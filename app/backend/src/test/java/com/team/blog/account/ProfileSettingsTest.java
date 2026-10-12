package com.team.blog.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.JsonNode;

import com.team.blog.media.ProfileImageCleanupJob;
import com.team.blog.media.storage.ObjectStorage;
import com.team.blog.shared.markdown.ImageUrls;
import com.team.blog.support.Browser;
import com.team.blog.support.IntegrationTest;
import com.team.blog.support.TestImages;

/** 005 프로필·설정 수용 기준. */
class ProfileSettingsTest extends IntegrationTest {
    @Autowired ProfileImageCleanupJob cleanupJob;
    @Autowired ObjectStorage storage;
    @Autowired ImageUrls imageUrls;

    private ResultActions upload(Browser b, byte[] data, String type) throws Exception {
        return b.perform(post("/api/me/profile-image").with(csrf()).contentType(type).content(data));
    }

    private long uploadOk(Session s) throws Exception {
        return read(upload(s.http(), TestImages.png(256, 256), "image/png").andExpect(status().isCreated()).andReturn())
                .path("id").asLong();
    }

    private ResultActions saveProfile(Session s, Map<String, Object> body) throws Exception {
        return s.http().perform(asJson(patch("/api/me/profile"), body));
    }

    private String bioOf(long memberId) {
        return jdbc.queryForObject("SELECT bio FROM member WHERE id = ?", String.class, memberId);
    }

    private String keyOf(long resourceId) {
        return jdbc.queryForObject("SELECT storage_key FROM resource WHERE id = ?", String.class, resourceId);
    }

    @Test
    void 설정_화면은_주소_이메일_로그인_수단_직전_로그인_기본_공개_범위를_보여준다() throws Exception {
        Session s = signup(uniqueLogin("settings"));
        s.http().perform(get("/api/me/settings")).andExpect(status().isOk())
                .andExpect(jsonPath("$.handle").value(s.handle()))
                .andExpect(jsonPath("$.email").isNotEmpty())
                .andExpect(jsonPath("$.provider").value("GITHUB"))
                .andExpect(jsonPath("$.hasPassword").value(false))
                .andExpect(jsonPath("$.previousLogin.at").doesNotExist())
                .andExpect(jsonPath("$.defaultVisibility").value("PUBLIC"))
                .andExpect(jsonPath("$.aiAgreed").value(false))
                .andExpect(jsonPath("$.profileImageUrl").doesNotExist())
                .andExpect(jsonPath("$.terms.termsVersion").isNotEmpty());
        // 다시 로그인하면 "그 전" 로그인이 보인다
        Browser again = relogin(s);
        again.perform(get("/api/me/settings")).andExpect(jsonPath("$.previousLogin.at").isNotEmpty())
                .andExpect(jsonPath("$.previousLogin.provider").value("GITHUB"));
        // 비회원은 설정을 볼 수 없다
        browser().perform(get("/api/me/settings")).andExpect(status().isUnauthorized());
    }

