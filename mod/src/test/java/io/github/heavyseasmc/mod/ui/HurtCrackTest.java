package io.github.heavyseasmc.mod.ui;

import io.github.heavyseasmc.engine.state.Condition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 裂纹取档（{@link HurtCrack}）：状态取自引擎的 {@link Condition#onBoat}，不在这里另算一份。 */
class HurtCrackTest {

    private static HudPart at(int damage, int size) {
        return HurtCrack.of(Condition.onBoat(damage, size), size - damage, size);
    }

    @Test
    @DisplayName("体型 8：没伤不盖 · 1–3 轻伤 · 4–6 重伤 · 7 濒死 · 8 昏迷 · 9 死了不盖")
    void sizeEight() {
        assertNull(at(0, 8));
        assertEquals(HudPart.CRACK_LIGHT, at(1, 8));
        assertEquals(HudPart.CRACK_LIGHT, at(3, 8));
        assertEquals(HudPart.CRACK_HEAVY, at(4, 8));
        assertEquals(HudPart.CRACK_HEAVY, at(6, 8));
        assertEquals(HudPart.CRACK_DYING, at(7, 8));
        assertEquals(HudPart.CRACK_OUT, at(8, 8));
        assertNull(at(9, 8));
    }

    @Test
    @DisplayName("体型 3（最小）：1 点轻伤 · 2 点只剩 1 = 濒死，跳过重伤")
    void sizeThree() {
        assertEquals(HudPart.CRACK_LIGHT, at(1, 3));
        assertEquals(HudPart.CRACK_DYING, at(2, 3));
        assertEquals(HudPart.CRACK_OUT, at(3, 3));
    }

    @Test
    @DisplayName("体型 4：伤一半就算重伤")
    void sizeFour() {
        assertEquals(HudPart.CRACK_LIGHT, at(1, 4));
        assertEquals(HudPart.CRACK_HEAVY, at(2, 4));
        assertEquals(HudPart.CRACK_DYING, at(3, 4));
    }

    @Test
    @DisplayName("没有对局数据（体型 0）不盖")
    void noSeat() {
        assertNull(HurtCrack.of(Condition.CONSCIOUS, 0, 0));
        assertNull(HurtCrack.of(null, 3, 5));
    }
}
