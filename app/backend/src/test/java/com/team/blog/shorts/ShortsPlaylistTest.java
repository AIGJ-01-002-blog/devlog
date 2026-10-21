package com.team.blog.shorts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import com.team.blog.support.IntegrationTest;

/** 묶음별 재생목록 (2026-10-21 블로그 주인 결정 "하루 1개, 7주"): 고르기만 하고 대본·영상은 그대로 둔다 */
class ShortsPlaylistTest extends IntegrationTest {
    private static final List<Map<String, String>> SCENES = List.of(
            Map.of("caption", "왜 롤백이 안 될까", "narration", "트랜잭셔널을 붙였는데 왜 롤백이 안 될까요?"),
            Map.of("caption", "범인", "narration", "같은 클래스 안에서 부르면 프록시를 거치지 않아요."),
            Map.of("caption", "결과", "narration", "메서드를 다른 빈으로 옮기니 롤백이 됐어요."));

    @Test
    void 재생목록을_고르고_지울_수_있고_만든_영상과_버전은_그대로다() throws Exception {
        Session me = signup(uniqueLogin("plist"));
        Session other = signup(uniqueLogin("plisto"));
        JsonNode created = read(me.http().perform(asJson(post("/api/me/shorts"), Map.of("title", "롤백이 안 되는 이유", "scenes", SCENES)))
                .andExpect(status().isCreated()).andReturn());
        long id = created.path("id").asLong();
        assertThat(created.path("playlist").isNull()).isTrue();
        jdbc.update("UPDATE shorts SET status = 'READY', video_key = 'shorts/2026/10/p.mp4', duration_ms = 33000 WHERE id = ?", id);
        int version = read(me.http().perform(get("/api/me/shorts/" + id)).andReturn()).path("version").asInt();

        other.http().perform(asJson(put("/api/me/shorts/" + id + "/playlist"), Map.of("playlist", "DB"))).andExpect(status().isNotFound());
        me.http().perform(asJson(put("/api/me/shorts/" + id + "/playlist"), Map.of("playlist", "게임"))).andExpect(status().isBadRequest());

        JsonNode item = read(me.http().perform(asJson(put("/api/me/shorts/" + id + "/playlist"), Map.of("playlist", "db")))
                .andExpect(status().isOk()).andReturn());
        assertThat(item.path("playlist").asString()).isEqualTo("DB");
        assertThat(item.path("status").asString()).as("영상은 그대로").isEqualTo("READY");
        assertThat(item.path("version").asInt()).as("대본이 아니라 버전을 올리지 않는다").isEqualTo(version);

        item = read(me.http().perform(asJson(put("/api/me/shorts/" + id + "/playlist"), Map.of("playlist", "보안")))
                .andExpect(status().isOk()).andReturn());
        assertThat(item.path("playlist").asString()).as("한글 이름도 받는다").isEqualTo("SECURITY");

        item = read(me.http().perform(asJson(put("/api/me/shorts/" + id + "/playlist"), Map.of("playlist", "")))
                .andExpect(status().isOk()).andReturn());
        assertThat(item.path("playlist").isNull()).isTrue();
    }
}
