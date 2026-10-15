package com.team.blog.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;

import com.team.blog.support.Browser;
import com.team.blog.support.IntegrationTest;

/** 001 US1·US2 수용 기준. */
class SignupLoginTest extends IntegrationTest {

    private static String gid() {
        return String.valueOf(900_000_000L + System.nanoTime() % 100_000_000L);
    }

    private Map<String, Object> form(String handleBody, String nickname) {
        return Map.of("handleBody", handleBody, "nickname", nickname, "agreeTerms", true, "agreePrivacy", true);
    }

    @Test
    void 처음_GitHub_인증하면_계정_없이_가입_마무리로_가고_주소와_닉네임이_미리_채워진다() throws Exception {
        String login = uniqueLogin("Octo-Cat");
        Integer before = jdbc.queryForObject("SELECT count(*) FROM member", Integer.class);
        Browser b = githubAuthenticated(gid(), login, "Octo Cat", login.toLowerCase() + "@x.com");
        JsonNode draft = read(b.perform(get("/api/auth/signup")).andExpect(status().isOk()).andReturn());
        assertThat(draft.path("prefix").asString()).isEqualTo("gi-");
        assertThat(draft.path("handleBody").asString()).isEqualTo(login.toLowerCase().replace('-', '_'));
        assertThat(draft.path("nickname").asString()).isEqualTo("OctoCat");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member", Integer.class)).isEqualTo(before);
        b.perform(get("/api/auth/me")).andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.pendingSignup").value(true));
        // 가입 대기 상태로는 쓰기 API를 쓸 수 없다
        b.perform(asJson(post("/api/posts"), Map.of())).andExpect(status().isUnauthorized());
    }

    @Test
    void 필수_약관에_동의하지_않으면_가입되지_않는다() throws Exception {
        String login = uniqueLogin("noagree");
        Browser b = githubAuthenticated(gid(), login, login, login + "@x.com");
        MvcResult r = b.perform(asJson(post("/api/auth/signup"),
                        Map.of("handleBody", login, "nickname", "약관거부" + login.substring(login.length() - 3), "agreeTerms", false, "agreePrivacy", true)))
                .andExpect(status().isBadRequest()).andReturn();
        assertThat(read(r).path("errors").toString()).contains("agreeTerms");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member WHERE handle = ?", Integer.class, "gi-" + login)).isZero();
    }

