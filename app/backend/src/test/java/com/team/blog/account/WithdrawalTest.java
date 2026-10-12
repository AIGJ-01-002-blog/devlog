package com.team.blog.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.JsonNode;

import com.team.blog.account.application.WithdrawalPurgeJob;
import com.team.blog.shared.mail.Mail;
import com.team.blog.shared.mail.Mailer;
import com.team.blog.shared.mail.OutboxMailer;
import com.team.blog.support.Browser;
import com.team.blog.support.FailingPurgeStep;
import com.team.blog.support.IntegrationTest;

/** 회원 탈퇴·복구·30일 뒤 정리 (020). */
class WithdrawalTest extends IntegrationTest {
    private static final String PASSWORD = "Blog!pass77";

    @Autowired WithdrawalPurgeJob purgeJob;
    @Autowired Mailer mailer;

    @AfterEach
    void noFailure() {
        FailingPurgeStep.failFor = -1;
    }

    private long publish(Session s, String title) throws Exception {
        long id = read(s.http().perform(asJson(post("/api/posts"), Map.of("title", title, "contentMd", "본문 " + title)))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        s.http().perform(asJson(post("/api/posts/" + id + "/publish"), Map.of("title", title, "contentMd", "본문 " + title,
                        "visibility", "PUBLIC", "baseVersion", 0, "tags", List.of()))
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isOk());
        return id;
    }

    private long comment(Session s, long postId, String content) throws Exception {
        return read(s.http().perform(asJson(post("/api/posts/" + postId + "/comments"), Map.of("content", content)))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
    }

    private long reply(Session s, long postId, long rootId, String content) throws Exception {
        return read(s.http().perform(asJson(post("/api/posts/" + postId + "/comments"),
                        Map.of("content", content, "replyToCommentId", rootId)))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
    }

    private void like(Session s, long postId) throws Exception {
        s.http().perform(put("/api/posts/" + postId + "/like").with(csrf())).andExpect(status().is2xxSuccessful());
    }

    private ResultActions withdraw(Browser b, Boolean confirmed, String password, String confirmText) throws Exception {
        Map<String, Object> body = new HashMap<>();
        if (confirmed != null) body.put("confirmed", confirmed);
        if (password != null) body.put("password", password);
        if (confirmText != null) body.put("confirmText", confirmText);
        return b.perform(asJson(post("/api/me/withdrawal"), body));
    }

    private String code(ResultActions r, int status) throws Exception {
        return read(r.andExpect(status().is(status)).andReturn()).path("code").asString();
    }

    private List<String> mailSubjectsTo(String email) {
        return ((OutboxMailer) mailer).recent().stream().filter(m -> m.to().equals(email)).map(Mail::subject).toList();
    }

    private void overdue(long memberId) {
        jdbc.update("UPDATE member SET withdrawn_at = now() - interval '31 days' WHERE id = ?", memberId);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    void 소셜_회원은_탈퇴를_입력해_탈퇴하고_모든_기기에서_로그아웃되며_복구하면_원래대로다() throws Exception {
        Session a = signup(uniqueLogin("wda"));
        Session b = signup(uniqueLogin("wdb"));
        long mine = publish(a, "탈퇴할 글");
        publish(a, "두 번째");
        long theirs = publish(b, "남의 글");
        comment(a, theirs, "남의 글에 쓴 댓글");
        like(b, mine);
        Browser other = relogin(a);
        String email = jdbc.queryForObject("SELECT email FROM auth_identity WHERE member_id = ?", String.class, a.memberId());

        JsonNode summary = read(a.http().perform(get("/api/me/withdrawal")).andExpect(status().isOk()).andReturn());
        assertThat(summary.path("posts").asLong()).isEqualTo(2);
        assertThat(summary.path("comments").asLong()).isEqualTo(1);
        assertThat(summary.path("likesReceived").asLong()).isEqualTo(1);
        assertThat(summary.path("method").asString()).isEqualTo("CONFIRM_TEXT");
        assertThat(summary.path("withdrawn").asBoolean()).isFalse();

        assertThat(code(withdraw(a.http(), false, null, "탈퇴"), 400)).isEqualTo("VALIDATION_FAILED");
        assertThat(code(withdraw(a.http(), true, null, "탈퇴할래요"), 400)).isEqualTo("VALIDATION_FAILED");
        JsonNode done = read(withdraw(a.http(), true, null, "탈퇴").andExpect(status().isOk()).andReturn());
        assertThat(done.path("restoreBy").asString()).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT status FROM member WHERE id = ?", String.class, a.memberId())).isEqualTo("WITHDRAWN");

        // 모든 기기에서 로그아웃 (FR-009)
        assertThat(read(a.http().perform(get("/api/auth/me")).andReturn()).path("authenticated").asBoolean()).isFalse();
        assertThat(read(other.perform(get("/api/auth/me")).andReturn()).path("authenticated").asBoolean()).isFalse();
        assertThat(mailSubjectsTo(email)).anyMatch(s -> s.contains("탈퇴 신청이 접수됐어요"));

        // 블로그·글은 바로 찾을 수 없고 댓글은 가려진다 (FR-010·FR-011)
        b.http().perform(get("/api/posts/" + mine)).andExpect(status().isNotFound());
        b.http().perform(get("/api/members/" + a.handle())).andExpect(status().isNotFound());
        JsonNode comments = read(b.http().perform(get("/api/posts/" + theirs + "/comments")).andExpect(status().isOk()).andReturn());
        assertThat(comments.toString()).doesNotContain("남의 글에 쓴 댓글");

        // 유예 중 로그인: 복구 화면만 (FR-016)
        Browser back = relogin(a);
        JsonNode me = read(back.perform(get("/api/auth/me")).andReturn());
        assertThat(me.path("member").path("status").asString()).isEqualTo("WITHDRAWN");
        assertThat(code(back.perform(asJson(post("/api/posts"), Map.of("title", "x", "contentMd", "y"))), 403)).isEqualTo("ACCOUNT_WITHDRAWN");
        assertThat(read(back.perform(get("/api/me/withdrawal")).andExpect(status().isOk()).andReturn()).path("withdrawn").asBoolean()).isTrue();

        back.perform(post("/api/me/restore").with(csrf())).andExpect(status().isNoContent());
        assertThat(jdbc.queryForMap("SELECT status, withdrawn_at FROM member WHERE id = ?", a.memberId()))
                .containsEntry("status", "ACTIVE").containsEntry("withdrawn_at", null);
        b.http().perform(get("/api/posts/" + mine)).andExpect(status().isOk());
        assertThat(read(b.http().perform(get("/api/posts/" + theirs + "/comments")).andReturn()).toString()).contains("남의 글에 쓴 댓글");
        assertThat(count("SELECT count(*) FROM post_like WHERE post_id = ?", mine)).isEqualTo(1);
        assertThat(mailSubjectsTo(email)).anyMatch(s -> s.contains("계정이 복구됐어요"));
        assertThat(code(back.perform(post("/api/me/restore").with(csrf())), 409)).isEqualTo("NOT_WITHDRAWN");
    }

    @Test
    void 관리자는_탈퇴할_수_없고_비회원은_로그인이_필요하다() throws Exception {
        Session a = signup(uniqueLogin("wdadm"));
        jdbc.update("UPDATE member SET role = 'ADMIN' WHERE id = ?", a.memberId());
        Browser admin = relogin(a);
        assertThat(code(withdraw(admin, true, null, "탈퇴"), 409)).isEqualTo("ADMIN_CANNOT_WITHDRAW");
        assertThat(jdbc.queryForObject("SELECT status FROM member WHERE id = ?", String.class, a.memberId())).isEqualTo("ACTIVE");
        withdraw(browser(), true, null, "탈퇴").andExpect(status().isUnauthorized());
    }

    @Test
    void 이메일_회원은_비밀번호를_확인하고_5번_틀리면_잠기며_유예_중_같은_이메일_가입은_복구를_안내한다() throws Exception {
        String email = uniqueLogin("wdmail") + "@example.com";
        String body = email.substring(0, email.indexOf('@')).replaceAll("[^a-z0-9]", "");
        Browser b = browser();
        b.perform(asJson(post("/api/auth/signup/email"), Map.of("email", email, "handleBody", body, "password", PASSWORD,
                "passwordConfirm", PASSWORD, "nickname", "메일" + body.substring(body.length() - 6), "agreeTerms", true,
                "agreePrivacy", true))).andExpect(status().isCreated());
        assertThat(read(b.perform(get("/api/me/withdrawal")).andReturn()).path("method").asString()).isEqualTo("PASSWORD");

        for (int i = 0; i < 5; i++) {
            assertThat(code(withdraw(b, true, "wrong-" + i, null), 400)).isEqualTo("VALIDATION_FAILED");
        }
        assertThat(code(withdraw(b, true, PASSWORD, null), 429)).isNotBlank();
        redis.delete(redis.keys("auth:fail:*"));
        withdraw(b, true, PASSWORD, null).andExpect(status().isOk());

        Map<String, Object> again = new HashMap<>(Map.of("email", email, "handleBody", body + "x", "password", PASSWORD,
                "passwordConfirm", PASSWORD, "nickname", "다른" + body.substring(body.length() - 6), "agreeTerms", true, "agreePrivacy", true));
        assertThat(code(browser().perform(asJson(post("/api/auth/signup/email"), again)), 409)).isEqualTo("WITHDRAWN_ACCOUNT");
        // 유예 중에도 로그인은 된다 (복구 화면으로)
        Browser c = browser();
        c.perform(asJson(post("/api/auth/login"), Map.of("email", email, "password", PASSWORD))).andExpect(status().isOk());
        assertThat(read(c.perform(get("/api/auth/me")).andReturn()).path("member").path("status").asString()).isEqualTo("WITHDRAWN");
    }

    @Test
    void 관리자는_탈퇴_회원을_유예_전에_바로_정리하고_같은_소셜_계정은_새로_가입한다() throws Exception {
        Session a = signup(uniqueLogin("wpnow"));
        Session admin = signup(uniqueLogin("wpadm"));
        jdbc.update("UPDATE member SET role = 'ADMIN' WHERE id = ?", admin.memberId());
        Browser adminHttp = relogin(admin);
        Session manager = signup(uniqueLogin("wpmgr"));
        jdbc.update("UPDATE member SET role = 'MANAGER' WHERE id = ?", manager.memberId());
        Browser managerHttp = relogin(manager);
        String purge = "/api/admin/members/" + a.handle() + "/purge";

        // 탈퇴 전에는 정리할 수 없다
        assertThat(code(adminHttp.perform(post(purge).with(csrf())), 409)).isEqualTo("NOT_WITHDRAWN");
        withdraw(a.http(), true, null, "탈퇴").andExpect(status().isOk());

        // 매니저는 403, 일반 회원은 관리자 API를 볼 수 없다
        assertThat(code(managerHttp.perform(post(purge).with(csrf())), 403)).isEqualTo("ADMIN_ONLY");
        Session other = signup(uniqueLogin("wpusr"));
        other.http().perform(post(purge).with(csrf())).andExpect(status().isNotFound());
        assertThat(count("SELECT count(*) FROM auth_identity WHERE member_id = ?", a.memberId())).isEqualTo(1);

        adminHttp.perform(post(purge).with(csrf())).andExpect(status().isNoContent());
        assertThat(count("SELECT count(*) FROM auth_identity WHERE member_id = ?", a.memberId())).isZero();
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM member WHERE id = ?", Boolean.class, a.memberId())).isTrue();
        assertThat(code(adminHttp.perform(post(purge).with(csrf())), 409)).as("이미 정리한 회원").isEqualTo("NOT_WITHDRAWN");

        Browser again = githubAuthenticated(a.githubId(), "again" + a.memberId(), "again", "again" + a.memberId() + "@example.com");
        assertThat(read(again.perform(get("/api/auth/me")).andReturn()).path("pendingSignup").asBoolean()).isTrue();
    }

    @Test
    void 삼십일이_지나면_단계_순서대로_정리하고_익명_처리한다() throws Exception {
        Session a = signup(uniqueLogin("wpa"));
        Session b = signup(uniqueLogin("wpb"));
        Session c = signup(uniqueLogin("wpc"));
        long mine = publish(a, "지워질 글");
        long trashed = publish(a, "휴지통 글");
        jdbc.update("UPDATE post SET deleted_at = now() WHERE id = ?", trashed);
        long theirs = publish(b, "남은 글");
        comment(b, mine, "내 글에 달린 남의 댓글");
        like(b, mine);
        long rootWithReply = comment(a, theirs, "답글 달린 내 댓글");
        reply(b, theirs, rootWithReply, "B의 답글");
        long lonely = comment(a, theirs, "혼자인 내 댓글");
        long bRoot = comment(b, theirs, "B의 댓글");
        reply(a, theirs, bRoot, "내 답글");
        like(a, theirs);
        like(c, theirs);
        jdbc.update("INSERT INTO follow (follower_id, followee_id, created_at) VALUES (?, ?, now()), (?, ?, now())",
                a.memberId(), b.memberId(), b.memberId(), a.memberId());
        long lo = Math.min(a.memberId(), c.memberId()), hi = Math.max(a.memberId(), c.memberId());
        jdbc.update("INSERT INTO friendship (member_a_id, member_b_id, requested_by, status, created_at, accepted_at) VALUES (?, ?, ?, 'ACCEPTED', now(), now())",
                lo, hi, a.memberId());
        // B가 A의 다른 글을 신고 → 대기 사건, A가 B의 글을 기타 설명과 함께 신고
        long reported = publish(a, "신고될 글");
        b.http().perform(asJson(post("/api/reports"), Map.of("targetType", "POST", "targetId", reported, "reason", "SPAM")))
                .andExpect(status().isNoContent());
        a.http().perform(asJson(post("/api/reports"), Map.of("targetType", "POST", "targetId", theirs, "reason", "OTHER",
                "detail", "퍼 온 글 같아요"))).andExpect(status().isNoContent());
        drain();
        long caseId = jdbc.queryForObject("SELECT id FROM report_case WHERE post_id = ?", Long.class, reported);
        long likeBundle = jdbc.queryForObject("""
                SELECT n.id FROM notification n JOIN notification_post np ON np.notification_id = n.id
                WHERE n.receiver_id = ? AND n.type = 'LIKE' AND np.post_id = ?
                """, Long.class, b.memberId(), theirs);
        assertThat(count("SELECT count(*) FROM notification_actor WHERE notification_id = ?", likeBundle)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM notification WHERE receiver_id = ?", a.memberId())).isPositive();

        withdraw(a.http(), true, null, "탈퇴").andExpect(status().isOk());
        assertThat(purgeJob.run()).as("30일 전에는 정리하지 않는다").isZero();
        overdue(a.memberId());

        // 한 단계라도 실패하면 그 회원 전체가 되돌아간다 (FR-023)
        FailingPurgeStep.failFor = a.memberId();
        assertThat(purgeJob.run()).isZero();
        assertThat(count("SELECT count(*) FROM post WHERE author_id = ?", a.memberId())).isEqualTo(3);
        assertThat(count("SELECT count(*) FROM auth_identity WHERE member_id = ?", a.memberId())).isEqualTo(1);
        FailingPurgeStep.failFor = -1;

        assertThat(purgeJob.run()).isEqualTo(1);
        // (10) 내 글 전부와 거기 달린 남의 댓글·좋아요
        assertThat(count("SELECT count(*) FROM post WHERE author_id = ?", a.memberId())).isZero();
        assertThat(count("SELECT count(*) FROM comment WHERE post_id IN (?, ?)", mine, trashed)).isZero();
        // (20) 답글 달린 내 댓글은 자리만, 혼자인 댓글·내 답글은 삭제
        assertThat(jdbc.queryForMap("SELECT content, deleted_at IS NOT NULL AS gone FROM comment WHERE id = ?", rootWithReply))
                .containsEntry("content", "").containsEntry("gone", true);
        assertThat(count("SELECT count(*) FROM comment WHERE id = ?", lonely)).isZero();
        assertThat(count("SELECT count(*) FROM comment WHERE author_id = ? AND parent_id IS NOT NULL", a.memberId())).isZero();
        assertThat(count("SELECT count(*) FROM comment WHERE id = ?", bRoot)).isEqualTo(1);
        // (30) 내가 누른 좋아요, (60)(65) 관계
        assertThat(count("SELECT count(*) FROM post_like WHERE member_id = ?", a.memberId())).isZero();
        assertThat(count("SELECT count(*) FROM post_like WHERE post_id = ?", theirs)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM follow WHERE follower_id = ? OR followee_id = ?", a.memberId(), a.memberId())).isZero();
        assertThat(count("SELECT count(*) FROM friendship WHERE member_a_id = ? OR member_b_id = ?", a.memberId(), a.memberId())).isZero();
        // (50) 로그인 수단, (70) 받은 알림과 묶음에서 나 빼기
        assertThat(count("SELECT count(*) FROM auth_identity WHERE member_id = ?", a.memberId())).isZero();
        assertThat(count("SELECT count(*) FROM notification WHERE receiver_id = ?", a.memberId())).isZero();
        assertThat(count("SELECT count(*) FROM notification_actor WHERE actor_id = ?", a.memberId())).isZero();
        assertThat(count("SELECT count(*) FROM notification_actor WHERE notification_id = ?", likeBundle)).isEqualTo(1);
        // (80) 내 콘텐츠의 대기 신고는 "대상 없음", 내가 쓴 신고의 설명은 비움
        assertThat(jdbc.queryForMap("SELECT status, handled_by FROM report_case WHERE id = ?", caseId))
                .containsEntry("status", "CLOSED_NO_TARGET").containsEntry("handled_by", null);
        assertThat(count("SELECT count(*) FROM report WHERE reporter_id = ? AND detail IS NOT NULL", a.memberId())).isZero();
        assertThat(count("SELECT count(*) FROM report WHERE reporter_id = ?", a.memberId())).isEqualTo(1);
        // (90) 익명 처리: 주소는 남고 개인 정보는 비움, 동의 기록은 남김
        Map<String, Object> m = jdbc.queryForMap("SELECT handle, nickname, bio, last_active_at, deleted_at FROM member WHERE id = ?", a.memberId());
        assertThat(m).containsEntry("handle", a.handle());
        assertThat(m.get("nickname")).isNull();
        assertThat(m.get("bio")).isNull();
        assertThat(m.get("last_active_at")).isNull();
        assertThat(m.get("deleted_at")).isNotNull();
        assertThat(count("SELECT count(*) FROM member_agreement WHERE member_id = ?", a.memberId())).isPositive();
        // 남은 자리 댓글은 탈퇴한 사용자로 보인다 (FR-033)
        assertThat(read(b.http().perform(get("/api/posts/" + theirs + "/comments")).andReturn()).toString())
                .doesNotContain("답글 달린 내 댓글");
        // 같은 GitHub 계정으로 다시 오면 새 가입이고 옛 주소는 쓸 수 없다 (FR-034)
        Browser again = githubAuthenticated(a.githubId(), a.handle().substring(3), "again", "again" + a.memberId() + "@example.com");
        assertThat(read(again.perform(get("/api/auth/me")).andReturn()).path("pendingSignup").asBoolean()).isTrue();
        assertThat(code(again.perform(asJson(post("/api/auth/signup"), Map.of("handleBody", a.handle().substring(3),
                "nickname", "다시왔어요", "agreeTerms", true, "agreePrivacy", true))), 409)).isEqualTo("HANDLE_TAKEN");
        assertThat(purgeJob.run()).as("이미 익명 처리한 회원은 다시 하지 않는다").isZero();
    }
}
