package io.github.heavyseasmc.mod.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 决策面弹出来之后的确认键（审查 2026-10-07 U1）：聊天里敲下的那个空格、按住回车的系统重复，都不许替玩家选。
 */
class ConfirmGateTest {

    private static final int ENTER = GLFW.GLFW_KEY_ENTER;
    private static final int SPACE = GLFW.GLFW_KEY_SPACE;
    private static final int LEFT = GLFW.GLFW_KEY_LEFT;

    @Test
    @DisplayName("弹出后 300 ms 内的确认键不算；过了就算")
    void graceAfterShown() {
        ConfirmGate g = new ConfirmGate();
        g.shown(1_000L, key -> false);
        assertFalse(g.press(SPACE, 1_000L + ConfirmGate.GRACE_MS - 1), "刚弹出来的那个空格替你选了");
        g.release(SPACE);
        assertTrue(g.press(SPACE, 1_000L + ConfirmGate.GRACE_MS));
        assertTrue(g.press(LEFT, 1_000L), "方向键不受宽限：它不替你决定任何事");
    }

    @Test
    @DisplayName("按住回车：系统的重复不算新的一下，松开再按才算")
    void heldKeyRepeatsDoNotCount() {
        ConfirmGate g = new ConfirmGate();
        g.shown(0L, key -> false);
        assertTrue(g.press(ENTER, 1_000L));
        assertFalse(g.press(ENTER, 1_500L), "按住时的系统重复被当成了第二下");
        g.release(ENTER);
        assertTrue(g.press(ENTER, 2_000L));
        assertTrue(g.press(LEFT, 2_000L));
        assertTrue(g.press(LEFT, 2_030L), "方向键按住连走照旧");
    }

    @Test
    @DisplayName("弹出之前就按着的确认键：松开之前一律不算")
    void keyHeldBeforeShownIsIgnoredUntilReleased() {
        ConfirmGate g = new ConfirmGate();
        g.shown(0L, key -> key == ENTER);
        assertFalse(g.press(ENTER, 5_000L), "聊天里按下的那个回车，按住的重复落到了新弹出的面上");
        g.release(ENTER);
        assertTrue(g.press(ENTER, 5_100L));
    }

    @Test
    @DisplayName("回到这一面（二级页面关掉）：重新看一次哪些键按着，没收到的松开不会让键一直失灵")
    void reshownResetsTheKeysSeenDown() {
        ConfirmGate g = new ConfirmGate();
        g.shown(0L, key -> false);
        assertTrue(g.press(ENTER, 1_000L));         // 这一下打开了二级页面，松开落在那一面上
        g.shown(2_000L, key -> false);               // 回到这一面
        assertTrue(g.press(ENTER, 3_000L), "回来之后第一下回车被当成了按住的重复");
    }
}