    @Test
    void 소개와_닉네임을_한_번에_저장하면_블로그_상단에_바로_반영된다() throws Exception {
        Session s = signup(uniqueLogin("bio"));
        String nick = "새닉" + (s.memberId() % 100000);
        saveProfile(s, Map.of("nickname", nick, "bio", "  안녕하세요 <b>개발자</b>\n\n\n\n주소 https://x.dev  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value(nick))
                .andExpect(jsonPath("$.bio").value("안녕하세요 <b>개발자</b>\n\n주소 https://x.dev"))
                .andExpect(jsonPath("$.nicknameNextChangeableAt").isNotEmpty());
        mvc.perform(get("/api/members/" + s.handle())).andExpect(jsonPath("$.nickname").value(nick))
                .andExpect(jsonPath("$.bio").value("안녕하세요 <b>개발자</b>\n\n주소 https://x.dev"));
        // 보낸 칸만 바뀐다
        saveProfile(s, Map.of("bio", "")).andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value(nick));
        assertThat(bioOf(s.memberId())).isNull();
    }

    @Test
    void 소개는_200자_4줄을_넘거나_금칙어가_있으면_거부된다() throws Exception {
        Session s = signup(uniqueLogin("biolimit"));
        saveProfile(s, Map.of("bio", "가".repeat(201))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("bio"))
                .andExpect(jsonPath("$.errors[0].code").value("BIO_TOO_LONG"))
                .andExpect(jsonPath("$.errors[0].message").value("소개는 200자까지 쓸 수 있어요."));
        saveProfile(s, Map.of("bio", "1\n2\n3\n4\n5")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("BIO_TOO_MANY_LINES"));
        saveProfile(s, Map.of("bio", "시발 테스트")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("BIO_BANNED_WORD"));
        // 이모지 200자(코드 포인트)까지는 된다
        saveProfile(s, Map.of("bio", "😀".repeat(200))).andExpect(status().isOk());
    }

    @Test
    void 하나라도_틀리면_아무것도_저장되지_않고_틀린_칸이_모두_표시된다() throws Exception {
        Session s = signup(uniqueLogin("atomic"));
        long img = uploadOk(s);
        saveProfile(s, Map.of("bio", "x".repeat(201), "nickname", "a", "profileImageId", img))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2));
        saveProfile(s, Map.of("bio", "맞는 소개", "nickname", "!!", "profileImageId", img)).andExpect(status().isBadRequest());
        assertThat(bioOf(s.memberId())).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member_profile_image WHERE member_id = ?", Integer.class, s.memberId())).isZero();
    }

    @Test
    void 닉네임_30일_제한_중에는_닉네임을_바꾸는_요청만_전체가_실패한다() throws Exception {
        Session s = signup(uniqueLogin("cool"));
        String first = "첫닉" + (s.memberId() % 100000);
        saveProfile(s, Map.of("nickname", first)).andExpect(status().isOk());
        // 같은 닉네임을 다시 보내면 변경이 아니어서 소개는 저장된다
        saveProfile(s, Map.of("nickname", first, "bio", "소개만")).andExpect(status().isOk());
        assertThat(bioOf(s.memberId())).isEqualTo("소개만");
        saveProfile(s, Map.of("nickname", "둘닉" + (s.memberId() % 100000), "bio", "안 바뀜"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("NICKNAME_CHANGE_TOO_SOON"))
                .andExpect(jsonPath("$.details.nextChangeableAt").isNotEmpty());
        assertThat(bioOf(s.memberId())).isEqualTo("소개만");
    }

    @Test
    void 사진을_올리고_저장해야_프로필_사진이_되고_바꾸면_이전_사진은_연결이_끊긴다() throws Exception {
        Session s = signup(uniqueLogin("photo"));
        JsonNode up = read(upload(s.http(), TestImages.png(256, 256), "image/png").andExpect(status().isCreated()).andReturn());
        long first = up.path("id").asLong();
        assertThat(up.path("url").asString()).startsWith(imageUrls.publicBaseUrl() + "/profiles/").endsWith(".png");
        // 올리기만 해서는 프로필이 아니다
        mvc.perform(get("/api/members/" + s.handle())).andExpect(jsonPath("$.profileImageUrl").doesNotExist());

        saveProfile(s, Map.of("profileImageId", first)).andExpect(status().isOk())
                .andExpect(jsonPath("$.profileImageUrl").value(up.path("url").asString()));
        mvc.perform(get("/api/members/" + s.handle())).andExpect(jsonPath("$.profileImageUrl").value(up.path("url").asString()));
        s.http().perform(get("/api/auth/me")).andExpect(jsonPath("$.member.profileImageUrl").value(up.path("url").asString()));

        long second = uploadOk(s);
        saveProfile(s, Map.of("profileImageId", second)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT detached_at IS NOT NULL FROM resource WHERE id = ?", Boolean.class, first)).isTrue();
        assertThat(jdbc.queryForObject("SELECT resource_id FROM member_profile_image WHERE member_id = ?", Long.class, s.memberId()))
                .isEqualTo(second);

        // 기본 이미지로
        Map<String, Object> reset = new HashMap<>();
        reset.put("profileImageId", null);
        saveProfile(s, reset).andExpect(status().isOk()).andExpect(jsonPath("$.profileImageUrl").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member_profile_image WHERE member_id = ?", Integer.class, s.memberId())).isZero();
        assertThat(jdbc.queryForObject("SELECT detached_at IS NOT NULL FROM resource WHERE id = ?", Boolean.class, second)).isTrue();
    }

    @Test
    void 올린_사진은_앱이_로컬_저장소에서_내려준다() throws Exception {
        Session s = signup(uniqueLogin("serve"));
        byte[] png = TestImages.png(256, 256);
        long id = read(upload(s.http(), png, "image/png").andExpect(status().isCreated()).andReturn()).path("id").asLong();
        mvc.perform(get("/media/" + keyOf(id))).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(png));
        mvc.perform(get("/media/profiles/2026/01/none.png")).andExpect(status().isNotFound());
        mvc.perform(get("/media/../application.yml")).andExpect(status().is4xxClientError());
    }

    @Test
    void 크기_형식_용량_사진_정보가_맞지_않는_사진은_거부된다() throws Exception {
        Session s = signup(uniqueLogin("reject"));
        upload(s.http(), TestImages.png(300, 300), "image/png").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMAGE_DIMENSION"));
        upload(s.http(), "<svg/>".getBytes(), "image/png").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMAGE_TYPE"));
        upload(s.http(), TestImages.jpegWithExif(256, 256), "image/jpeg").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMAGE_METADATA"));
        upload(s.http(), new byte[1024 * 1024 + 1], "image/png").andExpect(status().isContentTooLarge());
        upload(s.http(), TestImages.jpeg(256, 256), "image/jpeg").andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resource WHERE uploader_id = ?", Integer.class, s.memberId())).isEqualTo(1);
    }

    @Test
    void 남이_올렸거나_글용으로_올린_사진은_프로필로_저장할_수_없다() throws Exception {
        Session me = signup(uniqueLogin("mine"));
        Session other = signup(uniqueLogin("other"));
        long others = uploadOk(other);
        saveProfile(me, Map.of("profileImageId", others)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("PROFILE_IMAGE_INVALID"));
        Long postImage = jdbc.queryForObject("""
                WITH r AS (INSERT INTO resource (uploader_id, storage_key, content_type, size_bytes, kind)
                           VALUES (?, ?, 'image/webp', 100, 'IMAGE') RETURNING id)
                INSERT INTO resource_image (resource_id, width, height) SELECT id, 256, 256 FROM r RETURNING resource_id
                """, Long.class, me.memberId(), "images/2026/10/" + java.util.UUID.randomUUID() + ".webp");
        saveProfile(me, Map.of("profileImageId", postImage)).andExpect(status().isBadRequest());
        saveProfile(me, Map.of("profileImageId", "12")).andExpect(status().isBadRequest());
    }

    @Test
    void 이메일_인증_전에는_사진을_올릴_수_없지만_소개는_고칠_수_있다() throws Exception {
        Session s = signup(uniqueLogin("unverified"));
        jdbc.update("UPDATE auth_identity SET email_verified_at = NULL WHERE member_id = ?", s.memberId());
        upload(s.http(), TestImages.png(256, 256), "image/png").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
        saveProfile(s, Map.of("bio", "인증 전 소개")).andExpect(status().isOk());
        s.http().perform(asJson(patch("/api/me/settings"), Map.of("defaultVisibility", "PRIVATE"))).andExpect(status().isOk());
    }

    @Test
    void 사진_업로드는_1분에_20장까지다() throws Exception {
        Session s = signup(uniqueLogin("burst"));
        for (int i = 0; i < 20; i++) upload(s.http(), TestImages.png(256, 256), "image/png").andExpect(status().isCreated());
        upload(s.http(), TestImages.png(256, 256), "image/png").andExpect(status().isTooManyRequests());
    }

    @Test
    void 저장하지_않은_사진은_24시간_뒤_끊긴_사진은_7일_뒤_지우고_지금_사진은_남긴다() throws Exception {
        Session s = signup(uniqueLogin("cleanup"));
        long unsaved = uploadOk(s);
        long old = uploadOk(s);
        long current = uploadOk(s);
        long recentDetached = uploadOk(s);
        saveProfile(s, Map.of("profileImageId", old)).andExpect(status().isOk());
        saveProfile(s, Map.of("profileImageId", recentDetached)).andExpect(status().isOk());
        saveProfile(s, Map.of("profileImageId", current)).andExpect(status().isOk());
        Timestamp longAgo = Timestamp.from(Instant.now().minus(30, ChronoUnit.DAYS));
        jdbc.update("UPDATE resource SET created_at = ? WHERE id IN (?, ?, ?, ?)", longAgo, unsaved, old, current, recentDetached);
        jdbc.update("UPDATE resource SET detached_at = ? WHERE id = ?", Timestamp.from(Instant.now().minus(8, ChronoUnit.DAYS)), old);
        String unsavedKey = keyOf(unsaved);
        assertThat(storage.get(unsavedKey)).isPresent();

        assertThat(cleanupJob.runOnce()).isGreaterThanOrEqualTo(2);

        assertThat(jdbc.queryForList("SELECT id FROM resource WHERE uploader_id = ? ORDER BY id", Long.class, s.memberId()))
                .containsExactly(current, recentDetached);
        assertThat(storage.get(unsavedKey)).isEmpty();
        // 방금 올린 사진(24시간 전)은 남는다
        long fresh = uploadOk(s);
        cleanupJob.runOnce();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM resource WHERE id = ?", Integer.class, fresh)).isEqualTo(1);
    }

    @Test
    void 기본_공개_범위를_나만_보기로_바꾸면_새_글이_비공개로_시작한다() throws Exception {
        Session s = signup(uniqueLogin("defvis"));
        s.http().perform(asJson(patch("/api/me/settings"), Map.of("defaultVisibility", "PRIVATE"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultVisibility").value("PRIVATE"));
        s.http().perform(asJson(post("/api/posts"), Map.of("title", "새 글", "contentMd", "본문")))
                .andExpect(jsonPath("$.visibility").value("PRIVATE"));
        s.http().perform(asJson(patch("/api/me/settings"), Map.of("defaultVisibility", "GROUP"))).andExpect(status().isBadRequest());
    }

    @Test
    void AI_동의를_철회하면_기록이_지워진다() throws Exception {
        Session s = signup(uniqueLogin("aiwd"));
        jdbc.update("INSERT INTO member_agreement (member_id, type, version, agreed_at) VALUES (?, 'AI', '1', now())", s.memberId());
        s.http().perform(get("/api/me/settings")).andExpect(jsonPath("$.aiAgreed").value(true));
        s.http().perform(delete("/api/me/agreements/ai").with(csrf())).andExpect(status().isNoContent());
        s.http().perform(get("/api/me/settings")).andExpect(jsonPath("$.aiAgreed").value(false));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM member_agreement WHERE member_id = ?", Integer.class, s.memberId())).isEqualTo(2);
        s.http().perform(delete("/api/me/agreements/ai").with(csrf())).andExpect(status().isNoContent());
    }

    @Test
    void 소셜_사진_주소는_GitHub_Google_사진_서버의_https_주소만_가입_화면에_넘긴다() throws Exception {
        Browser ok = browser();
        ok.perform(asJson(post("/api/dev/login"), Map.of("providerUserId", "77" + System.nanoTime() % 1_000_000, "login", uniqueLogin("av"),
                "name", "av", "email", "av@example.com", "avatarUrl", "https://avatars.githubusercontent.com/u/1?v=4"))).andExpect(status().isOk());
        ok.perform(get("/api/auth/signup")).andExpect(jsonPath("$.avatarUrl").value("https://avatars.githubusercontent.com/u/1?v=4"));

        for (String bad : new String[]{"http://avatars.githubusercontent.com/u/1", "https://evil.example/a.png",
                "https://avatars.githubusercontent.com:8443/u/1", "https://user@lh3.googleusercontent.com/a"}) {
            Browser b = browser();
            b.perform(asJson(post("/api/dev/login"), Map.of("providerUserId", "78" + System.nanoTime() % 1_000_000, "login", uniqueLogin("bad"),
                    "name", "bad", "email", "bad@example.com", "avatarUrl", bad))).andExpect(status().isOk());
            b.perform(get("/api/auth/signup")).andExpect(jsonPath("$.avatarUrl").doesNotExist());
        }
        // 가입 마무리 화면에서만 두 사진 서버를 불러올 수 있다
        mvc.perform(get("/signup/social")).andExpect(header().string("Content-Security-Policy",
                org.hamcrest.Matchers.containsString("https://avatars.githubusercontent.com")));
        mvc.perform(get("/settings")).andExpect(header().string("Content-Security-Policy",
                org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("githubusercontent"))));
    }
}
