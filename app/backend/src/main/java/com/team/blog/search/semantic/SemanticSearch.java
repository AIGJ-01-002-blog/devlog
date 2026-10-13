package com.team.blog.search.semantic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.team.blog.post.access.PostAccessPolicy;
import com.team.blog.search.semantic.EmbeddingClient.EmbedException;
import com.team.blog.search.semantic.EmbeddingClient.Purpose;

/**
 * 하이브리드 검색의 의미 검색 쪽 (spec 054). 검색어를 임베딩해 pgvector에서 가까운 공개 글을 찾는다.
 * <p>
 * 의미 검색은 덤이다. 공급자가 설정되지 않았거나, pgvector 표가 없거나(확장 권한 없는 공용 DB), 공급자가 응답하지 않으면
 * 빈 값을 돌려주고 검색은 키워드만으로 끝난다. 공급자 연결에 실패하면 downFor 동안 다시 부르지 않아 검색이 느려지지 않는다.
 * 검색어 벡터는 Redis에 보관해 같은 검색어는 공급자를 다시 부르지 않는다. 검색어 원문은 열쇠에 넣지 않는다(해시).
 */
@Service
@EnableConfigurationProperties(SemanticProperties.class)
public class SemanticSearch {
    private static final Logger log = LoggerFactory.getLogger(SemanticSearch.class);
    static final String DOWN = "search:embed:down";
    static final String QUERY_CACHE = "search:qvec:";
    static final String WARMING = "search:embed:warming";
    private static final String SIMILAR_PAIRS = """
            SELECT a.post_id, n.post_id, n.distance FROM post_embedding a
            CROSS JOIN LATERAL (
                SELECT b.post_id, a.embedding <=> b.embedding AS distance FROM post_embedding b
                WHERE b.model = a.model AND b.post_id = ANY (?) AND b.post_id <> a.post_id
                ORDER BY a.embedding <=> b.embedding LIMIT ?
            ) n
            WHERE a.model = ? AND a.post_id = ANY (?) AND n.distance <= ?""";

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final EmbeddingClient client;
    private final SemanticProperties props;
    private volatile Boolean tableReady;
    /** 예열 요청은 검색 응답을 기다리게 하지 않도록 따로 보낸다 */
    private final ExecutorService warmer = Executors.newVirtualThreadPerTaskExecutor();

