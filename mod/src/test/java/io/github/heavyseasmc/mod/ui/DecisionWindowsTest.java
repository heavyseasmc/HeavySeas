package io.github.heavyseasmc.mod.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 窗口重置要再弹（2026-10-13 验收：试合入那一版重置后不再弹）。 */
class DecisionWindowsTest {

    private static final String MATCH = "game-one";
    private static final String STANCE = "game-one/3/ACTION/contest/STEAL/captain/mate/STANCES";

    @Test
    @DisplayName("同一个决定，有人加入把站队窗口重置（截止时刻变了）：算新窗口，再弹一次")
    void resetWindowPopsAgain() {
        String first = DecisionWindows.key(MATCH, STANCE, 115_000);
        String reset = DecisionWindows.key(MATCH, STANCE, 108_000 + 15_000);
        assertTrue(DecisionWindows.fresh(STANCE, first, ""), "第一次都不弹");
        assertTrue(DecisionWindows.fresh(STANCE, reset, first), "窗口重置了却当成同一扇：局面变了没人再问一次");
    }

    @Test
    @DisplayName("同一扇窗（决定与截止时刻都没变）：不重复弹")
    void sameWindowDoesNotPopTwice() {
        String key = DecisionWindows.key(MATCH, STANCE, 115_000);
        assertFalse(DecisionWindows.fresh(STANCE, key, key));
    }

    @Test
    @DisplayName("没有欠我的决定（答过了 · 轮不到我）：截止时刻怎么变都不弹")
    void noDecisionNeverPops() {
        assertFalse(DecisionWindows.fresh("", DecisionWindows.key(MATCH, "", 99_000), ""));
    }

    @Test
    @DisplayName("换了一局、同样的编号与时刻：也是新窗口")
    void anotherMatchIsAnotherWindow() {
        String a = DecisionWindows.key("game-one", STANCE, 115_000);
        String b = DecisionWindows.key("game-two", STANCE, 115_000);
        assertTrue(DecisionWindows.fresh(STANCE, b, a));
    }
}