    @Test
    void 가입하면_고친_주소로_만들어지고_약관_종류_버전_일자가_기록된다() throws Exception {
        String login = uniqueLogin("orig");
        Browser b = githubAuthenticated(gid(), login, login, login + "@x.com");
        String body = "my_blog" + login.substring(login.length() - 5);
        b.perform(asJson(post("/api/auth/signup"), form(body, "블로거" + login.substring(login.length() - 4))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.handle").value("gi-" + body));
        Long id = jdbc.queryForObject("SELECT id FROM member WHERE handle = ?", Long.class, "gi-" + body);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT type, version FROM member_agreement WHERE member_id = ? ORDER BY type", id);
        assertThat(rows).extracting(m -> m.get("type")).containsExactly("PRIVACY", "TERMS");
        assertThat(rows).extracting(m -> m.get("version")).containsExactly("2026-10-10", "2026-10-07");
        assertThat(jdbc.queryForObject("SELECT email_verified_at IS NOT NULL FROM auth_identity WHERE member_id = ?", Boolean.class, id)).isTrue();
        b.perform(get("/api/auth/me")).andExpect(jsonPath("$.member.handle").value("gi-" + body));
    }

    @Test
    void 같은_GitHub_계정으로_다시_로그인하면_가입_화면_없이_같은_계정이다() throws Exception {
        Session first = signup(uniqueLogin("again"));
        for (int i = 0; i < 5; i++) {
            Browser b = browser();
            MvcResult r = b.perform(asJson(post("/api/dev/login"),
                    Map.of("providerUserId", first.githubId(), "login", "renamed" + i, "name", "x", "email", "x@x.com"))).andReturn();
            assertThat(read(r).path("redirect").asString()).isEqualTo("/");
            b.perform(get("/api/auth/me")).andExpect(jsonPath("$.member.id").value(first.memberId()))
                    .andExpect(jsonPath("$.member.handle").value(first.handle()));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_identity WHERE provider = 'GITHUB' AND provider_user_id = ?",
                Integer.class, first.githubId())).isEqualTo(1);
    }

    @Test
    void 직전_로그인을_보여준다_첫_로그인은_null() throws Exception {
        Session s = signup(uniqueLogin("prev"));
        s.http().perform(get("/api/auth/me")).andExpect(jsonPath("$.previousLogin.at").doesNotExist());
        Browser again = relogin(s);
        again.perform(get("/api/auth/me"))
                .andExpect(jsonPath("$.previousLogin.at").exists())
                .andExpect(jsonPath("$.previousLogin.provider").value("GITHUB"));
    }

    @Test
    void 같은_주소로_동시에_10건_가입하면_1건만_성공한다() throws Exception {
        String body = uniqueLogin("race");
        List<Browser> browsers = new ArrayList<>();
        for (int i = 0; i < 10; i++) browsers.add(githubAuthenticated(gid() + i, body + i, "x", "r" + i + "@x.com"));
        List<Integer> statuses = concurrently(10, i -> browsers.get(i).perform(asJson(post("/api/auth/signup"),
                form(body, "경쟁자" + (char) ('a' + i) + body.substring(body.length() - 3)))).andReturn().getResponse().getStatus());
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(9);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member WHERE handle = ?", Integer.class, "gi-" + body)).isEqualTo(1);
    }

    @Test
    void 같은_닉네임으로_동시에_가입해도_1건만_성공한다_대소문자_무시() throws Exception {
        String suffix = String.valueOf(System.nanoTime() % 100000);
        List<Browser> browsers = new ArrayList<>();
        for (int i = 0; i < 10; i++) browsers.add(githubAuthenticated(gid() + i, "nick" + suffix + i, "x", "n" + i + "@x.com"));
        List<Integer> statuses = concurrently(10, i -> browsers.get(i).perform(asJson(post("/api/auth/signup"),
                form("nick" + suffix + "_" + i, (i % 2 == 0 ? "Same" : "sAME") + suffix))).andReturn().getResponse().getStatus());
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(9);
    }

    @Test
    void 같은_소셜_계정으로_가입을_두_번_눌러도_계정은_하나다() throws Exception {
        String login = uniqueLogin("double");
        String id = gid();
        Browser a = githubAuthenticated(id, login, "x", login + "@x.com");
        Browser b = githubAuthenticated(id, login, "x", login + "@x.com");
        a.perform(asJson(post("/api/auth/signup"), form(login, "두번" + login.substring(login.length() - 4)))).andExpect(status().isCreated());
        b.perform(asJson(post("/api/auth/signup"), form(login + "_x", "세번" + login.substring(login.length() - 4)))).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_identity WHERE provider_user_id = ?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void 대문자_주소로_접속하면_소문자로_이동한다() throws Exception {
        Session s = signup(uniqueLogin("upper"));
        mvc.perform(get("/@" + s.handle().toUpperCase()))
                .andExpect(status().isMovedPermanently())
                .andExpect(r -> assertThat(r.getResponse().getHeader("Location")).isEqualTo("/@" + s.handle()));
    }

    @Test
    void 가입_후_블로그_주소는_어떤_요청으로도_바뀌지_않는다() throws Exception {
        Session s = signup(uniqueLogin("fixed"));
        s.http().perform(asJson(patch("/api/me/nickname"), Map.of("nickname", "새닉네임" + s.memberId() % 100, "handle", "gi-hacked")));
        s.http().perform(asJson(patch("/api/me/profile"), Map.of("handle", "gi-hacked", "bio", "x")));
        assertThat(jdbc.queryForObject("SELECT handle FROM member WHERE id = ?", String.class, s.memberId())).isEqualTo(s.handle());
    }

    @Test
    void 정지된_계정은_로그인되지_않고_기한과_사유가_안내된다() throws Exception {
        Session s = signup(uniqueLogin("susp"));
        Session admin = signup(uniqueLogin("adm"));
        Instant until = Instant.now().plus(10, ChronoUnit.DAYS);
        jdbc.update("UPDATE member SET status = 'SUSPENDED' WHERE id = ?", s.memberId());
        jdbc.update("INSERT INTO member_suspension(member_id, reason, ends_at, suspended_by) VALUES (?, ?, ?, ?)",
                s.memberId(), "스팸 게시", Timestamp.from(until), admin.memberId());
        // 정지 전에 로그인해 둔 세션의 쓰기 요청은 거부된다
        s.http().perform(asJson(patch("/api/me/nickname"), Map.of("nickname", "바꾸기" + s.memberId() % 1000)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
        // 새 로그인은 거부되고 안내를 받는다
        Browser b = browser();
        MvcResult r = b.perform(asJson(post("/api/dev/login"),
                Map.of("providerUserId", s.githubId(), "login", "x", "name", "x", "email", "x@x.com"))).andReturn();
        assertThat(read(r).path("redirect").asString()).isEqualTo("/login?error=ACCOUNT_SUSPENDED");
        b.perform(get("/api/auth/me")).andExpect(jsonPath("$.authenticated").value(false));
        b.perform(get("/api/auth/login-error"))
                .andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"))
                .andExpect(jsonPath("$.reason").value("스팸 게시"))
                .andExpect(jsonPath("$.suspendedUntil").exists());
    }

    @Test
    void 정지_기한이_지나면_로그인할_때_정상으로_돌아온다() throws Exception {
        Session s = signup(uniqueLogin("lift"));
        Session admin = signup(uniqueLogin("adm2"));
        jdbc.update("UPDATE member SET status = 'SUSPENDED' WHERE id = ?", s.memberId());
        jdbc.update("INSERT INTO member_suspension(member_id, reason, started_at, ends_at, suspended_by) VALUES (?, ?, ?, ?, ?)",
                s.memberId(), "기한 지남", Timestamp.from(Instant.now().minus(3, ChronoUnit.DAYS)),
                Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS)), admin.memberId());
        MvcResult r = browser().perform(asJson(post("/api/dev/login"),
                Map.of("providerUserId", s.githubId(), "login", "x", "name", "x", "email", "x@x.com"))).andReturn();
        assertThat(read(r).path("redirect").asString()).isEqualTo("/");
        assertThat(jdbc.queryForObject("SELECT status FROM member WHERE id = ?", String.class, s.memberId())).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT lifted_at IS NOT NULL FROM member_suspension WHERE member_id = ?", Boolean.class, s.memberId())).isTrue();
    }

    @Test
    void 약관_버전이_바뀌면_재동의_전에는_다른_요청이_막힌다() throws Exception {
        Session s = signup(uniqueLogin("reagree"));
        jdbc.update("UPDATE member_agreement SET version = '2020-01-01' WHERE member_id = ? AND type = 'TERMS'", s.memberId());
        Browser b = browser();
        MvcResult r = b.perform(asJson(post("/api/dev/login"),
                Map.of("providerUserId", s.githubId(), "login", "x", "name", "x", "email", "x@x.com"))).andReturn();
        assertThat(read(r).path("redirect").asString()).startsWith("/agreements");
        b.perform(get("/api/auth/me")).andExpect(jsonPath("$.agreementRequired").value(true));
        b.perform(get("/api/me/posts")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AGREEMENT_REQUIRED"));
        b.perform(asJson(post("/api/auth/agreements"), Map.of("agreeTerms", true, "agreePrivacy", true)))
                .andExpect(status().isNoContent());
        b.perform(get("/api/me/posts")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT version FROM member_agreement WHERE member_id = ? AND type = 'TERMS'", String.class, s.memberId()))
                .isEqualTo("2026-10-07");
    }

    @Test
    void 닉네임을_바꾸면_30일_동안_다시_바꿀_수_없고_같은_값은_제한을_시작하지_않는다() throws Exception {
        Session s = signup(uniqueLogin("nickch"));
        String current = jdbc.queryForObject("SELECT nickname FROM member WHERE id = ?", String.class, s.memberId());
        s.http().perform(asJson(patch("/api/me/nickname"), Map.of("nickname", current))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT nickname_changed_at FROM member WHERE id = ?", Timestamp.class, s.memberId())).isNull();
        String next = "새이름" + s.memberId() % 10000;
        s.http().perform(asJson(patch("/api/me/nickname"), Map.of("nickname", next)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value(next));
        s.http().perform(asJson(patch("/api/me/nickname"), Map.of("nickname", "또바꿈" + s.memberId() % 10000)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NICKNAME_CHANGE_TOO_SOON"));
    }

    @Test
    void 이전_닉네임은_바로_다른_사람이_쓸_수_있다() throws Exception {
        Session a = signup(uniqueLogin("relA"));
        Session b = signup(uniqueLogin("relB"));
        String old = jdbc.queryForObject("SELECT nickname FROM member WHERE id = ?", String.class, a.memberId());
        a.http().perform(asJson(patch("/api/me/nickname"), Map.of("nickname", "옮긴이" + a.memberId() % 10000))).andExpect(status().isOk());
        b.http().perform(asJson(patch("/api/me/nickname"), Map.of("nickname", old.toUpperCase()))).andExpect(status().isOk());
    }

    @Test
    void 로그아웃하면_세션이_끝나고_쓰기_요청이_거부된다() throws Exception {
        Session s = signup(uniqueLogin("bye"));
        s.http().perform(asJson(post("/api/auth/logout"), Map.of())).andExpect(status().isNoContent());
        s.http().perform(asJson(patch("/api/me/nickname"), Map.of("nickname", "다시" + s.memberId() % 1000)))
                .andExpect(status().isUnauthorized());
        s.http().perform(get("/api/auth/me")).andExpect(jsonPath("$.authenticated").value(false));
    }

    @Test
    void 로그인_후_돌아갈_주소는_사이트_안_상대_경로만() throws Exception {
        Session s = signup(uniqueLogin("redir"));
        for (String bad : List.of("https://evil.example", "//evil.example", "/\\evil", "javascript:alert(1)")) {
            MvcResult r = browser().perform(asJson(post("/api/dev/login"),
                    Map.of("providerUserId", s.githubId(), "login", "x", "name", "x", "email", "x@x.com", "redirect", bad))).andReturn();
            assertThat(read(r).path("redirect").asString()).isEqualTo("/");
        }
        MvcResult ok = browser().perform(asJson(post("/api/dev/login"),
                Map.of("providerUserId", s.githubId(), "login", "x", "name", "x", "email", "x@x.com", "redirect", "/manage/posts?tab=drafts"))).andReturn();
        assertThat(read(ok).path("redirect").asString()).isEqualTo("/manage/posts?tab=drafts");
    }

    @Test
    void 같은_IP_로그인은_1분에_20회이고_헤더를_위조해도_우회할_수_없다() throws Exception {
        int ok = 0, limited = 0;
        for (int i = 0; i < 25; i++) {
            int st = browser().from("203.0.113.7").perform(asJson(post("/api/dev/login").header("X-Forwarded-For", "10.0.0." + i),
                            Map.of("providerUserId", "1", "login", "x", "name", "x")))
                    .andReturn().getResponse().getStatus();
            if (st == 429) limited++; else ok++;
        }
        assertThat(ok).isEqualTo(20);
        assertThat(limited).isEqualTo(5);
    }

    @Test
    void 퍼센트_인코딩한_로그인_주소도_같은_IP_한도를_넘을_수_없다() throws Exception {
        // 보안 점검(2026-10-15): 걸러내기 필터가 날 주소(getRequestURI)를 보면 /api/dev/%6cogin은 한도를 건너뛰고 컨트롤러에는 닿았다
        int limited = 0;
        for (int i = 0; i < 25; i++) {
            String path = i % 2 == 0 ? "/api/dev/%6cogin" : "/api/dev/login";
            int st = browser().from("203.0.113.9").perform(asJson(post(java.net.URI.create(path)),
                            Map.of("providerUserId", "1", "login", "x", "name", "x")))
                    .andReturn().getResponse().getStatus();
            if (st == 429) limited++;
        }
        assertThat(limited).isEqualTo(5);
    }

    @Test
    void 가입_대기_정보가_없으면_410() throws Exception {
        browser().perform(asJson(post("/api/auth/signup"), form("whatever", "아무개")))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("SIGNUP_EXPIRED"));
    }

    @Test
    void 소셜_사진_대신_받기는_가입_대기_중에만_되고_사진_주소가_없으면_404() throws Exception {
        browser().perform(get("/api/auth/signup/avatar"))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("SIGNUP_EXPIRED"));
        String login = uniqueLogin("noavatar");
        Browser b = githubAuthenticated(gid(), login, login, login + "@x.com");
        b.perform(get("/api/auth/signup/avatar"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("AVATAR_NOT_AVAILABLE"));
    }

    @Test
    void 중복_확인_API는_사용_중인_주소에_다음_번호를_제안한다() throws Exception {
        Session s = signup(uniqueLogin("avail"));
        mvc.perform(get("/api/handles/availability").param("handle", s.handle()))
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.reason").value("TAKEN"))
                .andExpect(jsonPath("$.suggestion").value(s.handle().substring(3) + "_2"));
        mvc.perform(get("/api/handles/availability").param("handle", "go_kim"))
                .andExpect(jsonPath("$.reason").value("PREFIX_LOOKALIKE"));
        mvc.perform(get("/api/nicknames/availability").param("nickname", "관리자"))
                .andExpect(jsonPath("$.code").value("NICKNAME_RESERVED"));
    }

    interface Task<T> {
        T run(int i) throws Exception;
    }

    static <T> List<T> concurrently(int n, Task<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int idx = i;
            futures.add(pool.submit(() -> {
                start.await();
                return task.run(idx);
            }));
        }
        start.countDown();
        List<T> out = new ArrayList<>();
        for (Future<T> f : futures) out.add(f.get());
        pool.shutdown();
        return out;
    }
}
