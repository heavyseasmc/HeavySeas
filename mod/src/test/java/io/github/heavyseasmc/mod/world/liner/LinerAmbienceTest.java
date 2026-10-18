package io.github.heavyseasmc.mod.world.liner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 落地钟报时（ADR-0109 ②）：游戏刻 → 时:分。刻 0 是早上 6 点；跨天、负数（拨钟拨回去）都要落在 0–24 点里。 */
class LinerAmbienceTest {

    @Test
    @DisplayName("落地钟按游戏刻报时：0 刻 6 点，6000 刻正午，18000 刻午夜，跨天照算")
    void theClockReadsTheTimeOfDay() {
        assertEquals("06:00", LinerAmbience.clock(0));
        assertEquals("12:00", LinerAmbience.clock(6000));
        assertEquals("00:00", LinerAmbience.clock(18000));
        assertEquals("05:59", LinerAmbience.clock(23999));
        assertEquals("23:30", LinerAmbience.clock(17500));
        assertEquals("06:00", LinerAmbience.clock(24000 * 5));
        assertEquals("05:00", LinerAmbience.clock(-1000));
    }
}
