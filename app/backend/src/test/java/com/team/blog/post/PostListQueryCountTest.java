package com.team.blog.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import com.team.blog.post.query.PostCardQuery;
import com.team.blog.support.IntegrationTest;

/**
 * 목록당 쿼리 1번 규칙(docs/10 §4)을 실제로 센다. 글 20개 목록을 지금 카드 쿼리와, 작성자·태그·수를 글마다 따로 읽는
 * N+1 방식으로 각각 읽어 SQL 문장 수와 지연 시간(p50·p95)을 비교한다. 시간은 환경마다 달라 출력만 하고, 문장 수만 검사한다.
 */
@Import(PostListQueryCountTest.CountingDataSource.class)
class PostListQueryCountTest extends IntegrationTest {
    private static final int POSTS = 20;
    private static final int WARMUP = 50;
    private static final int RUNS = 500;

    /** 이 스레드에서 준비한 SQL 문장 수. 알림 같은 다른 스레드의 쿼리는 세지 않는다 */
    static final ThreadLocal<AtomicInteger> STATEMENTS = new ThreadLocal<>();

    @Autowired PostCardQuery cards;

    @Test
    void 글_20개_목록은_카드_쿼리_1번이고_N플러스1이면_61번이다() throws Exception {
        Session me = signup(uniqueLogin("nplus"));
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < POSTS; i++) ids.add(publish(me, i));

        int card = count(() -> assertThat(cards.publicCards(ids)).hasSize(POSTS));
        int naive = count(() -> assertThat(nPlusOne(ids)).hasSize(POSTS));
        assertThat(card).isEqualTo(1);
        assertThat(naive).isEqualTo(1 + POSTS * 3);

        // 실제 목록 API 한 번(블로그 글 목록, 한 쪽 9개)에서 나가는 문장 수: 카드 + 다음 쪽 판단·작성자 확인 등 부가 쿼리
        int api = count(() -> browser().perform(get("/api/members/" + me.handle() + "/posts")).andExpect(status().isOk()));

        long[] cardNanos = time(() -> cards.publicCards(ids));
        long[] naiveNanos = time(() -> nPlusOne(ids));
        System.out.printf("[N+1 측정] 글 %d개, 반복 %d회(예열 %d회) | 카드 쿼리 %d문장 p50 %.2fms p95 %.2fms | N+1 %d문장 p50 %.2fms p95 %.2fms | 목록 API %d문장%n",
                POSTS, RUNS, WARMUP, card, ms(cardNanos, 50), ms(cardNanos, 95), naive, ms(naiveNanos, 50), ms(naiveNanos, 95), api);
    }

    /** 고치기 전 모양: 목록 1번 + 글마다 작성자·태그·수를 따로 읽는다 */
    private List<Map<String, Object>> nPlusOne(List<Long> ids) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT p.id, p.title, p.author_id FROM post p WHERE p.id = ANY (?::bigint[]) ORDER BY p.id DESC", (Object) ids.toArray(Long[]::new));
        for (Map<String, Object> row : rows) {
            long postId = ((Number) row.get("id")).longValue();
            row.put("author", jdbc.queryForMap("SELECT handle, nickname FROM member WHERE id = ?", row.get("author_id")));
            row.put("tags", jdbc.queryForList(
                    "SELECT t.name FROM post_tag pt JOIN tag t ON t.id = pt.tag_id WHERE pt.post_id = ? ORDER BY pt.position", String.class, postId));
            row.put("stat", jdbc.queryForMap("SELECT comment_count, like_count, view_count FROM post_stat WHERE post_id = ?", postId));
        }
        return rows;
    }

    private long publish(Session s, int i) throws Exception {
        long id = read(s.http().perform(asJson(post("/api/posts"), Map.of("title", "목록 " + i, "contentMd", "")))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        Map<String, Object> body = new HashMap<>();
        body.put("title", "목록 측정 글 " + i);
        body.put("contentMd", ARTICLE_BODY);
        body.put("visibility", "PUBLIC");
        body.put("tags", List.of("spring", "postgresql", "성능"));
        body.put("baseVersion", 0);
        s.http().perform(asJson(post("/api/posts/" + id + "/publish"), body).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk());
        return id;
    }

    private int count(Step step) throws Exception {
        AtomicInteger n = new AtomicInteger();
        STATEMENTS.set(n);
        try {
            step.run();
        } finally {
            STATEMENTS.remove();
        }
        return n.get();
    }

    private static long[] time(Runnable r) {
        for (int i = 0; i < WARMUP; i++) r.run();
        long[] nanos = new long[RUNS];
        for (int i = 0; i < RUNS; i++) {
            long t = System.nanoTime();
            r.run();
            nanos[i] = System.nanoTime() - t;
        }
        Arrays.sort(nanos);
        return nanos;
    }

    private static double ms(long[] sorted, int percentile) {
        int idx = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, idx)] / 1_000_000.0;
    }

    @FunctionalInterface
    interface Step {
        void run() throws Exception;
    }

    /** DataSource를 감싸 연결에서 문장을 준비할 때마다 센다 (측정 중인 스레드만) */
    @TestConfiguration
    static class CountingDataSource {
        @Bean
        static BeanPostProcessor countStatements() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof DataSource ds)) return bean;
                    return Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                            (p, m, args) -> {
                                Object result = invoke(ds, m, args);
                                return result instanceof Connection c ? counting(c) : result;
                            });
                }
            };
        }

        private static Connection counting(Connection c) {
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (p, m, args) -> {
                        String n = m.getName();
                        if (n.equals("prepareStatement") || n.equals("createStatement") || n.equals("prepareCall")) {
                            AtomicInteger counter = STATEMENTS.get();
                            if (counter != null) counter.incrementAndGet();
                        }
                        return invoke(c, m, args);
                    });
        }

        private static Object invoke(Object target, java.lang.reflect.Method m, Object[] args) throws Throwable {
            try {
                return m.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }
}
