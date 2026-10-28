package com.team.blog.shorts.autoupload;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.team.blog.shorts.application.ShortsService.Playlist;
import com.team.blog.shorts.application.UploadLink.Platform;

/**
 * 서버가 올릴 때 쓰는 제목·캡션이 스튜디오 올리기 안내(uploadGuide)와 같은지 (spec 108 FR-010).
 * 견본 파일은 화면 테스트(shorts.test.ts)도 읽는다. 한쪽만 바꾸면 둘 중 하나가 깨진다.
 */
class ShortsCaptionParityTest {
    /** 테스트는 app/backend에서 돈다 */
    static final Path FIXTURE = Path.of("../frontend/src/lib/shortsCaptionParity.json");

    @Test
    void 견본마다_화면과_같은_제목_캡션_재생목록을_만든다() throws Exception {
        JsonNode root = JsonMapper.builder().build().readTree(Files.readString(FIXTURE));
        String origin = root.path("origin").asString();
        int checked = 0;
        for (JsonNode sample : root.path("samples")) {
            JsonNode in = sample.path("input");
            List<String> narrations = new ArrayList<>();
            in.path("narrations").forEach(n -> narrations.add(n.asString()));
            List<String> tags = new ArrayList<>();
            in.path("tags").forEach(n -> tags.add(n.asString()));
            String playlist = in.path("playlist").isNull() ? null : Playlist.valueOf(in.path("playlist").asString()).label;
            ShortsCaption.Input input = new ShortsCaption.Input(in.path("title").asString(), in.path("postId").isNull() ? null : in.path("postId").asLong(),
                    in.path("postUrl").isNull() ? null : in.path("postUrl").asString(), in.path("replay").asBoolean(), narrations, tags, playlist);
            for (String p : List.of("YOUTUBE", "INSTAGRAM")) {
                JsonNode want = sample.path("expected").path(p);
                ShortsCaption.Text got = ShortsCaption.build(input, Platform.valueOf(p), origin, java.time.LocalTime.of(20, 0)); // 화면 SHORTS_PUBLISH_TIME
                String name = sample.path("name").asString() + " / " + p;
                assertThat(got.title()).as(name).isEqualTo(want.path("title").isNull() ? null : want.path("title").asString());
                assertThat(got.caption()).as(name).isEqualTo(want.path("caption").asString());
                assertThat(got.playlistName()).as(name).isEqualTo(want.path("playlistName").isNull() ? null : want.path("playlistName").asString());
                checked++;
            }
        }
        assertThat(checked).isGreaterThanOrEqualTo(6);
    }

    @Test
    void 한도는_코드_포인트로_자른다() {
        assertThat(ShortsCaption.limit("가나다라", 2)).isEqualTo("가나");
        assertThat(ShortsCaption.limit("😀😀😀", 2)).isEqualTo("😀😀");
        assertThat(ShortsCaption.limit("짧음", 10)).isEqualTo("짧음");
        assertThat(ShortsCaption.jsTrim("﻿　 글 \n")).isEqualTo("글");
        assertThat(ShortsCaption.link("https://devlog.life/", "/@a/posts/1")).isEqualTo("https://devlog.life/@a/posts/1");
        assertThat(ShortsCaption.clock(java.time.LocalTime.of(20, 0))).isEqualTo("저녁 8시");
        assertThat(ShortsCaption.clock(java.time.LocalTime.of(19, 30))).isEqualTo("저녁 7시 30분");
        assertThat(ShortsCaption.clock(java.time.LocalTime.of(9, 0))).isEqualTo("오전 9시");
    }
}
