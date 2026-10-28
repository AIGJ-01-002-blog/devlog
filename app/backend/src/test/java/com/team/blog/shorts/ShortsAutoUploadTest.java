package com.team.blog.shorts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;

import com.team.blog.media.storage.ObjectStorage;
import com.team.blog.shared.security.MemberPrincipal;
import com.team.blog.shorts.autoupload.AutoUploadJobs;
import com.team.blog.shorts.autoupload.AutoUploads;
import com.team.blog.support.Browser;
import com.team.blog.support.FakePlatforms;
import com.team.blog.support.FakePlatforms.Reply;
import com.team.blog.support.IntegrationTest;

/**
 * 쇼츠 자동 올리기 (spec 108). 가짜 Google·YouTube·Instagram 서버로 잇기 → 시험 → 켜기 → 렌더 뒤 올리기 → 공개를 끝까지 본다.
 * 진짜 계정에는 아무것도 보내지 않는다.
 */
class ShortsAutoUploadTest extends IntegrationTest {
    static final FakePlatforms FAKE = FakePlatforms.start();
    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @DynamicPropertySource
    static void platforms(DynamicPropertyRegistry r) {
        r.add("blog.shorts.auto-upload.youtube.auth-url", () -> FAKE.baseUrl() + "/o/oauth2/v2/auth");
        r.add("blog.shorts.auto-upload.youtube.token-url", () -> FAKE.baseUrl() + "/token");
        r.add("blog.shorts.auto-upload.youtube.api-url", FAKE::baseUrl);
        r.add("blog.shorts.auto-upload.instagram.auth-url", () -> FAKE.baseUrl() + "/oauth/authorize");
        r.add("blog.shorts.auto-upload.instagram.token-url", FAKE::baseUrl);
        r.add("blog.shorts.auto-upload.instagram.graph-url", FAKE::baseUrl);
    }

    @AfterAll
    static void stop() {
        FAKE.stop();
    }

    private static final List<Map<String, String>> SCENES = List.of(
            Map.of("caption", "배포하면 다 로그아웃?", "narration", "왜 배포만 하면 사용자가 전부 로그아웃될까요?"),
            Map.of("caption", "범인은 직렬화", "narration", "로그를 보니 세션을 저장한 클래스 모양이 바뀌어 예전 세션을 못 읽고 있었어요."),
            Map.of("caption", "결과", "narration", "JSON으로 바꾸니 재배포 뒤 다시 로그인하는 사람이 없어졌어요."));

    @Autowired AutoUploadJobs jobs;
    @Autowired AutoUploads uploads;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
    @Autowired ObjectStorage storage;

    @BeforeEach
    void clean() {
        FAKE.reset();
        jdbc.update("DELETE FROM shorts_auto_upload");
        jdbc.update("DELETE FROM shorts_platform_connection");
    }

    private Browser admin(Session s) throws Exception {
        jdbc.update("UPDATE member SET role = 'ADMIN' WHERE id = ?", s.memberId());
        return relogin(s);
    }

