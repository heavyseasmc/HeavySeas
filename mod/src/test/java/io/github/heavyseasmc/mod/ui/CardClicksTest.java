package io.github.heavyseasmc.mod.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 点牌：单击只选中，双击才打出（用户 2026-10-13）。 */
class CardClicksTest {

    @Test
    @DisplayName("单击一张牌只选中，不打出")
    void singleClickOnlySelects() {
        CardClicks clicks = new CardClicks();
        assertEquals(CardClicks.Result.SELECT, clicks.click(2, 1_000), "单击就打出：验收实拍到的那个毛病");
    }

    @Test
    @DisplayName("同一张上两下、相隔不到 250 ms：打出；第三下不连着算")
    void doubleClickPlays() {
        CardClicks clicks = new CardClicks();
        clicks.click(2, 1_000);
        assertEquals(CardClicks.Result.PLAY, clicks.click(2, 1_200));
        assertEquals(CardClicks.Result.SELECT, clicks.click(2, 1_300), "第三下又打出一张：连点三下花掉两张牌");
    }

    @Test
    @DisplayName("隔得太久、或第二下点在别的牌上：都只算重新选中")
    void slowOrDifferentClickSelects() {
        CardClicks clicks = new CardClicks();
        clicks.click(2, 1_000);
        assertEquals(CardClicks.Result.SELECT, clicks.click(2, 1_000 + CardClicks.DOUBLE_CLICK_MS));
        clicks.reset();
        clicks.click(1, 2_000);
        assertEquals(CardClicks.Result.SELECT, clicks.click(2, 2_100), "点了两张不同的牌却打出了第二张");
        assertEquals(CardClicks.Result.PLAY, clicks.click(2, 2_200));
    }

    @Test
    @DisplayName("中间换过选中（方向键）：上一下作废")
    void resetForgetsTheFirstClick() {
        CardClicks clicks = new CardClicks();
        clicks.click(0, 1_000);
        clicks.reset();
        assertEquals(CardClicks.Result.SELECT, clicks.click(0, 1_100));
    }
}
