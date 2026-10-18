package io.github.heavyseasmc.mod.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandControlsLayoutTest {

    @Test
    @DisplayName("第一格永远占位：放得下时第二枚的位置只看「打出」想要多宽（Z2 ①）")
    void secondButtonNeverMovesIntoTheFirstSlot() {
        var row = HandControlsLayout.fit(600, 150, 260, 14);
        assertEquals(150, row.firstWidth());
        assertEquals(164, row.secondX(), "亮出挪进了第一格：点「原来打出的位置」就成了亮出");
        assertEquals(260, row.secondWidth());
    }

    @Test
    @DisplayName("放不下时两枚都收窄、不越界，第二枚仍在第一格之后")
    void narrowRoomShrinksBothWithinBounds() {
        for (int room : new int[]{40, 120, 240, 320, 400}) {
            var row = HandControlsLayout.fit(room, 150, 440, 14);
            assertTrue(row.firstWidth() > 0);
            assertTrue(row.secondWidth() > 0);
            assertTrue(row.secondX() >= row.firstWidth(), "第二枚压进了第一格（" + room + "）");
            assertTrue(row.secondX() + row.secondWidth() <= room, "整排越过了右边（" + room + "）");
        }
    }

    @Test
    @DisplayName("第一枚（Enter 打出）短：放不下时它先拿够想要的宽，收窄的是长的那一枚（验收 L07：Play 被压没了）")
    void shortFirstButtonKeepsItsWidth() {
        var row = HandControlsLayout.fit(400, 150, 450, 14);
        assertEquals(150, row.firstWidth(), "第一枚被等比压窄了，字放不下：" + row);
        assertEquals(400, row.secondX() + row.secondWidth());
        // 第一枚自己就比一半还宽时，至多占一半
        var wide = HandControlsLayout.fit(300, 250, 250, 14);
        assertEquals((300 - 14) / 2, wide.firstWidth());
        assertTrue(wide.secondWidth() >= wide.firstWidth());
    }

    @Test
    @DisplayName("赠给指定的人那一版只有一枚")
    void fixedRecipientGiftUsesOnlyOneBoundedButton() {
        assertEquals(new HandControlsLayout(120, 0, 0), HandControlsLayout.fit(400, 120, 0, 14));
        assertEquals(new HandControlsLayout(90, 0, 0), HandControlsLayout.fit(90, 120, 0, 14));
    }
}
