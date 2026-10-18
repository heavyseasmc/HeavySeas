package io.github.heavyseasmc.mod.ui;

import io.github.heavyseasmc.mod.ui.HudLayout.Rect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 「昏」签只在头像方框里挪（{@link SeatTag}）。 */
final class SeatTagTest {

    @Test
    @DisplayName("2026-10-13 第二轮验收 50：印章顶边恰好落在头像正中那一行时，签挪到印章上面，不伸出头像下沿（座位轨下沿）")
    void sealStartingAtTheMiddleSendsTheTagUp() {
        Rect token = new Rect(100, 26, 40, 40);
        Rect seal = new Rect(114, 46, 45, 20);          // 印章底贴着头像底、顶边在 26 + 20
        Rect tag = SeatTag.place(token, seal, 24, 17);
        assertFalse(tag.intersects(seal), "签压着印章：" + tag);
        assertTrue(tag.y() >= token.y() && tag.bottom() <= token.bottom(), "签出了头像方框：" + tag);
        assertEquals(46 - 1 - 17, tag.y(), "挪到印章上面");
    }

    @Test
    @DisplayName("不相交就在正中；上下都放不下时贴着头像顶，仍不出方框")
    void staysInsideTheToken() {
        Rect token = new Rect(0, 0, 40, 40);
        assertEquals(new Rect(8, 11, 24, 17), SeatTag.place(token, new Rect(30, 30, 10, 5), 24, 17), "不相交：正中");
        Rect cramped = SeatTag.place(token, new Rect(0, 10, 40, 20), 24, 17);
        assertTrue(cramped.y() >= 0 && cramped.bottom() <= 40, "上下都放不下也不出方框：" + cramped);
        Rect high = SeatTag.place(token, new Rect(20, 0, 20, 12), 24, 17);
        assertFalse(high.intersects(new Rect(20, 0, 20, 12)), "印章在上半：签挪到下面");
        assertTrue(high.bottom() <= 40);
    }
}
