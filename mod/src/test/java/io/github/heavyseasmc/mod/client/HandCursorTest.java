package io.github.heavyseasmc.mod.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 手牌一面的选中：手牌与面前两排共用一个下标（审查 2026-10-07 Z1：用户实玩报「亮出到面前的牌选不中、打不出」）。
 */
class HandCursorTest {

    @Test
    @DisplayName("从手牌最后一张往右走进面前那一排，走到面前最后一张为止")
    void rightArrowWalksIntoTheFrontRow() {
        // 手里 2 张、面前 2 张：下标 0 1 是手牌，2 3 是面前
        assertEquals(2, HandCursor.step(1, +1, 2, 2), "手牌最后一张往右：该走到面前第一张");
        assertEquals(3, HandCursor.step(2, +1, 2, 2));
        assertEquals(3, HandCursor.step(3, +1, 2, 2), "面前最后一张再往右：停住");
        assertEquals(1, HandCursor.step(2, -1, 2, 2), "面前第一张往左：回到手牌最后一张");
        assertEquals(0, HandCursor.step(0, -1, 2, 2));
    }

    @Test
    @DisplayName("手里一张都没有、面前有牌：照样选得中面前的牌")
    void frontOnlyIsSelectable() {
        assertEquals(1, HandCursor.step(0, +1, 0, 2));
        assertEquals(0, HandCursor.clamp(0, 0, 2), "空手时下标 0 指面前第一张");
        assertTrue(HandCursor.inFront(0, 0));
    }

    @Test
    @DisplayName("张数变了：下标夹回两排合起来的范围里")
    void clampKeepsIndexInBothRows() {
        assertEquals(3, HandCursor.clamp(5, 2, 2));
        assertEquals(2, HandCursor.clamp(2, 1, 2), "亮出一张之后手牌少一张、面前多一张：下标 2 仍然有效");
        assertEquals(0, HandCursor.clamp(-1, 2, 0));
        assertEquals(0, HandCursor.clamp(3, 0, 0), "两排都空：0");
    }

    @Test
    @DisplayName("选中面前的牌：第二枚按钮是「赠送」，不是「亮出」（面前的牌没有亮出这回事）")
    void frontCardHasNoReveal() {
        assertFalse(HandCursor.inFront(1, 2));
        assertEquals(HandCursor.Second.REVEAL, HandCursor.second(1, 2));
        assertTrue(HandCursor.inFront(2, 2));
        assertEquals(HandCursor.Second.GIVE, HandCursor.second(2, 2));
    }
}
