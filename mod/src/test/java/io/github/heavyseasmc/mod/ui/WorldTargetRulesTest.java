package io.github.heavyseasmc.mod.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldTargetRulesTest {
    @Test
    void overlappedSeatsCanBeReachedInEitherDirectionWithoutMovingTheCamera() {
        assertEquals(7, WorldTargetRules.cycle(0, 8, 1));
        assertEquals(0, WorldTargetRules.cycle(7, 8, -1));
        assertEquals(-1, WorldTargetRules.cycle(0, 0, 1));
    }

    /** 2026-10-13 验收 16/17：Ctrl+滚轮换到坐在镜头外的人，描边跟过去了却看不见。 */
    @Test
    void switchingPersonStaysAmongPeopleOnScreenLeftToRight() {
        // 座位次序：0 在右 20°，1 在身后，2 在左 30°，3 在正前方，4 在右 80°（画面外）
        double[] bearing = {20, 170, -30, 0, 80};
        double half = 45;
        // 从正前方（3）往下滚 = 往右：到 0；再往右接回最左的 2；从来不落到 1、4
        assertEquals(0, WorldTargetRules.cycleOnScreen(bearing, 3, half, -1));
        assertEquals(2, WorldTargetRules.cycleOnScreen(bearing, 0, half, -1));
        assertEquals(3, WorldTargetRules.cycleOnScreen(bearing, 2, half, -1));
        assertEquals(2, WorldTargetRules.cycleOnScreen(bearing, 3, half, 1));
        int at = 3;
        for (int k = 0; k < 10; k++) {
            at = WorldTargetRules.cycleOnScreen(bearing, at, half, k % 3 == 0 ? 1 : -1);
            assertTrue(Math.abs(bearing[at]) <= half, "换到了画面外的第 " + at + " 个");
        }
        // 现在选的人不在画面里：落到离准星最近的那个
        assertEquals(3, WorldTargetRules.cycleOnScreen(bearing, 1, half, -1));
        // 画面里一个人都没有：不动
        assertEquals(1, WorldTargetRules.cycleOnScreen(new double[]{100, 170}, 1, half, -1));
    }
}
