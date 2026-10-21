package com.team.blog.shorts.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;

class ColorGroupsTest {

    @Test
    void 세_편씩_같은_룩이고_세_편이_지나면_다른_룩이다() {
        assertThat(LongStream.range(0, 9).mapToObj(ColorGroups::lookFor).toList())
                .containsExactly("mint", "mint", "mint", "night", "night", "night", "sunset", "sunset", "sunset");
    }

    @Test
    void 일곱_묶음이_지나면_처음_색으로_돌아온다() {
        assertThat(ColorGroups.lookFor(3L * ColorGroups.ORDER.size())).isEqualTo("mint");
        assertThat(ColorGroups.lookFor(3L * ColorGroups.ORDER.size() - 1)).isEqualTo("paper");
    }

    @Test
    void 이웃한_묶음은_밝은_색과_어두운_색이_번갈아_온다() {
        // 렌더 워커 looks.ts의 dark 값과 같다
        java.util.Set<String> dark = java.util.Set.of("night", "mono", "ocean");
        for (int i = 0; i + 1 < ColorGroups.ORDER.size(); i++) {
            assertThat(dark.contains(ColorGroups.ORDER.get(i))).isNotEqualTo(dark.contains(ColorGroups.ORDER.get(i + 1)));
        }
    }

    @Test
    void 이미_그린_영상을_다시_그리면_그때_룩을_쓴다() {
        assertThat(RenderQueue.groupLook("ocean:2", 0)).isEqualTo("ocean");
        assertThat(RenderQueue.groupLook(null, 4)).isEqualTo("night");
        assertThat(RenderQueue.groupLook("unknown:1", 0)).isEqualTo("mint");
    }

    @Test
    void 음수_자리는_받지_않는다() {
        assertThatThrownBy(() -> ColorGroups.lookFor(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
