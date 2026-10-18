package io.github.heavyseasmc.mod.ui;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-10-13 验收 E02：高亮第 1 张，查看态堆顶却是第 2 张。 */
class DrawOrderTest {

    @Test
    void highlightedCardIsDrawnLastSoItTopsTheStack() {
        for (int n = 1; n <= 6; n++) {
            for (int h = 0; h < n; h++) {
                int[] order = DrawOrder.highlightLast(n, h);
                assertEquals(h, order[n - 1], "高亮 " + h + "（共 " + n + " 张）不在堆顶：" + Arrays.toString(order));
                assertArrayEquals(java.util.stream.IntStream.range(0, n).toArray(),
                        Arrays.stream(order).sorted().toArray(), "每张画一次");
            }
        }
    }

    @Test
    void noHighlightDrawsEveryCardInOrder() {
        assertArrayEquals(new int[]{0, 1, 2}, DrawOrder.highlightLast(3, -1));
        assertArrayEquals(new int[]{}, DrawOrder.highlightLast(0, 0));
    }
}
