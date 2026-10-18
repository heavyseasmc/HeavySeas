package io.github.heavyseasmc.mod.ui;

import io.github.heavyseasmc.mod.net.LiftFloorsS2C;
import net.minecraft.text.TranslatableTextContent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 电梯楼层面板的按钮（{@link LiftPanel}）：楼层表 → 一列按钮、焦点、数字键、楼层名。 */
final class LiftPanelTest {

    /** E 甲板 · D 甲板 · 一扇手放的（none）· 艇甲板，从低到高；人在 D 甲板（第 1 层）。 */
    private static final List<LiftFloorsS2C.Stop> STOPS = List.of(new LiftFloorsS2C.Stop(10, "e"), new LiftFloorsS2C.Stop(20, "d"),
            new LiftFloorsS2C.Stop(30, "none"), new LiftFloorsS2C.Stop(40, "boat"));

    @Test
    @DisplayName("按钮从上到下 = 楼层从高到低；当前层标出来；数字键 n = 从下往上第 n 层")
    void buttonsAreTopDown() {
        List<LiftPanel.Button> b = LiftPanel.buttons(STOPS, 1);
        assertEquals(List.of(new LiftPanel.Button(3, 40, "boat", false, 4), new LiftPanel.Button(2, 30, "none", false, 3),
                new LiftPanel.Button(1, 20, "d", true, 2), new LiftPanel.Button(0, 10, "e", false, 1)), b);
    }

    @Test
    @DisplayName("数字键：按到当前层是 -1、没有那一层是 -1；第 10 层起没有数字键")
    void digits() {
        List<LiftPanel.Button> b = LiftPanel.buttons(STOPS, 1);
        assertEquals(0, LiftPanel.byDigit(b, 4));
        assertEquals(3, LiftPanel.byDigit(b, 1));
        assertEquals(-1, LiftPanel.byDigit(b, 2), "当前层按不动");
        assertEquals(-1, LiftPanel.byDigit(b, 5));
        assertEquals(-1, LiftPanel.byDigit(b, 0));
        List<LiftFloorsS2C.Stop> tall = new java.util.ArrayList<>();
        for (int i = 0; i < 11; i++) {
            tall.add(new LiftFloorsS2C.Stop(i * 4, "none"));
        }
        List<LiftPanel.Button> many = LiftPanel.buttons(tall, 0);
        assertEquals(0, many.get(0).digit(), "第 11 层没有数字键");
        assertEquals(9, many.get(2).digit());
    }

    @Test
    @DisplayName("焦点：开面板时落在当前层上面那一颗；在最高层时落在下面那一颗；方向键跳过当前层、到头停住")
    void focus() {
        List<LiftPanel.Button> b = LiftPanel.buttons(STOPS, 1);
        assertEquals(1, LiftPanel.initialFocus(b));
        assertEquals(1, LiftPanel.initialFocus(LiftPanel.buttons(STOPS, 3)));
        assertEquals(3, LiftPanel.move(b, 1, 1), "往下跳过当前层（下标 2）");
        assertEquals(1, LiftPanel.move(b, 3, -1));
        assertEquals(0, LiftPanel.move(b, 0, -1), "到顶停住");
        assertEquals(3, LiftPanel.move(b, 3, 1), "到底停住");
    }

    @Test
    @DisplayName("楼层名：写明了甲板就用甲板名，none 与认不出的照位置编号（从 1 数）")
    void labels() {
        assertEquals("heavyseas.lift.deck.boat", key(LiftPanel.label("boat", 3)));
        TranslatableTextContent n = (TranslatableTextContent) LiftPanel.label("none", 2).getContent();
        assertEquals("heavyseas.lift.floor", n.getKey());
        assertEquals(3, n.getArgs()[0]);
        assertEquals("heavyseas.lift.floor", key(LiftPanel.label("E.bad", 0)), "认不出的名字不拼成键");
    }

    private static String key(net.minecraft.text.Text t) {
        return ((TranslatableTextContent) t.getContent()).getKey();
    }
}
