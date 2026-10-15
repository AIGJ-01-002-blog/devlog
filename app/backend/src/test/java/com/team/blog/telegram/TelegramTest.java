package com.team.blog.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import tools.jackson.databind.JsonNode;

import com.team.blog.support.FakeAi;
import com.team.blog.support.FakeTelegram;
import com.team.blog.support.IntegrationTest;
import com.team.blog.support.Waits;
import com.team.blog.telegram.application.TelegramBot;
import com.team.blog.telegram.infra.TelegramApi;

/** spec 023 인수 시나리오: 연결(US1), 알림(US2), 메모로 임시글(US3). 봇 업데이트는 테스트가 직접 넣는다. */
class TelegramTest extends IntegrationTest {
    @Autowired TelegramBot bot;

    static final FakeTelegram TG = FakeTelegram.INSTANCE;

    @BeforeEach
    void resetAi() {
        FakeAi.INSTANCE.reset();
        redis.delete(redis.keys("ai:*"));
    }

    static long newChat() {
        return ThreadLocalRandom.current().nextLong(1_000_000, Long.MAX_VALUE / 2);
    }

    void say(long chat, String text) {
        bot.handle(new TelegramApi.Update(1, chat, "private", text, "민서"));
    }

    String code(Session s) throws Exception {
        String url = read(s.http().perform(post("/api/me/telegram/link").with(csrf())).andExpect(status().isOk()).andReturn())
                .path("url").asString();
        assertThat(url).startsWith("https://t.me/" + FakeTelegram.BOT + "?start=");
        return url.substring(url.indexOf("start=") + 6);
    }

    long link(Session s) throws Exception {
        long chat = newChat();
        say(chat, "/start " + code(s));
        assertThat(TG.await(chat, 1)).contains("블로그와 연결했어요");
        return chat;
    }

    JsonNode telegram(Session s) throws Exception {
        return read(s.http().perform(get("/api/me/telegram")).andExpect(status().isOk()).andReturn());
    }

    /** 연결이 남아 있는가 (기다리는 조건에 쓴다) */
    boolean linked(Session s) {
        try {
            return telegram(s).path("linked").asBoolean();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    long publish(Session s) throws Exception {
        long id = read(s.http().perform(asJson(post("/api/posts"), Map.of("title", "글", "contentMd", "")))
                .andExpect(status().isCreated()).andReturn()).path("id").asLong();
        s.http().perform(asJson(post("/api/posts/" + id + "/publish"), Map.of("title", "레디스 정리", "contentMd", "본문",
                "visibility", "PUBLIC", "baseVersion", 0, "tags", List.of()))
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isOk());
        return id;
    }

    // ---------------------------------------------------------------- US1

    @Test
    void 연결_주소로_봇을_시작하면_계정에_연결되고_코드는_한_번만_쓰인다() throws Exception {
        Session s = signup(uniqueLogin("tg"));
        JsonNode before = telegram(s);
        assertThat(before.path("available").asBoolean()).isTrue();
        assertThat(before.path("linked").asBoolean()).isFalse();
        String code = code(s);
        long chat = newChat();
        say(chat, "/start " + code);
        assertThat(TG.await(chat, 1)).contains("블로그와 연결했어요");
        assertThat(telegram(s).path("linked").asBoolean()).isTrue();

        long other = newChat();
        say(other, "/start " + code);
        assertThat(TG.await(other, 1)).contains("만료");
        say(other, "/start nope");
        assertThat(TG.await(other, 2)).contains("만료");
        // 연결 안 된 대화의 메모는 저장하지 않고 안내만
        say(other, "메모");
        assertThat(TG.await(other, 3)).contains("연결되지 않았어요");

        // 새 코드를 받으면 전 코드는 무효
        String first = code(s);
        String second = code(s);
        long third = newChat();
        say(third, "/start " + first);
        assertThat(TG.await(third, 1)).contains("만료");
        say(third, "/start " + second);
        assertThat(TG.await(third, 2)).contains("연결했어요");
        // 같은 회원은 대화 하나: 마지막 대화로 옮겨졌다
        assertThat(jdbc.queryForObject("SELECT chat_id FROM member_telegram WHERE member_id = ?", Long.class, s.memberId())).isEqualTo(third);

        say(third, "/stop");
        assertThat(TG.await(third, 3)).contains("연결을 끊었어요");
        assertThat(telegram(s).path("linked").asBoolean()).isFalse();
    }

    @Test
    void 대화_하나는_한_계정에만_연결된다() throws Exception {
        Session a = signup(uniqueLogin("tga")), b = signup(uniqueLogin("tgb"));
        long chat = link(a);
        say(chat, "/start " + code(b));
        TG.await(chat, 2);
        assertThat(telegram(a).path("linked").asBoolean()).isFalse();
        assertThat(telegram(b).path("linked").asBoolean()).isTrue();
        b.http().perform(delete("/api/me/telegram").with(csrf())).andExpect(status().isNoContent());
        assertThat(telegram(b).path("linked").asBoolean()).isFalse();
    }

    // ---------------------------------------------------------------- US2

    @Test
    void 새_알림이_텔레그램으로_가고_끄면_가지_않는다() throws Exception {
        Session author = signup(uniqueLogin("tgw")), reader = signup(uniqueLogin("tgr"));
        long chat = link(author);
        long post = publish(author);
        reader.http().perform(asJson(post("/api/posts/" + post + "/comments"), Map.of("content", "좋은 글 감사합니다")))
                .andExpect(status().isCreated());
        drain();
        String msg = TG.await(chat, 2);
        assertThat(msg).contains("댓글을 남겼어요").contains("「레디스 정리」").contains("좋은 글 감사합니다")
                .contains("/@" + author.handle() + "/posts/" + post);

        author.http().perform(asJson(patch("/api/me/telegram"), Map.of("notifications", false))).andExpect(status().isOk());
        reader.http().perform(asJson(post("/api/posts/" + post + "/comments"), Map.of("content", "두 번째"))).andExpect(status().isCreated());
        drain();
        // 알림을 끈 뒤에는 잠시 기다려도 더 오지 않는다
        Waits.during(Duration.ofMillis(300), () -> assertThat(TG.to(chat)).hasSize(2));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notification WHERE receiver_id = ? AND type = 'COMMENT'", Long.class,
                author.memberId())).isEqualTo(2);
    }

