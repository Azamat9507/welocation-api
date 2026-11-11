package com.minsu.welocation.guide;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GuideTextTest {

    @Test
    void nameSearchTreatsTheModelsWildcardsAsPlainText() {
        assertThat(SpotSearchTool.likePattern("50%_off\\")).isEqualTo("%50\\%\\_off\\\\%");
        assertThat(SpotSearchTool.likePattern("  ")).isNull();
        assertThat(SpotSearchTool.likePattern("가".repeat(40))).hasSize(SpotSearchTool.MAX_NAME_LENGTH + 2);
    }

    @Test
    void modelTextIsPlainAndShort() {
        assertThat(GuideService.clean("좋은\u0000 곳\u001b[31m\n입니다", 100)).isEqualTo("좋은 곳[31m\n입니다");
        assertThat(GuideService.clean("가".repeat(130), GuideService.MAX_REASON)).hasSize(GuideService.MAX_REASON + 1).endsWith("…");
        assertThat(GuideService.clean(null, 10)).isEmpty();
    }
}