    /** 영상까지 다 만든 쇼츠. playlist를 주면 글 쇼츠로 만든다 */
    private long readyShorts(Browser b, String title, String playlist) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("title", title, "scenes", SCENES));
        if (playlist != null) {
            long postId = read(b.perform(asJson(post("/api/posts"), Map.of("title", "세션이 풀리는 문제", "contentMd", "배포 뒤 세션이 풀린 이야기")))
                    .andExpect(status().isCreated()).andReturn()).path("id").asLong();
            body.put("postId", postId);
        }
        long id = read(b.perform(asJson(post("/api/me/shorts"), body)).andExpect(status().isCreated()).andReturn()).path("id").asLong();
        String key = "shorts/2026/10/auto-" + id + ".mp4";
        storage.put(key, new byte[] {0, 0, 0, 0x18, 'f', 't', 'y', 'p'}, "video/mp4");
        jdbc.update("UPDATE shorts SET status = 'READY', video_key = ?, video_bytes = 8, duration_ms = 33000, rendered_at = ?, playlist = ? WHERE id = ?",
                key, Timestamp.from(Instant.now()), playlist, id);
        return id;
    }

    private static String param(String url, String name) {
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            if (pair.startsWith(name + "=")) return java.net.URLDecoder.decode(pair.substring(name.length() + 1), java.nio.charset.StandardCharsets.UTF_8);
        }
        return null;
    }

    private void connect(Browser b, String platform) throws Exception {
        String url = read(b.perform(post("/api/me/shorts/auto-upload/" + platform + "/connect").with(
                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())).andExpect(status().isOk()).andReturn())
                .path("url").asString();
        MvcResult r = b.perform(get("/api/me/shorts/auto-upload/" + platform + "/callback").param("code", "code-1").param("state", param(url, "state")))
                .andExpect(status().isFound()).andReturn();
        assertThat(r.getResponse().getHeader("Location")).isEqualTo("/studio?auto=1&platform=" + platform + "&result=connected");
    }

    private JsonNode platform(Browser b, String name) throws Exception {
        for (JsonNode p : read(b.perform(get("/api/me/shorts/auto-upload")).andExpect(status().isOk()).andReturn()).path("platforms")) {
            if (p.path("platform").asString().equals(name)) return p;
        }
        throw new AssertionError(name);
    }

    private void due() {
        jdbc.update("UPDATE shorts_auto_upload SET next_attempt_at = now() - interval '1 second'");
    }

    @Test
    void 관리자가_아니면_없는_주소다() throws Exception {
        Session member = signup(uniqueLogin("aunone"));
        member.http().perform(get("/api/me/shorts/auto-upload")).andExpect(status().isNotFound());
        member.http().perform(asJson(put("/api/me/shorts/auto-upload/youtube/enabled"), Map.of("enabled", true))).andExpect(status().isNotFound());
    }

    @Test
    void state가_맞지_않으면_잇지_않는다() throws Exception {
        Browser b = admin(signup(uniqueLogin("austate")));
        b.perform(post("/api/me/shorts/auto-upload/youtube/connect").with(
                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())).andExpect(status().isOk());
        MvcResult r = b.perform(get("/api/me/shorts/auto-upload/youtube/callback").param("code", "c").param("state", "forged"))
                .andExpect(status().isFound()).andReturn();
        assertThat(r.getResponse().getHeader("Location")).endsWith("result=state");
        assertThat(FAKE.calls("POST /token")).isEmpty();
        assertThat(platform(b, "YOUTUBE").path("connected").asBoolean()).isFalse();
    }

    @Test
    void 다른_회원이_시작한_state로는_잇지_않는다() throws Exception {
        Session starter = signup(uniqueLogin("austart"));
        Session other = signup(uniqueLogin("auother"));
        jdbc.update("UPDATE member SET role = 'ADMIN' WHERE id IN (?, ?)", starter.memberId(), other.memberId());
        MemberPrincipal me = new MemberPrincipal(other.memberId(), other.handle(), "ADMIN", "LOCAL", false, null);
        AutoUploads.PendingState pending = new AutoUploads.PendingState(starter.memberId(), "s-1", Instant.now().plusSeconds(60));
        assertThat(uploads.finishConnect(me, "youtube", "code-1", "s-1", null, pending)).isEqualTo(AutoUploads.ConnectResult.STATE);
        assertThat(FAKE.calls("POST /token")).isEmpty();
    }

    /** 잇고 시험을 통과해 켠다 */
    private void enable(Browser b, String platform) throws Exception {
        connect(b, platform);
        long sample = readyShorts(b, "시험 영상", null);
        b.perform(asJson(post("/api/me/shorts/auto-upload/" + platform + "/test"), Map.of("shortsId", sample))).andExpect(status().isOk());
        b.perform(asJson(put("/api/me/shorts/auto-upload/" + platform + "/enabled"), Map.of("enabled", true))).andExpect(status().isOk());
        FAKE.reset();
    }

    @Test
    void 관리자가_아니게_되면_예약_작업도_넣거나_올리지_않는다() throws Exception {
        Session me = signup(uniqueLogin("audemote"));
        Browser b = admin(me);
        enable(b, "youtube");
        long queued = readyShorts(b, "켜 둔 사이 만든 영상", null);
        assertThat(jobs.enqueue()).isEqualTo(1);
        jdbc.update("UPDATE member SET role = 'USER' WHERE id = ?", me.memberId());
        readyShorts(b, "그 뒤에 만든 영상", null);
        assertThat(jobs.enqueue()).isZero();
        due();
        jobs.runDue();
        assertThat(FAKE.calls("POST /upload/youtube/v3/videos")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM shorts_auto_upload WHERE shorts_id = ?", String.class, queued)).isEqualTo("QUEUED");
    }

    @Test
    void 올린_뒤_실패해_다시_해도_같은_영상을_또_올리지_않는다() throws Exception {
        Session me = signup(uniqueLogin("auonce"));
        Browser b = admin(me);
        enable(b, "youtube");
        long id = readyShorts(b, "한 번만 올릴 영상", null);
        jobs.enqueue();
        // 영상 ID를 적은 뒤 상태 적기 전에 실패해 QUEUED로 돌아온 작업
        jdbc.update("UPDATE shorts_auto_upload SET status = 'QUEUED', external_id = 'vidSaved', url = 'https://youtube.com/shorts/vidSaved' WHERE shorts_id = ?", id);
        due();
        jobs.runDue();
        assertThat(FAKE.calls("POST /upload/youtube/v3/videos")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM shorts_auto_upload WHERE shorts_id = ?", String.class, id)).isEqualTo("SCHEDULED");
    }

    @Test
    void 인스타는_한_시간_넘게_늦은_컨테이너를_게시하지_않고_다음_빈_날로_미룬다() throws Exception {
        Session me = signup(uniqueLogin("aulate"));
        Browser b = admin(me);
        enable(b, "instagram");
        long id = readyShorts(b, "늦은 릴스", null);
        jobs.enqueue();
        jdbc.update("UPDATE shorts_auto_upload SET publish_at = now() - interval '1 minute' WHERE shorts_id = ?", id);
        due();
        jobs.runDue();
        assertThat(FAKE.calls("POST /v24.0/" + FakePlatforms.IG_USER + "/media")).hasSize(1);
        jdbc.update("UPDATE shorts_auto_upload SET publish_at = now() - interval '2 hours' WHERE shorts_id = ?", id);
        due();
        jobs.runDue();
        assertThat(FAKE.calls("POST /v24.0/" + FakePlatforms.IG_USER + "/media_publish")).isEmpty();
        Map<String, Object> row = jdbc.queryForMap("SELECT status, container_id, publish_at > now() AS later FROM shorts_auto_upload WHERE shorts_id = ?", id);
        assertThat(row.get("status")).isEqualTo("QUEUED");
        assertThat(row.get("container_id")).isNull();
        assertThat(row.get("later")).isEqualTo(true);
    }

    /**
     * 동시성 규칙(헌법 V): [다시 올리기]와 예약 작업이 같은 회원·플랫폼의 빈 날을 함께 고르면 서로 다른 날을 받는다.
     * 시험이 회원 행을 먼저 잠가 두 요청이 모두 slot()을 고르기 직전(잠금 대기)에 멈춘 것을 확인한 뒤 놓는다.
     * 잠금이 없으면 두 요청이 기다리지 않아 대기 2건을 보지 못하고 실패한다
     */
    @Test
    void 다시_올리기와_예약_작업이_동시에_골라도_공개_날이_겹치지_않는다() throws Exception {
        Session me = signup(uniqueLogin("aulock"));
        Browser b = admin(me);
        enable(b, "youtube");
        long failedId = readyShorts(b, "실패했던 영상", null);
        assertThat(jobs.enqueue()).isEqualTo(1);
        jdbc.update("UPDATE shorts_auto_upload SET status = 'FAILED' WHERE shorts_id = ?", failedId);
        long fresh = readyShorts(b, "새로 만든 영상", null);

        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<?>[] running = new java.util.concurrent.Future<?>[2];
            tx.executeWithoutResult(s -> {
                jdbc.queryForList("SELECT id FROM member WHERE id = ? FOR UPDATE", Long.class, me.memberId());
                running[0] = pool.submit(() -> { start.await(); jobs.retry(me.memberId(), failedId, com.team.blog.shorts.autoupload.AutoPlatform.YOUTUBE); return null; });
                running[1] = pool.submit(() -> { start.await(); return jobs.enqueue(); });
                start.countDown();
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
                while (waitingOnMember() < 2) {
                    assertThat(System.nanoTime()).as("두 요청이 회원 행 잠금을 기다려야 한다").isLessThan(deadline);
                    Thread.onSpinWait();
                }
            });
            running[0].get(20, java.util.concurrent.TimeUnit.SECONDS);
            running[1].get(20, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        List<java.time.LocalDate> days = jdbc.queryForList("SELECT publish_at FROM shorts_auto_upload WHERE shorts_id IN (?, ?) AND status = 'QUEUED'",
                Timestamp.class, failedId, fresh).stream().map(t -> t.toInstant().atZone(KST).toLocalDate()).toList();
        assertThat(days).hasSize(2).doesNotHaveDuplicates();
    }

    /** 연결 해제와 진행 중 업로드(헌법 V 동시성): 업로드가 끝나기 전에 끊으면 작업은 FAILED로 남고, 올라간 영상이 있다고 알린다 */
    @Test
    void 올리는_도중에_연결을_끊으면_FAILED로_남고_올라간_영상을_알린다() throws Exception {
        Session me = signup(uniqueLogin("aucut"));
        Browser b = admin(me);
        enable(b, "youtube");
        long id = readyShorts(b, "올리는 중에 끊을 영상", null);
        assertThat(jobs.enqueue()).isEqualTo(1);
        java.util.concurrent.CountDownLatch uploading = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        FAKE.onVideoPut = () -> {
            uploading.countDown();
            try {
                release.await(20, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<Integer> run = pool.submit(jobs::runDue);
            assertThat(uploading.await(20, java.util.concurrent.TimeUnit.SECONDS)).as("영상 바이트가 가짜 YouTube에 닿았다").isTrue();
            b.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/me/shorts/auto-upload/youtube").with(
                    org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())).andExpect(status().isNoContent());
            release.countDown();
            run.get(20, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        Map<String, Object> row = jdbc.queryForMap("SELECT status, external_id, note FROM shorts_auto_upload WHERE shorts_id = ?", id);
        assertThat(row.get("status")).as("끊으며 멈춘 FAILED를 SCHEDULED로 덮지 않는다").isEqualTo("FAILED");
        assertThat(row.get("external_id")).isEqualTo(FakePlatforms.VIDEO_ID);
        assertThat((String) row.get("note")).contains("YouTube에 공개 예약돼 있어요");
    }

    /** 회원 행 잠금(SELECT … FROM member … FOR UPDATE)을 기다리는 다른 연결 수 */
    private int waitingOnMember() {
        // 잠금을 쥔 트랜잭션 안에서 읽으므로, 처음 읽은 통계 스냅샷이 트랜잭션 끝까지 남는다. 매번 비워 새로 읽는다
        jdbc.execute("SELECT pg_stat_clear_snapshot()");
        return jdbc.queryForObject("""
                SELECT count(*) FROM pg_stat_activity WHERE pid <> pg_backend_pid() AND wait_event_type = 'Lock'
                  AND query LIKE '%FROM member WHERE id = % FOR UPDATE%'
                """, Integer.class);
    }

    @Test
    void 유튜브는_시험을_통과해야_켜지고_렌더된_영상을_다음_빈_날_20시로_비공개_예약한다() throws Exception {
        Session me = signup(uniqueLogin("auyt"));
        Browser b = admin(me);
        connect(b, "youtube");
        JsonNode yt = platform(b, "YOUTUBE");
        assertThat(yt.path("connected").asBoolean()).isTrue();
        assertThat(yt.path("accountName").asString()).isEqualTo("devlog 채널");
        assertThat(yt.path("enabled").asBoolean()).isFalse();
        String stored = jdbc.queryForObject("SELECT access_token_enc || refresh_token_enc FROM shorts_platform_connection WHERE member_id = ?",
                String.class, me.memberId());
        assertThat(stored).startsWith("v1:").doesNotContain("yt-access-1").doesNotContain("yt-refresh-1");

        b.perform(asJson(put("/api/me/shorts/auto-upload/youtube/enabled"), Map.of("enabled", true))).andExpect(status().isConflict());

        long sample = readyShorts(b, "시험 영상", null);
        JsonNode test = read(b.perform(asJson(post("/api/me/shorts/auto-upload/youtube/test"), Map.of("shortsId", sample)))
                .andExpect(status().isOk()).andReturn());
        assertThat(test.path("passed").asBoolean()).isTrue();
        assertThat(test.path("url").asString()).isEqualTo("https://youtube.com/shorts/" + FakePlatforms.VIDEO_ID);
        JsonNode meta = json.readTree(FAKE.calls("POST /upload/youtube/v3/videos").getFirst().body());
        assertThat(meta.path("status").path("privacyStatus").asString()).isEqualTo("private");
        assertThat(meta.path("status").has("publishAt")).as("시험은 예약하지 않는다").isFalse();
        assertThat(jobs.enqueue()).as("켜기 전에는 넣지 않는다").isZero();

        b.perform(asJson(put("/api/me/shorts/auto-upload/youtube/enabled"), Map.of("enabled", true))).andExpect(status().isOk());
        FAKE.reset();
        long first = readyShorts(b, "배포하면 왜 로그아웃될까", "AI");
        long second = readyShorts(b, "두 번째 영상", null);
        assertThat(jobs.enqueue()).isEqualTo(2);
        assertThat(jobs.enqueue()).as("한 번만 넣는다").isZero();
        List<Instant> slots = jdbc.queryForList("SELECT publish_at FROM shorts_auto_upload WHERE member_id = ? ORDER BY publish_at", Timestamp.class,
                me.memberId()).stream().map(Timestamp::toInstant).toList();
        assertThat(slots).hasSize(2);
        slots.forEach(s -> assertThat(s.atZone(KST).toLocalTime()).isEqualTo(LocalTime.of(20, 0)));
        assertThat(slots.get(1).atZone(KST).toLocalDate()).as("하루에 두 편 없이 다음 날").isEqualTo(slots.get(0).atZone(KST).toLocalDate().plusDays(1));

        assertThat(jobs.runDue()).isEqualTo(2);
        assertThat(FAKE.calls("PUT /upload-session/1")).hasSize(2);
        JsonNode firstMeta = json.readTree(FAKE.calls("POST /upload/youtube/v3/videos").getFirst().body());
        assertThat(firstMeta.path("snippet").path("title").asString()).isEqualTo("[백엔드 면접] 배포하면 왜 로그아웃될까");
        assertThat(firstMeta.path("snippet").path("description").asString()).startsWith("전체 글: http").contains("/@" + me.handle() + "/posts/");
        assertThat(firstMeta.path("status").path("publishAt").asString()).isEqualTo(slots.get(0).toString());
        assertThat(firstMeta.path("status").path("containsSyntheticMedia").asBoolean()).isTrue();
        assertThat(FAKE.calls("POST /youtube/v3/playlistItems")).as("재생목록을 고른 영상만 넣는다").hasSize(1);
        assertThat(json.readTree(FAKE.calls("POST /youtube/v3/playlistItems").getFirst().body()).path("snippet").path("playlistId").asString()).isEqualTo("PLai");

        JsonNode view = read(b.perform(get("/api/me/shorts/" + first + "/auto-uploads")).andExpect(status().isOk()).andReturn()).path(0);
        assertThat(view.path("status").asString()).isEqualTo("SCHEDULED");
        assertThat(view.path("url").asString()).isEqualTo("https://youtube.com/shorts/" + FakePlatforms.VIDEO_ID);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shorts_upload WHERE shorts_id = ?", Integer.class, first))
                .as("공개 전에는 블로그에 보이지 않게 올린 주소를 적지 않는다").isZero();

        jdbc.update("UPDATE shorts_auto_upload SET publish_at = now() - interval '1 minute' WHERE shorts_id = ?", first);
        due();
        jobs.runDue();
        JsonNode item = read(b.perform(get("/api/me/shorts/" + first)).andExpect(status().isOk()).andReturn());
        assertThat(item.path("uploads").path(0).path("url").asString()).isEqualTo("https://youtube.com/shorts/" + FakePlatforms.VIDEO_ID);
        assertThat(jdbc.queryForObject("SELECT status FROM shorts_auto_upload WHERE shorts_id = ?", String.class, second)).isEqualTo("SCHEDULED");
    }

    @Test
    void 다시_할_수_있는_실패는_세_번까지_하고_FAILED에서_다시_올리기로_돌린다() throws Exception {
        Session me = signup(uniqueLogin("auretry"));
        Browser b = admin(me);
        connect(b, "youtube");
        long id = readyShorts(b, "시험 영상", null);
        b.perform(asJson(post("/api/me/shorts/auto-upload/youtube/test"), Map.of("shortsId", id))).andExpect(status().isOk());
        b.perform(asJson(put("/api/me/shorts/auto-upload/youtube/enabled"), Map.of("enabled", true))).andExpect(status().isOk());
        long target = readyShorts(b, "실패할 영상", null);
        jobs.enqueue();
        for (int i = 0; i < 3; i++) FAKE.once("POST /upload/youtube/v3/videos", new Reply(503, "{}"));
        for (int i = 0; i < 3; i++) {
            due();
            jobs.runDue();
        }
        JsonNode view = read(b.perform(get("/api/me/shorts/" + target + "/auto-uploads")).andReturn()).path(0);
        assertThat(view.path("status").asString()).isEqualTo("FAILED");
        assertThat(view.path("attempts").asInt()).isEqualTo(3);
        assertThat(view.path("error").asString()).contains("YouTube가 잠시 바빠요").endsWith("(3번 해 봤어요)");

        JsonNode retried = read(b.perform(post("/api/me/shorts/" + target + "/auto-uploads/youtube/retry").with(
                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())).andExpect(status().isOk()).andReturn()).path(0);
        assertThat(retried.path("status").asString()).isEqualTo("QUEUED");
        jobs.runDue();
        assertThat(jdbc.queryForObject("SELECT status FROM shorts_auto_upload WHERE shorts_id = ?", String.class, target)).isEqualTo("SCHEDULED");

        // 연결을 끊으면 남은 작업을 멈춘다
        b.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/me/shorts/auto-upload/youtube").with(
                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())).andExpect(status().isNoContent());
        assertThat(platform(b, "YOUTUBE").path("connected").asBoolean()).isFalse();
    }

    @Test
    void 인스타는_시험에서_게시하지_않고_공개_시각에_서버가_릴스를_게시한다() throws Exception {
        Session me = signup(uniqueLogin("auig"));
        Browser b = admin(me);
        connect(b, "instagram");
        assertThat(platform(b, "INSTAGRAM").path("accountName").asString()).isEqualTo("devlog.k");
        long sample = readyShorts(b, "시험 영상", null);
        JsonNode test = read(b.perform(asJson(post("/api/me/shorts/auto-upload/instagram/test"), Map.of("shortsId", sample)))
                .andExpect(status().isOk()).andReturn());
        assertThat(test.path("passed").asBoolean()).isTrue();
        assertThat(FAKE.calls("POST /v24.0/" + FakePlatforms.IG_USER + "/media")).hasSize(1);
        assertThat(FAKE.calls("POST /v24.0/" + FakePlatforms.IG_USER + "/media_publish")).as("시험은 게시하지 않는다").isEmpty();
        b.perform(asJson(put("/api/me/shorts/auto-upload/instagram/enabled"), Map.of("enabled", true))).andExpect(status().isOk());

        FAKE.reset();
        long id = readyShorts(b, "릴스로 올릴 영상", null);
        assertThat(jobs.enqueue()).isEqualTo(1);
        jobs.runDue();
        assertThat(FAKE.calls("POST /v24.0/" + FakePlatforms.IG_USER + "/media")).as("공개 15분 전까지는 컨테이너를 만들지 않는다").isEmpty();

        jdbc.update("UPDATE shorts_auto_upload SET publish_at = now() - interval '1 minute' WHERE shorts_id = ?", id);
        due();
        jobs.runDue();
        FakePlatforms.Seen created = FAKE.calls("POST /v24.0/" + FakePlatforms.IG_USER + "/media").getFirst();
        assertThat(created.form()).containsEntry("media_type", "REELS");
        assertThat(created.form().get("caption")).startsWith("릴스로 올릴 영상\n\n").contains("프로필 링크의 블로그");
        assertThat(created.form().get("video_url")).endsWith("/shorts/2026/10/auto-" + id + ".mp4");
        due();
        jobs.runDue();
        assertThat(FAKE.calls("POST /v24.0/" + FakePlatforms.IG_USER + "/media_publish")).hasSize(1);
        JsonNode view = read(b.perform(get("/api/me/shorts/" + id + "/auto-uploads")).andReturn()).path(0);
        assertThat(view.path("status").asString()).isEqualTo("PUBLISHED");
        assertThat(view.path("url").asString()).isEqualTo("https://www.instagram.com/reel/DAbc123/");
        assertThat(jdbc.queryForObject("SELECT url FROM shorts_upload WHERE shorts_id = ? AND platform = 'INSTAGRAM'", String.class, id))
                .isEqualTo("https://www.instagram.com/reel/DAbc123/");
    }
}
