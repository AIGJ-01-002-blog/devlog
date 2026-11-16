package com.team.blog.shorts.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.team.blog.shorts.application.ShortsScript.Scene;

/** 이미 저장된 "루프" 대본도 워커에는 끝 문장을 완결해 넘긴다(2026-11-16) */
class RenderQueueJobScenesTest {

    @Test
    void 마지막으로_말하는_장면만_끝_문장을_완결한다() {
        List<Scene> scenes = List.of(new Scene("a", "34건, 대부분 오탐이었어요.", null),
                new Scene("b", "전체 코드는 블로그 글에서 보세요. 처음 돌렸을 땐 저도,", null),
                new Scene("c", "", null));
        List<RenderQueue.JobScene> job = RenderQueue.jobScenes(scenes);
        assertThat(job).extracting(RenderQueue.JobScene::narration)
                .containsExactly("34건, 대부분 오탐이었어요.", "전체 코드는 블로그 글에서 보세요.", "");
    }
}
