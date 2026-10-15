package com.team.blog.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

/**
 * 통합 테스트 기반: 실제 PostgreSQL·Redis, MockMvc, 개발용 로그인으로 GitHub 가입 흐름을 그대로 탄다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTest {
    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 1_000_000);

    @Autowired protected MockMvc mvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected StringRedisTemplate redis;
    @Autowired @Qualifier("notificationExecutor") private ThreadPoolTaskExecutor notifier;
    protected final JsonMapper json = JsonMapper.builder().build();

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", Containers.POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", Containers.POSTGRES::getUsername);
        r.add("spring.datasource.password", Containers.POSTGRES::getPassword);
        r.add("spring.data.redis.host", Containers.REDIS::getHost);
        r.add("spring.data.redis.port", () -> Containers.REDIS.getMappedPort(6379));
        // AI 태그 추천(018)은 가짜 공급자로 켠다
        r.add("blog.ai.gemini.base-url", FakeAi.INSTANCE::baseUrl);
        r.add("blog.ai.local.base-url", FakeAi.INSTANCE::baseUrl);
        // 텔레그램(023)은 가짜 봇 서버로
        r.add("blog.telegram.base-url", FakeTelegram.INSTANCE::baseUrl);
        // 디스코드 웹훅(078)도 가짜 서버로
        r.add("blog.discord.base-url", FakeDiscord.INSTANCE::baseUrl);
        // 포인트 모의투자 시세(#267)도 가짜 업비트로
        r.add("blog.paper.base-url", FakeUpbit.INSTANCE::baseUrl);
        // 자동매매 화면 차트(spec 101)도 가짜 거래소로
        r.add("blog.trades.binance-url", FakeExchanges.INSTANCE::baseUrl);
        r.add("blog.trades.upbit-url", FakeExchanges.INSTANCE::baseUrl);
    }

    @BeforeEach
    protected void resetRateLimits() {
        var keys = redis.keys("rl:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    /**
     * 알림은 운영과 같이 커밋 뒤 다른 스레드에서 만든다(같은 스레드면 동시 요청이 연결을 둘씩 잡아 풀이 막힌다).
     * 사건은 응답 전에 대기열에 들어가므로, 읽기 전에 대기열이 빌 때까지 기다리면 결과가 정해진다.
     */
    protected void drain() throws InterruptedException {
        long until = System.currentTimeMillis() + 10_000;
        while (notifier.getQueueSize() > 0 || notifier.getActiveCount() > 0) {
            if (System.currentTimeMillis() > until) throw new AssertionError("알림 처리가 끝나지 않았습니다");
            Thread.sleep(5);
        }
    }

    /**
     * 테스트마다 겹치지 않는 GitHub 아이디. 이름과 번호 사이에 x를 둔다: 금칙어 검사는 숫자를 글자로도 읽어서(1→i, 7→t)
     * "trash"+"17"이 "trashit"(shit)이 되는 것처럼, 실행 순서에 따라 번호가 금칙어를 만들어 가입이 400으로 실패했다.
     */
    protected static String uniqueLogin(String base) {
        return base + "x" + SEQ.incrementAndGet();
    }

    protected Browser browser() {
        return new Browser(mvc);
    }

    /** GitHub 인증만 마친 가입 대기 브라우저. */
    protected Browser githubAuthenticated(String githubId, String login, String name, String email) throws Exception {
        Browser b = browser();
        Map<String, Object> body = new java.util.HashMap<>(Map.of("providerUserId", githubId, "login", login, "name", name == null ? "" : name));
        if (email != null) body.put("email", email);
        b.perform(asJson(post("/api/dev/login"), body)).andExpect(status().isOk());
        return b;
    }

    /** 이미 가입한 GitHub 계정으로 새 브라우저에서 로그인. */
    protected Browser relogin(Session s) throws Exception {
        Browser b = browser();
        b.perform(asJson(post("/api/dev/login"), Map.of("providerUserId", s.githubId(), "login", "x", "name", "x", "email", "x@example.com")))
                .andExpect(status().isOk());
        return b;
    }

    /** 가입까지 마친 로그인 브라우저. */
    protected Session signup(String login) throws Exception {
        String letters = login.replaceAll("[^a-zA-Z0-9]", "");
        return signup(login, "닉" + letters.substring(Math.max(0, letters.length() - 7)));
    }

    protected Session signup(String login, String nickname) throws Exception {
        String githubId = String.valueOf(SEQ.incrementAndGet() + 10_000_000L);
        Browser b = githubAuthenticated(githubId, login, login, login.toLowerCase() + "@example.com");
        String body = login.toLowerCase().replace('-', '_');
        b.perform(asJson(post("/api/auth/signup"), Map.of("handleBody", body, "nickname", nickname,
                        "agreeTerms", true, "agreePrivacy", true)))
                .andExpect(status().isCreated());
        JsonNode me = read(b.perform(get("/api/auth/me")).andReturn());
        return new Session(b, me.path("member").path("id").asLong(), me.path("member").path("handle").asString(), githubId);
    }

    protected JsonNode read(MvcResult r) throws Exception {
        String s = r.getResponse().getContentAsString();
        return s.isEmpty() ? json.createObjectNode() : json.readTree(s);
    }

    protected MockHttpServletRequestBuilder asJson(MockHttpServletRequestBuilder b, Object body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    }

    public record Session(Browser http, long memberId, String handle, String githubId) {}

}
