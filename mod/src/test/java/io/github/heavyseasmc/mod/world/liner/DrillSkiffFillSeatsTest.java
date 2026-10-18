package io.github.heavyseasmc.mod.world.liner;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 演习艇人不够 6 个时替身补位（服务端设置 {@code stand_ins.fill_empty_seats}，ADR-0099 §2.4）：开着时 1–5 人补到 6，
 * 关着（默认）时与原先一样 —— 不到 6 人开不了局。6–8 人两种都不动。
 */
final class DrillSkiffFillSeatsTest {

    @Test
    @DisplayName("关着（默认）：人数原样，不到 6 人由调用方拦下")
    void offKeepsTheSeatedCount() {
        for (int seated = 0; seated <= 8; seated++) {
            assertEquals(seated, DrillSkiff.voyageSize(seated, false));
        }
    }

    @Test
    @DisplayName("开着：1–5 人补到 6，6–8 人不动，0 人不开")
    void onFillsUpToSix() {
        assertEquals(0, DrillSkiff.voyageSize(0, true), "没人坐着：没有谁来开这一局");
        for (int seated = 1; seated <= 5; seated++) {
            assertEquals(6, DrillSkiff.voyageSize(seated, true));
        }
        for (int seated = 6; seated <= 8; seated++) {
            assertEquals(seated, DrillSkiff.voyageSize(seated, true));
        }
    }
}