    @Test
    void 팔로우한_사람의_새_글처럼_한_번에_여러_명에게_만드는_알림도_보낸다() throws Exception {
        Session author = signup(uniqueLogin("tgn")), follower = signup(uniqueLogin("tgf"));
        long chat = link(follower);
        follower.http().perform(put("/api/members/" + author.handle() + "/follow").with(csrf())).andExpect(status().is2xxSuccessful());
        long post = publish(author);
        drain();
        assertThat(TG.await(chat, 2)).contains("「레디스 정리」").contains("/@" + author.handle() + "/posts/" + post);
    }

    @Test
    void 봇을_차단하면_연결을_지운다() throws Exception {
        Session author = signup(uniqueLogin("tgx")), reader = signup(uniqueLogin("tgy"));
        long chat = link(author);
        TG.blocked.add(chat);
        long post = publish(author);
        reader.http().perform(asJson(post("/api/posts/" + post + "/comments"), Map.of("content", "안녕"))).andExpect(status().isCreated());
        drain();
        Waits.until(() -> !linked(author), Duration.ofSeconds(5));
        assertThat(telegram(author).path("linked").asBoolean()).isFalse();
    }

    // ---------------------------------------------------------------- US3

    String draftTitle(long postId) {
        return jdbc.queryForObject("SELECT title FROM post WHERE id = ? AND status = 'DRAFT'", String.class, postId);
    }

    long draftIdFrom(String msg) {
        return Long.parseLong(msg.substring(msg.indexOf("/write/") + 7).split("\\s")[0]);
    }

    @Test
    void AI에_동의하지_않았으면_메모_그대로_임시글이_된다() throws Exception {
        Session s = signup(uniqueLogin("tgm"));
        long chat = link(s);
        say(chat, "# Redis TTL 메모\nEXPIRE는 키를 덮어쓰면 사라진다");
        String msg = TG.await(chat, 2);
        assertThat(msg).contains("임시글로 저장했어요").contains("「Redis TTL 메모」").contains("AI 기능에 동의하면");
        long id = draftIdFrom(msg);
        assertThat(draftTitle(id)).isEqualTo("Redis TTL 메모");
        assertThat(jdbc.queryForObject("SELECT content_md FROM post WHERE id = ?", String.class, id)).contains("EXPIRE는 키를 덮어쓰면");
        assertThat(FakeAi.INSTANCE.geminiRequests).isEmpty();
    }

    @Test
    void AI에_동의했으면_다듬은_제목과_본문으로_임시글이_되고_실패하면_그대로_둔다() throws Exception {
        Session s = signup(uniqueLogin("tgai"));
        s.http().perform(post("/api/me/agreements/ai").with(csrf())).andExpect(status().isNoContent());
        long chat = link(s);
        FakeAi.INSTANCE.nextGemini(FakeAi.geminiText("{\"title\":\"Redis EXPIRE와 SET의 TTL 함정\",\"contentMd\":\"## 알게 된 점\\n\\n- `SET`으로 덮어쓰면 TTL이 사라진다\"}"));
        say(chat, "redis set하면 ttl 날아감 주의");
        String msg = TG.await(chat, 2);
        assertThat(msg).contains("「Redis EXPIRE와 SET의 TTL 함정」").doesNotContain("메모 그대로");
        long id = draftIdFrom(msg);
        assertThat(jdbc.queryForObject("SELECT content_md FROM post WHERE id = ?", String.class, id)).contains("## 알게 된 점");
        // 메모는 지시문과 구분해 보낸다
        assertThat(FakeAi.INSTANCE.geminiRequests.getLast()).contains("redis set하면 ttl 날아감 주의").contains("지어내지 않는다");

        FakeAi.INSTANCE.nextGemini(new FakeAi.Reply(500, "{}"));
        say(chat, "두 번째 메모");
        String failed = TG.await(chat, 3);
        assertThat(failed).contains("「두 번째 메모」").contains("메모 그대로");
    }

    @Test
    void 이메일_인증_전이거나_탈퇴_신청한_계정의_메모는_저장하지_않는다() throws Exception {
        Session s = signup(uniqueLogin("tgblock"));
        long chat = link(s);
        jdbc.update("UPDATE auth_identity SET email_verified_at = NULL WHERE member_id = ?", s.memberId());
        say(chat, "메모");
        assertThat(TG.await(chat, 2)).contains("이메일 인증");
        jdbc.update("UPDATE member SET status = 'SUSPENDED' WHERE id = ?", s.memberId());
        say(chat, "메모");
        assertThat(TG.await(chat, 3)).contains("정지된 계정");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM post WHERE author_id = ?", Long.class, s.memberId())).isZero();
    }

    @Test
    void 비로그인은_설정_API를_쓸_수_없다() throws Exception {
        browser().perform(get("/api/me/telegram")).andExpect(status().isUnauthorized());
        browser().perform(post("/api/me/telegram/link").with(csrf())).andExpect(status().isUnauthorized());
    }
}
