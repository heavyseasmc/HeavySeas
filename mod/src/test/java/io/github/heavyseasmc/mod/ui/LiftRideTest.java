package io.github.heavyseasmc.mod.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 坐电梯时画面的曲线（{@link LiftRide}）：渐暗 · 保持 · 到站后亮回来；只在走的那一段晃。 */
final class LiftRideTest {

    private static final int RIDE = 60;     // 三层

    @Test
    @DisplayName("渐暗：出发时 0、半秒后最暗、整趟保持、到站（换层那一刻）仍是最暗、再过半秒亮回来")
    void dimCoversTheWholeRide() {
        assertEquals(0f, LiftRide.dim(0, RIDE));
        assertEquals(LiftRide.MAX_DIM / 2, LiftRide.dim(LiftRide.FADE_TICKS / 2.0, RIDE), 1e-6);
        assertEquals(LiftRide.MAX_DIM, LiftRide.dim(LiftRide.FADE_TICKS, RIDE), 1e-6);
        assertEquals(LiftRide.MAX_DIM, LiftRide.dim(RIDE, RIDE), 1e-6, "换层那一刻最暗");
        assertEquals(LiftRide.MAX_DIM / 2, LiftRide.dim(RIDE + LiftRide.FADE_TICKS / 2.0, RIDE), 1e-6);
        assertEquals(0f, LiftRide.dim(RIDE + LiftRide.FADE_TICKS, RIDE));
        assertTrue(LiftRide.MAX_DIM < 0.6f, "半暗，不是黑屏");
        assertFalse(LiftRide.over(RIDE + LiftRide.FADE_TICKS - 0.01, RIDE));
        assertTrue(LiftRide.over(RIDE + LiftRide.FADE_TICKS, RIDE));
    }

    @Test
    @DisplayName("轻晃：只在走的那一段，幅度不超过 BOB_BLOCKS，而且真的在晃（有正有负）")
    void bobOnlyWhileMoving() {
        assertEquals(0, LiftRide.bob(0, RIDE));
        assertEquals(0, LiftRide.bob(RIDE, RIDE), "到站之后不晃");
        assertEquals(0, LiftRide.bob(RIDE + 5, RIDE));
        double lo = 0;
        double hi = 0;
        for (double t = 0; t <= RIDE; t += 0.25) {
            double b = LiftRide.bob(t, RIDE);
            assertTrue(Math.abs(b) <= LiftRide.BOB_BLOCKS + 1e-12, "t " + t + "：" + b);
            lo = Math.min(lo, b);
            hi = Math.max(hi, b);
        }
        assertTrue(lo < -LiftRide.BOB_BLOCKS / 2 && hi > LiftRide.BOB_BLOCKS / 2, "晃得出来：" + lo + " · " + hi);
    }
}
