package io.github.heavyseasmc.mod.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 聊天开着时「决定还在走」那张签放在哪（2026-10-13 验收 34：挂在顶上居中，折成两行，秒数落进聊天记录的底色，签子压住座位轨）。
 */
class ChatPendingSlotTest {

    @Test
    @DisplayName("一行、贴着输入框上沿：不上顶（座位轨在顶上）、不进聊天记录、不进钉住的日志栏")
    void staysAboveInputRightOfChatHistory() {
        int[][] windows = {{427, 240}, {640, 360}, {480, 270}, {1280, 720}};
        for (int[] win : windows) {
            int w = win[0];
            int h = win[1];
            int chatRight = Math.min(328, w / 2);
            int lineH = 10;
            for (int sidebarLeft : new int[]{w, w - 150}) {
                int[] slot = DecisionNavigation.chatSlot(w, h, chatRight, sidebarLeft, 220, lineH);
                String where = w + "×" + h + " · 右栏 " + sidebarLeft;
                assertTrue(slot[1] > h / 2, "签子跑到上半屏去了（" + where + "）：会压住座位轨");
                assertTrue(slot[1] + lineH <= h - DecisionNavigation.CHAT_INPUT_H, "签子压进了输入框（" + where + "）");
                assertTrue(slot[0] >= chatRight, "签子压进了聊天记录（" + where + "）");
                assertTrue(slot[0] + slot[2] <= Math.min(w, sidebarLeft), "签子压进了右边那一栏（" + where + "）");
                assertTrue(slot[2] >= 1, "签子没有宽度（" + where + "）");
            }
        }
    }

    @Test
    @DisplayName("放得下时整句一行：宽就是这一行字的宽，靠右")
    void wideWindowKeepsWholeLine() {
        int[] slot = DecisionNavigation.chatSlot(640, 360, 328, 640, 180, 10);
        assertEquals(180, slot[2]);
        assertTrue(640 - (slot[0] + slot[2]) < 12, "没有靠右：" + slot[0]);
    }
}
