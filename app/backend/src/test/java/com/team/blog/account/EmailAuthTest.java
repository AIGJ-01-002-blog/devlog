package com.team.blog.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;

import com.team.blog.account.application.AgreementService;
import com.team.blog.account.domain.AgreementType;
import com.team.blog.shared.mail.Mail;
import com.team.blog.shared.mail.Mailer;
import com.team.blog.shared.mail.OutboxMailer;
import com.team.blog.support.Browser;
import com.team.blog.support.IntegrationTest;

/** 004 이메일 가입·인증, 이메일 로그인·잠금, 비밀번호 재설정·변경, Google·이메일 없는 GitHub 가입 수용 기준. */
class EmailAuthTest extends IntegrationTest {
    private static final String PASSWORD = "Blog!pass77";
    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_-]+)");

    @Autowired Mailer mailer;
    @Autowired AgreementService agreementService;

    record Account(Browser http, String email, long memberId, String handle) {}

    private OutboxMailer outbox() {
        return (OutboxMailer) mailer;
    }

    private List<Mail> mailsTo(String email) {
        return outbox().recent().stream().filter(m -> m.to().equals(email)).toList();
    }

    /** 가장 최근에 받은 링크 메일의 토큰. */
    private String lastToken(String email) {
        for (Mail m : mailsTo(email)) {
            Matcher matcher = TOKEN.matcher(m.body());
            if (matcher.find()) return matcher.group(1);
        }
        throw new AssertionError("링크 메일이 없다: " + email);
    }

    private String uniqueEmail(String base) {
        return uniqueLogin(base) + "@example.com";
    }

    private Map<String, Object> signupForm(String email, String handleBody, String nickname, String password) {
        Map<String, Object> m = new HashMap<>();
        m.put("email", email);
        m.put("handleBody", handleBody);
        m.put("password", password);
        m.put("passwordConfirm", password);
        m.put("nickname", nickname);
        m.put("agreeTerms", true);
        m.put("agreePrivacy", true);
        return m;
    }

    private Account emailSignup(String email) throws Exception {
        String norm = email.strip().toLowerCase();
        String body = norm.substring(0, norm.indexOf('@')).replaceAll("[^a-z0-9]", "");
        Browser b = browser();
        b.perform(asJson(post("/api/auth/signup/email"),
                        signupForm(email, body, "닉" + body.substring(Math.max(0, body.length() - 7)), PASSWORD)))
                .andExpect(status().isCreated());
        JsonNode me = read(b.perform(get("/api/auth/me")).andReturn());
        return new Account(b, norm, me.path("member").path("id").asLong(), me.path("member").path("handle").asString());
    }

    private Account verified(String base) throws Exception {
        Account a = emailSignup(uniqueEmail(base));
        a.http().perform(asJson(post("/api/auth/email/verify"), Map.of("token", lastToken(a.email())))).andExpect(status().isOk());
        return a;
    }

    private MvcResult login(Browser b, String email, String password) throws Exception {
        return b.perform(asJson(post("/api/auth/login"), Map.of("email", email, "password", password))).andReturn();
    }

    private void lockReset() {
        var keys = redis.keys("auth:fail:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
    }

    // ---------- US1 가입·인증 ----------

    @Test
    void 이메일_앞부분으로_주소를_미리_채우고_겹치면_다음_번호를_제안한다() throws Exception {
        mvc.perform(get("/api/handles/suggestion").param("material", "Kim.Min-Seo+blog"))
                .andExpect(jsonPath("$.handleBody").value("kim_min_seo"));
        mvc.perform(get("/api/handles/suggestion").param("material", "go.kim")).andExpect(jsonPath("$.handleBody").value("gokim"));
        mvc.perform(get("/api/handles/suggestion").param("material", "ab")).andExpect(jsonPath("$.handleBody").value(
                org.hamcrest.Matchers.matchesPattern("user_\\d{6}")));
        Account a = emailSignup(uniqueEmail("dup"));
        String local = a.email().substring(0, a.email().indexOf('@'));
        mvc.perform(get("/api/handles/suggestion").param("material", local))
                .andExpect(jsonPath("$.handleBody").value(local + "_2"));
    }

    @Test
    void 가입하면_로그인되고_약관이_기록되고_24시간_인증_메일이_간다() throws Exception {
        String email = uniqueEmail("join");
        Account a = emailSignup("  " + email.toUpperCase() + " ");
        assertThat(a.handle()).doesNotContain("-");
        a.http().perform(get("/api/auth/me"))
                .andExpect(jsonPath("$.authenticated").value(true))
                .andExpect(jsonPath("$.emailVerified").value(false))
                .andExpect(jsonPath("$.previousLogin.provider").value("LOCAL"));
        Map<String, Object> row = jdbc.queryForMap("SELECT provider, provider_user_id, email, password_hash, email_verified_at FROM auth_identity WHERE member_id = ?", a.memberId());
        assertThat(row).containsEntry("provider_user_id", email);
        assertThat(row.get("email_verified_at")).isNull();
        assertThat((String) row.get("password_hash")).startsWith("$2").doesNotContain(PASSWORD);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member_agreement WHERE member_id = ?", Integer.class, a.memberId())).isEqualTo(2);
        List<Mail> mails = mailsTo(email);
        assertThat(mails).hasSize(1);
        assertThat(mails.getFirst().body()).contains("/verify-email?token=").contains("24시간");
        Long ttl = redis.getExpire("auth:verify:member:" + a.memberId());
        assertThat(ttl).isBetween(23 * 3600L, 24 * 3600L);
    }

    @Test
    void 같은_IP의_이메일_가입은_1시간에_정한_계정_수까지만_받는다() throws Exception {
        long seed = System.nanoTime() % 100000;
        // 입력을 틀린 요청은 세지 않는다
        Map<String, Object> bad = signupForm("not-an-email", "iplimitbad" + seed, "틀린입력" + seed % 1000, PASSWORD);
        browser().perform(asJson(post("/api/auth/signup/email"), bad)).andExpect(status().isBadRequest());
        for (int i = 0; i < 10; i++) {
            String email = uniqueEmail("iplimit" + i);
            browser().perform(asJson(post("/api/auth/signup/email"),
                            signupForm(email, "iplimit" + seed + "x" + i, "제한" + i + "닉" + seed % 1000, PASSWORD)))
                    .andExpect(status().isCreated());
        }
        String body = browser().perform(asJson(post("/api/auth/signup/email"),
                        signupForm(uniqueEmail("iplimit10"), "iplimit" + seed + "x10", "제한열닉" + seed % 1000, PASSWORD)))
                .andExpect(status().isTooManyRequests())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("TOO_MANY_SIGNUPS");
    }

    @Test
    void 가입_화면의_AI_동의는_선택이라_따로_기록되고_안_하면_기록이_없다() throws Exception {
        // 이메일 가입: 선택 항목에 동의
        String email = uniqueEmail("aiyes");
        Map<String, Object> form = signupForm(email, email.substring(0, email.indexOf('@')).replaceAll("[^a-z0-9]", ""), "에이아이예", PASSWORD);
        form.put("agreeAi", true);
        Browser b = browser();
        b.perform(asJson(post("/api/auth/signup/email"), form)).andExpect(status().isCreated());
        long yes = read(b.perform(get("/api/auth/me")).andReturn()).path("member").path("id").asLong();
        assertThat(jdbc.queryForList("SELECT type FROM member_agreement WHERE member_id = ? ORDER BY type", String.class, yes))
                .containsExactly("AI", "PRIVACY", "TERMS");
        assertThat(jdbc.queryForObject("SELECT version FROM member_agreement WHERE member_id = ? AND type = 'AI'", String.class, yes))
                .isEqualTo(agreementService.currentVersion(AgreementType.AI));

        // 소셜 가입: 선택 항목을 비우면 필수 둘만 남고 가입은 된다
        String login = uniqueLogin("ainone");
        Browser g = githubAuthenticated(String.valueOf(System.nanoTime() % 1_000_000_000L + 20_000_000L), login, login, login + "@example.com");
        g.perform(asJson(post("/api/auth/signup"), Map.of("handleBody", login.toLowerCase().replace('-', '_'), "nickname", "에이아이노",
                        "agreeTerms", true, "agreePrivacy", true, "agreeAi", false)))
                .andExpect(status().isCreated());
        long no = read(g.perform(get("/api/auth/me")).andReturn()).path("member").path("id").asLong();
        assertThat(jdbc.queryForList("SELECT type FROM member_agreement WHERE member_id = ? ORDER BY type", String.class, no))
                .containsExactly("PRIVACY", "TERMS");

        // 소셜 가입: 선택 항목에 동의하면 AI도 지금 버전으로 기록된다
        String login2 = uniqueLogin("aisocial");
        Browser s = githubAuthenticated(String.valueOf(System.nanoTime() % 1_000_000_000L + 30_000_000L), login2, login2, login2 + "@example.com");
        s.perform(asJson(post("/api/auth/signup"), Map.of("handleBody", login2.toLowerCase().replace('-', '_'), "nickname", "에이아이소",
                        "agreeTerms", true, "agreePrivacy", true, "agreeAi", true)))
                .andExpect(status().isCreated());
        long social = read(s.perform(get("/api/auth/me")).andReturn()).path("member").path("id").asLong();
        assertThat(jdbc.queryForObject("SELECT version FROM member_agreement WHERE member_id = ? AND type = 'AI'", String.class, social))
                .isEqualTo(agreementService.currentVersion(AgreementType.AI));
    }

    @Test
    void 인증_전에는_글쓰기가_막히고_삭제_프로필_비밀번호_변경은_된다_인증하면_쓸_수_있다() throws Exception {
        Account a = emailSignup(uniqueEmail("unv"));
        a.http().perform(asJson(post("/api/posts"), Map.of()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
        // 글을 고치는 쓰기 행동도 대상 글을 보기 전에 계정 상태로 막힌다 (docs/42 §3 판정 순서)
        long postId = 987_654_321L;
        a.http().perform(asJson(put("/api/posts/" + postId + "/autosave"), Map.of("title", "t", "contentMd", "c", "baseVersion", 0)))
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
        a.http().perform(asJson(post("/api/posts/" + postId + "/publish"), Map.of("title", "t", "contentMd", "c", "visibility", "PUBLIC", "baseVersion", 0, "tags", List.of())))
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
        a.http().perform(asJson(patch("/api/posts/" + postId + "/visibility"), Map.of("visibility", "PRIVATE")))
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
        a.http().perform(put("/api/posts/" + postId + "/like").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
        a.http().perform(delete("/api/posts/" + postId + "/draft").with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf()))
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
        // 허용: 닉네임 수정, 비밀번호 변경
        a.http().perform(asJson(patch("/api/me/nickname"), Map.of("nickname", "새닉" + a.memberId() % 100000)))
                .andExpect(status().isOk());
        a.http().perform(asJson(put("/api/me/password"), Map.of("currentPassword", PASSWORD, "password", "New!pass88", "passwordConfirm", "New!pass88")))
                .andExpect(status().isNoContent());

        a.http().perform(asJson(post("/api/auth/email/verify"), Map.of("token", lastToken(a.email()))))
                .andExpect(status().isOk());
        a.http().perform(get("/api/auth/me")).andExpect(jsonPath("$.emailVerified").value(true));
        a.http().perform(asJson(post("/api/posts"), Map.of())).andExpect(status().isCreated());
    }

    @Test
    void 인증_전_글쓰기_막기는_퍼센트_인코딩한_주소로도_건너뛸_수_없다() throws Exception {
        // 보안 점검(2026-10-15): 날 주소로 비교하면 /api/%70osts·/%61pi/posts가 막기를 건너뛰고 글쓰기에 닿았다
        Account a = emailSignup(uniqueEmail("enc"));
        for (String path : List.of("/api/%70osts", "/%61pi/posts", "/api/posts%2F")) {
            int st = a.http().perform(asJson(post(java.net.URI.create(path)), Map.of())).andReturn().getResponse().getStatus();
            assertThat(st).as(path).isNotEqualTo(201);
        }
        a.http().perform(asJson(post(java.net.URI.create("/api/%70osts")), Map.of()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
    }

    @Test
    void 인증_링크는_한_번만_쓰이고_다시_보내면_이전_링크는_무효다_재발송은_1분에_1번() throws Exception {
        Account a = emailSignup(uniqueEmail("link"));
        String first = lastToken(a.email());
        a.http().perform(asJson(post("/api/auth/email/resend"), Map.of())).andExpect(status().isAccepted());
        a.http().perform(asJson(post("/api/auth/email/resend"), Map.of()))
                .andExpect(status().isTooManyRequests());
        String second = lastToken(a.email());
        assertThat(second).isNotEqualTo(first);
        Browser other = browser(); // 다른 브라우저에서 링크를 열어도 된다
        other.perform(asJson(post("/api/auth/email/verify"), Map.of("token", first)))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("LINK_EXPIRED"));
        other.perform(asJson(post("/api/auth/email/verify"), Map.of("token", second))).andExpect(status().isOk());
        other.perform(asJson(post("/api/auth/email/verify"), Map.of("token", second))).andExpect(status().isGone());
        assertThat(jdbc.queryForObject("SELECT email_verified_at IS NOT NULL FROM auth_identity WHERE member_id = ?", Boolean.class, a.memberId())).isTrue();
        a.http().perform(asJson(post("/api/auth/email/resend"), Map.of())).andExpect(status().isConflict());
    }

    @Test
    void 만료된_인증_링크는_쓸_수_없다() throws Exception {
        Account a = emailSignup(uniqueEmail("exp"));
        String token = lastToken(a.email());
        // 24시간이 지난 것처럼: 링크 기록이 Redis TTL로 사라진 상태
        redis.delete("auth:verify:member:" + a.memberId());
        a.http().perform(asJson(post("/api/auth/email/verify"), Map.of("token", token)))
                .andExpect(status().isGone()).andExpect(jsonPath("$.message").value("링크가 만료됐어요."));
    }

    @Test
    void 이미_가입된_이메일로는_다시_가입할_수_없고_동시에_10건이어도_1개다() throws Exception {
        Account a = emailSignup(uniqueEmail("taken"));
        browser().perform(asJson(post("/api/auth/signup/email"), signupForm(a.email().toUpperCase(), "another" + a.memberId(), "다른닉" + a.memberId() % 1000, PASSWORD)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));

        String email = uniqueEmail("race");
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            int n = i;
            results.add(pool.submit(() -> {
                start.await();
                return browser().perform(asJson(post("/api/auth/signup/email"),
                        signupForm(email, "race" + System.nanoTime() % 100000 + "x" + n, "경주" + n + (System.nanoTime() % 1000), PASSWORD)))
                        .andReturn().getResponse().getStatus();
            }));
        }
        start.countDown();
        int created = 0;
        for (Future<Integer> f : results) if (f.get() == 201) created++;
        pool.shutdown();
        assertThat(created).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_identity WHERE provider = 'LOCAL' AND email = ?", Integer.class, email)).isEqualTo(1);
    }

    @Test
    void 비밀번호_규칙에_맞지_않으면_가입할_수_없다() throws Exception {
        String email = uniqueEmail("pwrule");
        String local = email.substring(0, email.indexOf('@'));
        Map<String, String> cases = Map.of(
                "Ab1!efg", "PASSWORD_LENGTH",
                "Abcdefgh1!abcdefg", "PASSWORD_LENGTH",
                "Abcdefgh1", "PASSWORD_SPECIAL",
                "abcdefg!!", "PASSWORD_DIGIT",
                "12345678!", "PASSWORD_LETTER",
                "Abc 1234!", "PASSWORD_CHARS",
                "Abc한글123!", "PASSWORD_CHARS",
                "X" + local + "!", "PASSWORD_CONTAINS_EMAIL",
                "Password1!", "PASSWORD_TOO_COMMON");
        for (var c : cases.entrySet()) {
            MvcResult r = browser().perform(asJson(post("/api/auth/signup/email"), signupForm(email, "pwrule" + System.nanoTime() % 100000, "규칙닉", c.getKey())))
                    .andExpect(status().isBadRequest()).andReturn();
            assertThat(read(r).path("errors").findValuesAsString("code")).as(c.getKey()).contains(c.getValue());
        }
        MvcResult r = browser().perform(asJson(post("/api/auth/signup/email"), signupForm(email, "pwrule1", "규칙닉", "Ab1!efg"))).andReturn();
        assertThat(read(r).toString()).contains("최대 16자");
        Map<String, Object> mismatch = signupForm(email, "go_kim", "규칙닉", PASSWORD);
        mismatch.put("passwordConfirm", PASSWORD + "x");
        r = browser().perform(asJson(post("/api/auth/signup/email"), mismatch)).andExpect(status().isBadRequest()).andReturn();
        assertThat(read(r).path("errors").findValuesAsString("code")).contains("PASSWORD_MISMATCH", "HANDLE_PREFIX_LOOKALIKE");
        Map<String, Object> badEmail = signupForm("a".repeat(250) + "@x.com", "longmail", "규칙닉", PASSWORD);
        r = browser().perform(asJson(post("/api/auth/signup/email"), badEmail)).andExpect(status().isBadRequest()).andReturn();
        assertThat(read(r).path("errors").findValuesAsString("code")).contains("EMAIL_INVALID");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_identity WHERE email = ?", Integer.class, email)).isZero();
    }

    // ---------- US2 로그인 ----------

    @Test
    void 이메일은_대소문자_공백과_상관없이_로그인되고_틀린_이유는_구별되지_않는다() throws Exception {
        Account a = verified("login");
        Browser b = browser();
        MvcResult ok = login(b, "  " + a.email().toUpperCase() + " ", PASSWORD);
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        b.perform(get("/api/auth/me")).andExpect(jsonPath("$.member.id").value(a.memberId()));

        MvcResult wrong = login(browser(), a.email(), PASSWORD + "x");
        MvcResult unknown = login(browser(), uniqueEmail("nobody"), PASSWORD);
        assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(401);
        assertThat(wrong.getResponse().getContentAsString()).isEqualTo(unknown.getResponse().getContentAsString());
        assertThat(read(wrong).path("message").asString()).isEqualTo("이메일 또는 비밀번호가 올바르지 않아요.");
    }

    @Test
    void 같은_계정으로_5번_틀리면_15분_동안_맞는_비밀번호로도_로그인되지_않는다() throws Exception {
        Account a = verified("lock");
        for (int i = 0; i < 5; i++) assertThat(login(browser(), a.email(), "Wrong!pass1").getResponse().getStatus()).isEqualTo(401);
        MvcResult locked = login(browser(), a.email(), PASSWORD);
        assertThat(locked.getResponse().getStatus()).isEqualTo(429);
        assertThat(read(locked).path("message").asString()).isEqualTo("잠시 후 다시 시도해 주세요(약 15분).");
        // 가입되지 않은 이메일도 똑같이 잠긴다 (가입 여부를 알 수 없다)
        String ghost = uniqueEmail("ghost");
        for (int i = 0; i < 5; i++) login(browser(), ghost, "Wrong!pass1");
        assertThat(login(browser(), ghost, PASSWORD).getResponse().getStatus()).isEqualTo(429);
        // 15분이 지나면 (잠금 기록 만료)
        lockReset();
        assertThat(login(browser(), a.email(), PASSWORD).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void 정지된_이메일_계정은_로그인되지_않고_기한과_사유가_안내된다() throws Exception {
        Account a = verified("susp");
        Account admin = verified("suspadm");
        Instant until = Instant.now().plus(3, ChronoUnit.DAYS);
        jdbc.update("UPDATE member SET status = 'SUSPENDED' WHERE id = ?", a.memberId());
        jdbc.update("INSERT INTO member_suspension(member_id, reason, ends_at, suspended_by) VALUES (?, ?, ?, ?)",
                a.memberId(), "도배", Timestamp.from(until), admin.memberId());
        Browser b = browser();
        MvcResult r = login(b, a.email(), PASSWORD);
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        JsonNode body = read(r);
        assertThat(body.path("code").asString()).isEqualTo("ACCOUNT_SUSPENDED");
        assertThat(body.path("details").path("reason").asString()).isEqualTo("도배");
        assertThat(body.path("details").path("suspendedUntil").isMissingNode()).isFalse();
        b.perform(get("/api/auth/me")).andExpect(jsonPath("$.authenticated").value(false));
    }

    // ---------- US3 재설정 ----------

    @Test
    void 비밀번호_찾기는_가입_여부와_상관없이_같은_안내이고_재설정하면_모든_기기에서_로그아웃된다() throws Exception {
        Account a = verified("reset");
        Browser other = browser();
        login(other, a.email(), PASSWORD);
        other.perform(get("/api/auth/me")).andExpect(jsonPath("$.authenticated").value(true));

        MvcResult known = browser().perform(asJson(post("/api/auth/password/reset-request"), Map.of("email", a.email().toUpperCase()))).andReturn();
        MvcResult unknown = browser().perform(asJson(post("/api/auth/password/reset-request"), Map.of("email", uniqueEmail("nouser")))).andReturn();
        assertThat(known.getResponse().getStatus()).isEqualTo(202);
        assertThat(known.getResponse().getContentAsString()).isEqualTo(unknown.getResponse().getContentAsString());
        assertThat(read(known).path("message").asString()).isEqualTo("가입된 이메일이면 안내 메일을 보냈어요.");
        Mail mail = mailsTo(a.email()).getFirst();
        assertThat(mail.body()).contains("/reset-password?token=").contains("30분");
        String token = lastToken(a.email());
        assertThat(redis.getExpire("auth:reset:member:" + a.memberId())).isBetween(29 * 60L, 30 * 60L);

        // 규칙 오류는 링크를 쓰지 않는다
        browser().perform(asJson(post("/api/auth/password/reset"), Map.of("token", token, "password", "short", "passwordConfirm", "short")))
                .andExpect(status().isBadRequest());
        browser().perform(asJson(post("/api/auth/password/reset-check"), Map.of("token", token))).andExpect(jsonPath("$.valid").value(true));
        browser().perform(asJson(post("/api/auth/password/reset"), Map.of("token", token, "password", "Fresh!pass9", "passwordConfirm", "Fresh!pass9")))
                .andExpect(status().isNoContent());
        // 모든 기기 로그아웃
        a.http().perform(get("/api/auth/me")).andExpect(jsonPath("$.authenticated").value(false));
        other.perform(get("/api/auth/me")).andExpect(jsonPath("$.authenticated").value(false));
        // 링크는 한 번만
        browser().perform(asJson(post("/api/auth/password/reset"), Map.of("token", token, "password", "Again!pass9", "passwordConfirm", "Again!pass9")))
                .andExpect(status().isGone());
        assertThat(login(browser(), a.email(), PASSWORD).getResponse().getStatus()).isEqualTo(401);
        assertThat(login(browser(), a.email(), "Fresh!pass9").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void 같은_이메일의_소셜_계정은_메일에_안내되고_소셜만_있으면_비밀번호가_없다는_메일만_간다() throws Exception {
        Account a = verified("mix");
        googleSignup(a.email(), "mixgo" + a.memberId());
        browser().perform(asJson(post("/api/auth/password/reset-request"), Map.of("email", a.email()))).andExpect(status().isAccepted());
        assertThat(mailsTo(a.email()).getFirst().body()).contains("reset-password?token=").contains("Google로 가입한 계정도 있어요");

        String socialOnly = uniqueEmail("onlygo");
        googleSignup(socialOnly, "onlygo" + System.nanoTime() % 100000);
        browser().perform(asJson(post("/api/auth/password/reset-request"), Map.of("email", socialOnly))).andExpect(status().isAccepted());
        Mail m = mailsTo(socialOnly).getFirst();
        assertThat(m.body()).contains("Google로 가입되어 비밀번호가 없어요").doesNotContain("token=");
    }

    @Test
    void 같은_이메일로_1분_안에_다시_요청하면_메일이_다시_가지_않는다() throws Exception {
        Account a = verified("twice");
        int before = mailsTo(a.email()).size();
        browser().perform(asJson(post("/api/auth/password/reset-request"), Map.of("email", a.email()))).andExpect(status().isAccepted());
        browser().perform(asJson(post("/api/auth/password/reset-request"), Map.of("email", a.email()))).andExpect(status().isAccepted());
        assertThat(mailsTo(a.email())).hasSize(before + 1);
        // 같은 IP 1시간 20번
        Browser b = browser().from("198.51.100.77");
        for (int i = 0; i < 20; i++) {
            b.perform(asJson(post("/api/auth/password/reset-request"), Map.of("email", uniqueEmail("ip")))).andExpect(status().isAccepted());
        }
        b.perform(asJson(post("/api/auth/password/reset-request"), Map.of("email", uniqueEmail("ip")))).andExpect(status().isTooManyRequests());
    }

    // ---------- US4 Google·이메일 없는 GitHub ----------

    private Browser googleSignup(String email, String body) throws Exception {
        String sub = "g" + System.nanoTime();
        Browser b = browser();
        b.perform(asJson(post("/api/dev/login"), Map.of("provider", "GOOGLE", "providerUserId", sub, "login", "", "name", "구글", "email", email)))
                .andExpect(jsonPath("$.redirect").value("/signup/social"));
        b.perform(asJson(post("/api/auth/signup"), Map.of("handleBody", body, "nickname", "구" + System.nanoTime() % 100000000,
                "agreeTerms", true, "agreePrivacy", true))).andExpect(status().isCreated());
        return b;
    }

    @Test
    void Google_첫_로그인은_go_주소로_가입하고_같은_이메일의_이메일_계정과_별도이며_이메일이_바뀌어도_같은_계정이다() throws Exception {
        Account local = verified("kimgo");
        String localPart = local.email().substring(0, local.email().indexOf('@'));
        String sub = "g" + System.nanoTime();
        Browser b = browser();
        b.perform(asJson(post("/api/dev/login"), Map.of("provider", "GOOGLE", "providerUserId", sub, "login", "", "name", "김", "email", local.email())))
                .andExpect(jsonPath("$.redirect").value("/signup/social"));
        b.perform(get("/api/auth/signup"))
                .andExpect(jsonPath("$.prefix").value("go-"))
                .andExpect(jsonPath("$.handleBody").value(localPart))
                .andExpect(jsonPath("$.emailRequired").value(false));
        b.perform(asJson(post("/api/auth/signup"), Map.of("handleBody", localPart, "nickname", "구글" + System.nanoTime() % 100000,
                "agreeTerms", true, "agreePrivacy", true))).andExpect(status().isCreated());
        JsonNode me = read(b.perform(get("/api/auth/me")).andReturn());
        assertThat(me.path("member").path("handle").asString()).isEqualTo("go-" + localPart);
        assertThat(me.path("emailVerified").asBoolean()).isTrue();
        assertThat(me.path("member").path("id").asLong()).isNotEqualTo(local.memberId());

        Browser again = browser();
        again.perform(asJson(post("/api/dev/login"), Map.of("provider", "GOOGLE", "providerUserId", sub, "login", "", "name", "김", "email", "changed" + sub + "@gmail.com")))
                .andExpect(jsonPath("$.redirect").value("/"));
        again.perform(get("/api/auth/me")).andExpect(jsonPath("$.member.handle").value("go-" + localPart));
    }

    @Test
    void 카카오는_ka_주소로_가입하고_이메일_동의가_없으면_가입_때_이메일을_받는다() throws Exception {
        String kid = String.valueOf(4_000_000_000L + System.nanoTime() % 100_000_000L);
        String body = "kakao" + kid.substring(kid.length() - 8);
        Browser b = browser();
        b.perform(asJson(post("/api/dev/login"), Map.of("provider", "KAKAO", "providerUserId", kid, "login", "", "name", "카카오")))
                .andExpect(jsonPath("$.redirect").value("/signup/social"));
        b.perform(get("/api/auth/signup"))
                .andExpect(jsonPath("$.provider").value("KAKAO"))
                .andExpect(jsonPath("$.prefix").value("ka-"))
                .andExpect(jsonPath("$.emailRequired").value(true));
        b.perform(asJson(post("/api/auth/signup"), Map.of("handleBody", body, "nickname", "카카오" + kid.substring(kid.length() - 5),
                "agreeTerms", true, "agreePrivacy", true, "email", uniqueEmail("kakao")))).andExpect(status().isCreated());
        b.perform(get("/api/auth/me")).andExpect(jsonPath("$.member.handle").value("ka-" + body));

        Browser again = browser();
        again.perform(asJson(post("/api/dev/login"), Map.of("provider", "KAKAO", "providerUserId", kid, "login", "", "name", "카카오")))
                .andExpect(jsonPath("$.redirect").value("/"));
        again.perform(get("/api/auth/me")).andExpect(jsonPath("$.member.handle").value("ka-" + body));
    }

    @Test
    void 페이스북은_fb_주소로_가입하고_이메일이_오면_다시_묻지_않는다() throws Exception {
        String fid = String.valueOf(10_200_000_000_000L + System.nanoTime() % 100_000_000L);
        String localPart = "fb" + fid.substring(fid.length() - 8);
        Browser b = browser();
        b.perform(asJson(post("/api/dev/login"), Map.of("provider", "FACEBOOK", "providerUserId", fid, "login", "", "name", "Kim Minseo",
                        "email", localPart + "@example.com")))
                .andExpect(jsonPath("$.redirect").value("/signup/social"));
        b.perform(get("/api/auth/signup"))
                .andExpect(jsonPath("$.provider").value("FACEBOOK"))
                .andExpect(jsonPath("$.prefix").value("fb-"))
                .andExpect(jsonPath("$.handleBody").value(localPart))
                .andExpect(jsonPath("$.emailRequired").value(false));
        b.perform(asJson(post("/api/auth/signup"), Map.of("handleBody", localPart, "nickname", "페북" + fid.substring(fid.length() - 5),
                "agreeTerms", true, "agreePrivacy", true))).andExpect(status().isCreated());
        b.perform(get("/api/auth/me")).andExpect(jsonPath("$.member.handle").value("fb-" + localPart));

        Browser again = browser();
        again.perform(asJson(post("/api/dev/login"), Map.of("provider", "FACEBOOK", "providerUserId", fid, "login", "", "name", "Kim Minseo")))
                .andExpect(jsonPath("$.redirect").value("/"));
        again.perform(get("/api/auth/me")).andExpect(jsonPath("$.member.handle").value("fb-" + localPart));
    }

    @Test
    void 네이버는_nv_주소로_가입하고_이메일이_오면_다시_묻지_않는다() throws Exception {
        String nid = "n" + (10_000_000L + System.nanoTime() % 90_000_000L);
        String localPart = "nv" + nid.substring(1);
        Browser b = browser();
        b.perform(asJson(post("/api/dev/login"), Map.of("provider", "NAVER", "providerUserId", nid, "login", "", "name", "민서",
                        "email", localPart + "@naver.com")))
                .andExpect(jsonPath("$.redirect").value("/signup/social"));
        b.perform(get("/api/auth/signup"))
                .andExpect(jsonPath("$.provider").value("NAVER"))
                .andExpect(jsonPath("$.prefix").value("nv-"))
                .andExpect(jsonPath("$.handleBody").value(localPart))
                .andExpect(jsonPath("$.emailRequired").value(false));
        b.perform(asJson(post("/api/auth/signup"), Map.of("handleBody", localPart, "nickname", "네이버" + nid.substring(nid.length() - 5),
                "agreeTerms", true, "agreePrivacy", true))).andExpect(status().isCreated());
        b.perform(get("/api/auth/me")).andExpect(jsonPath("$.member.handle").value("nv-" + localPart));

        Browser again = browser();
        again.perform(asJson(post("/api/dev/login"), Map.of("provider", "NAVER", "providerUserId", nid, "login", "", "name", "민서")))
                .andExpect(jsonPath("$.redirect").value("/"));
        again.perform(get("/api/auth/me")).andExpect(jsonPath("$.member.handle").value("nv-" + localPart));
    }

    @Test
    void 인증된_이메일이_없는_GitHub_계정은_가입_때_이메일을_받아_메일로_인증한다() throws Exception {
        String gid = String.valueOf(800_000_000L + System.nanoTime() % 100_000_000L);
        Browser b = browser();
        b.perform(asJson(post("/api/dev/login"), Map.of("providerUserId", gid, "login", "noemail" + gid, "name", "x")))
                .andExpect(jsonPath("$.redirect").value("/signup/social"));
        b.perform(get("/api/auth/signup")).andExpect(jsonPath("$.emailRequired").value(true));
        Map<String, Object> form = new HashMap<>(Map.of("handleBody", "noemail" + gid, "nickname", "무메일" + gid.substring(gid.length() - 4),
                "agreeTerms", true, "agreePrivacy", true));
        b.perform(asJson(post("/api/auth/signup"), form))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("email"));
        String email = uniqueEmail("ghmail");
        form.put("email", email);
        b.perform(asJson(post("/api/auth/signup"), form)).andExpect(status().isCreated());
        b.perform(get("/api/auth/me")).andExpect(jsonPath("$.emailVerified").value(false));
        b.perform(asJson(post("/api/posts"), Map.of())).andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
        b.perform(asJson(post("/api/auth/email/verify"), Map.of("token", lastToken(email)))).andExpect(status().isOk());
        b.perform(asJson(post("/api/posts"), Map.of())).andExpect(status().isCreated());
    }

    // ---------- US5 비밀번호 변경 ----------

    @Test
    void 비밀번호를_바꾸면_지금_기기는_유지되고_다른_기기는_로그아웃되며_알림_메일이_간다() throws Exception {
        Account a = verified("chg");
        Browser other = browser();
        login(other, a.email(), PASSWORD);

        a.http().perform(asJson(put("/api/me/password"), Map.of("currentPassword", PASSWORD, "password", PASSWORD, "passwordConfirm", PASSWORD)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("PASSWORD_SAME"));
        a.http().perform(asJson(put("/api/me/password"), Map.of("currentPassword", PASSWORD, "password", "Next!pass42", "passwordConfirm", "Next!pass42")))
                .andExpect(status().isNoContent());
        a.http().perform(get("/api/auth/me")).andExpect(jsonPath("$.authenticated").value(true));
        other.perform(get("/api/auth/me")).andExpect(jsonPath("$.authenticated").value(false));
        assertThat(mailsTo(a.email()).getFirst().subject()).contains("비밀번호가 변경됐어요");
        assertThat(mailsTo(a.email()).getFirst().body()).contains("/forgot-password");
        assertThat(login(browser(), a.email(), "Next!pass42").getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void 현재_비밀번호를_5번_틀리면_15분_동안_바꿀_수_없고_소셜_계정은_바꿀_수_없다() throws Exception {
        Account a = verified("chgwrong");
        for (int i = 0; i < 5; i++) {
            a.http().perform(asJson(put("/api/me/password"), Map.of("currentPassword", "Wrong!pass1", "password", "Next!pass42", "passwordConfirm", "Next!pass42")))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("PASSWORD_WRONG"));
        }
        a.http().perform(asJson(put("/api/me/password"), Map.of("currentPassword", PASSWORD, "password", "Next!pass42", "passwordConfirm", "Next!pass42")))
                .andExpect(status().isTooManyRequests());

        Session github = signup(uniqueLogin("ghpw"));
        github.http().perform(asJson(put("/api/me/password"), Map.of("currentPassword", "x", "password", "Next!pass42", "passwordConfirm", "Next!pass42")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PASSWORD_NOT_SUPPORTED"));
    }

    @Test
    void Google_앱_키가_없으면_Google_로그인을_보여주지_않는다() throws Exception {
        mvc.perform(get("/api/auth/providers"))
                .andExpect(jsonPath("$.email").value(true))
                .andExpect(jsonPath("$.social[0]").value("github"))
                .andExpect(jsonPath("$.social.length()").value(1));
    }

    @Test
    void 개발_환경은_메일을_보내지_않고_보관함에서_볼_수_있다() throws Exception {
        Account a = emailSignup(uniqueEmail("devmail"));
        MvcResult r = mvc.perform(get("/api/dev/mails")).andExpect(status().isOk()).andReturn();
        assertThat(r.getResponse().getContentAsString()).contains(a.email());
    }
}
