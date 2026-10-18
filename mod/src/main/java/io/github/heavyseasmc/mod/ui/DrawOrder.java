package io.github.heavyseasmc.mod.ui;

/**
 * 一排牌按什么次序画：高亮那一张最后画。查看态里牌收成一叠，最后画的那张就是堆顶 —— 它必须是签子上说的那一张。
 *
 * <p>2026-10-13 验收 E02：划船一面按下标画，最后一张永远在堆顶；高亮第 1 张时，堆顶是第 2 张、签子说的是第 1 张。
 * 舵手一面同一个写法。补给箱、押武器、选牌几面本来就把高亮放在最后画，这里把那条约定收成一处。
 */
public final class DrawOrder {

    private DrawOrder() {
    }

    /** {@code n} 张、高亮第 {@code highlight} 张（越界 = 没有高亮）时的画法次序。 */
    public static int[] highlightLast(int n, int highlight) {
        int[] order = new int[Math.max(0, n)];
        int at = 0;
        for (int i = 0; i < n; i++) {
            if (i != highlight) {
                order[at++] = i;
            }
        }
        if (highlight >= 0 && highlight < n) {
            order[at] = highlight;
        }
        return order;
    }
}