    public SemanticSearch(JdbcTemplate jdbc, StringRedisTemplate redis, EmbeddingClient client, SemanticProperties props) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.client = client;
        this.props = props;
    }

    /** 시작할 때 의미 검색을 쓰는지 한 줄 남긴다. 운영에서 "왜 임베딩이 안 도는지"를 로그 한 줄로 남긴다 */
    @EventListener(ApplicationReadyEvent.class)
    public void logStatus() {
        if (!client.configured()) {
            log.info("의미 검색 꺼짐: 임베딩 공급자가 설정되지 않았습니다. 검색은 키워드만 씁니다");
            return;
        }
        boolean ready = tableReady();
        if (log.isInfoEnabled()) {
            log.info("의미 검색 {}: 공급자 {}, 모델 {}", ready ? "켜짐" : "꺼짐(post_embedding 표 없음)", client.provider(), client.model());
        }
    }

    /** 의미 검색을 쓸 수 있게 설정돼 있는지 (공급자가 지금 켜져 있는지는 보지 않는다) */
    public boolean enabled() {
        return client.configured() && tableReady();
    }

    boolean tableReady() {
        Boolean ready = tableReady;
        if (ready == null) {
            try {
                ready = Boolean.TRUE.equals(jdbc.queryForObject("SELECT to_regclass('post_embedding') IS NOT NULL", Boolean.class));
            } catch (RuntimeException e) {
                // 조회가 잠깐 실패한 것이면 다음에 다시 본다. 확인된 결과만 기억한다
                log.warn("post_embedding 표를 확인하지 못했습니다. 다음 검색 때 다시 봅니다: {}", e.getMessage());
                return false;
            }
            if (!ready) log.info("post_embedding 표가 없어(pgvector 없음) 검색은 키워드만 씁니다");
            tableReady = ready;
        }
        return ready;
    }

    /**
     * @param authorId 블로그 안 검색이면 그 블로그 주인
     * @return 가까운 순서의 공개 글 번호. 쓸 수 없으면 비어 있다
     */
    public Optional<List<Long>> nearest(String query, Long authorId) {
        if (!enabled()) return Optional.empty();
        Optional<String> vector = queryVector(query);
        if (vector.isEmpty()) return Optional.empty();
        List<Object> args = new ArrayList<>();
        args.add(vector.get());
        args.add(client.model());
        StringBuilder sql = new StringBuilder("""
                SELECT p.id FROM (
                    SELECT e.post_id, e.embedding <=> ?::vector AS distance FROM post_embedding e WHERE e.model = ?
                ) d
                JOIN post p ON p.id = d.post_id JOIN member m ON m.id = p.author_id
                WHERE\s""").append(PostAccessPolicy.PUBLIC_LIST_CONDITION).append(" AND d.distance <= ?");
        args.add(client.maxDistance());
        if (authorId != null) {
            sql.append(" AND p.author_id = ?");
            args.add(authorId);
        }
        sql.append(" ORDER BY d.distance, p.id DESC LIMIT ?");
        args.add(props.candidates());
        try {
            return Optional.of(jdbc.queryForList(sql.toString(), Long.class, args.toArray()));
        } catch (RuntimeException e) {
            // 벡터 차원이 다른 행이 섞이는 등 DB 쪽 오류도 검색을 막지 않는다
            log.warn("의미 검색을 건너뜁니다: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 임베딩이 가까운 두 글. distance는 코사인 거리(0에 가까울수록 비슷) */
    public record Pair(long a, long b, double distance) {}

    /**
     * 주제 브랜치(072)용: 주어진 글끼리 임베딩이 가까운 쌍. 글마다 가까운 perPost개까지 보고, 의미 검색 기준 거리에
     * distanceFactor를 곱한 거리보다 가까운 쌍만 돌려준다(검색보다 좁게 묶는다). 같은 쌍이 두 번 나올 수 있다.
     * 의미 검색을 쓸 수 없으면(pgvector 없음, 공급자 미설정) 빈 목록이고, 그때 주제 브랜치는 태그만으로 묶인다.
     */
    public List<Pair> similarPairs(Collection<Long> postIds, double distanceFactor, int perPost) {
        if (!enabled() || postIds.size() < 2) return List.of();
        double max = client.maxDistance() * distanceFactor;
        try {
            return jdbc.query(con -> {
                var ps = con.prepareStatement(SIMILAR_PAIRS);
                var ids = con.createArrayOf("bigint", postIds.toArray());
                ps.setArray(1, ids);
                ps.setInt(2, perPost);
                ps.setString(3, client.model());
                ps.setArray(4, ids);
                ps.setDouble(5, max);
                return ps;
            }, (rs, i) -> new Pair(rs.getLong(1), rs.getLong(2), rs.getDouble(3)));
        } catch (RuntimeException e) {
            log.warn("주제 묶음에 임베딩을 쓰지 못해 태그만 씁니다: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 글 화면 "내용이 비슷한 다른 글"(072): 이 글과 임베딩이 가까운 글 번호, 가까운 순. 의미 검색 기준 거리 안의 글만이다.
     * 공개 여부는 보지 않으니 부르는 쪽이 공개 목록 조건으로 다시 거른다. 쓸 수 없으면 빈 목록.
     */
    public List<Long> neighbors(long postId, int limit) {
        if (!enabled()) return List.of();
        try {
            return jdbc.query("""
                    SELECT b.post_id FROM post_embedding a JOIN post_embedding b ON b.model = a.model AND b.post_id <> a.post_id
                    WHERE a.post_id = ? AND a.model = ? AND (a.embedding <=> b.embedding) <= ?
                    ORDER BY a.embedding <=> b.embedding LIMIT ?""",
                    (rs, i) -> rs.getLong(1), postId, client.model(), client.maxDistance(), limit);
        } catch (RuntimeException e) {
            log.warn("비슷한 글에 임베딩을 쓰지 못해 태그만 씁니다: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 검색창을 열 때 임베딩 모델을 미리 올린다. 운영 Ollama는 쓰지 않으면 모델을 내려 메모리를 비우므로(OLLAMA_KEEP_ALIVE),
     * 사람이 검색어를 치는 동안 모델을 올려 두면 첫 검색도 의미 검색까지 제때 끝난다. 여러 사람이 동시에 열어도
     * warmEvery에 한 번만 보내고, 그 동안은 검색어 임베딩이 늦어도 공급자가 내려간 것으로 표시하지 않는다.
     */
    public void warmUp() {
        if (!enabled()) return;
        try {
            if (Boolean.TRUE.equals(redis.hasKey(DOWN))) return;
            if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(WARMING, "1", props.warmEvery()))) return;
        } catch (RuntimeException e) {
            return;
        }
        warmer.execute(() -> {
            try {
                client.embed(List.of("warm"), Purpose.QUERY, props.warmTimeout());
            } catch (EmbedException e) {
                markDown(e);
            }
        });
    }

    private boolean warming() {
        try {
            return Boolean.TRUE.equals(redis.hasKey(WARMING));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private Optional<String> queryVector(String query) {
        String key = QUERY_CACHE + client.model() + ":" + hash(query);
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) return Optional.of(cached);
            if (Boolean.TRUE.equals(redis.hasKey(DOWN))) return Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        try {
            String v = EmbeddingClient.literal(client.embed(List.of(query), Purpose.QUERY, props.queryTimeout()).get(0));
            cacheQueryVector(key, v);
            return Optional.of(v);
        } catch (EmbedException e) {
            // 예열 중이면 모델을 올리느라 늦은 것이라, 이번만 키워드로 끝내고 다음 검색은 다시 부른다
            if (!warming()) markDown(e);
            return Optional.empty();
        }
    }

    private void cacheQueryVector(String key, String v) {
        try {
            redis.opsForValue().set(key, v, props.queryCacheTtl());
        } catch (RuntimeException ignored) {
            // 보관하지 못해도 이번 검색에는 쓴다
        }
    }

    void markDown(EmbedException e) {
        log.warn("임베딩 공급자를 {} 동안 건너뜁니다: {}", props.downFor(), e.getMessage());
        try {
            redis.opsForValue().set(DOWN, "1", props.downFor());
        } catch (RuntimeException ignored) {
            // Redis가 없으면 다음 검색이 다시 시도한다
        }
    }

    boolean isDown() {
        try {
            return Boolean.TRUE.equals(redis.hasKey(DOWN));
        } catch (RuntimeException e) {
            return true;
        }
    }

    static String hash(String query) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(query.strip().toLowerCase().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
