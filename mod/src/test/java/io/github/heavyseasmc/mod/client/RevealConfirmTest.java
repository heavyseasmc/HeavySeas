package io.github.heavyseasmc.mod.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 亮出不可逆，所以要按两下（审查 2026-10-07 Z2）：第一下只把按钮变成「再按一次亮出」，3 秒内对同一张再按才发包。
 */
class RevealConfirmTest {

    @Test
    @DisplayName("第一下只是待确认，3 秒内对同一张再按一下才亮出")
    void twoPressesWithinTheWindowFire() {
        RevealConfirm r = new RevealConfirm();
        assertEquals(RevealConfirm.Result.ARMED, r.press("parasol", 1, 1_000L), "第一下就亮出了 —— 一碰即亮，误触收不回来");
        assertTrue(r.armed("parasol", 1, 1_500L));
        assertEquals(RevealConfirm.Result.FIRE, r.press("parasol", 1, 3_900L));
        assertFalse(r.armed("parasol", 1, 3_900L), "亮出之后不再是待确认");
    }

    @Test
    @DisplayName("超过 3 秒、换了一张、撤销过：第二下都只是重新待确认")
    void anythingElseStartsOver() {
        RevealConfirm r = new RevealConfirm();
        r.press("parasol", 1, 0L);
        assertFalse(r.armed("parasol", 1, RevealConfirm.WINDOW_MS + 1), "过了 3 秒还亮着「再按一次」");
        assertEquals(RevealConfirm.Result.ARMED, r.press("parasol", 1, RevealConfirm.WINDOW_MS + 1));

        r.cancel();
        assertEquals(RevealConfirm.Result.ARMED, r.press("parasol", 1, 10_000L), "撤销之后的第一下还是第一下");
        assertEquals(RevealConfirm.Result.ARMED, r.press("water", 1, 10_100L), "换了牌：从头来");
        assertEquals(RevealConfirm.Result.ARMED, r.press("water", 2, 10_200L), "同名的另一张（下标变了）：也从头来");
        assertFalse(r.armed("water", 1, 10_200L));
    }

    @Test
    @DisplayName("亮出之后紧跟的那一下（连按）不算：既不待确认，也不让「打出」接手")
    void pressRightAfterFiringIsIgnored() {
        RevealConfirm r = new RevealConfirm();
        r.press("oar", 0, 0L);
        r.press("oar", 0, 200L);
        assertTrue(r.coolingDown(200L + RevealConfirm.AFTER_MS - 1));
        assertEquals(RevealConfirm.Result.IGNORED, r.press("oar", 0, 300L));
        assertFalse(r.coolingDown(200L + RevealConfirm.AFTER_MS));
    }
}
