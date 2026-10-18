package io.github.heavyseasmc.mod.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.heavyseasmc.mod.ui.HudLayout.Rect;
import io.github.heavyseasmc.mod.ui.PaneBudget;
import io.github.heavyseasmc.mod.ui.SheetLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「选一个人」那一排（医疗箱 · 赠送 · 落海）两头离板框留出空（2026-10-13 总截图 09：钉住日志时医疗箱那一排最右的「小孩」贴着板右框）。
 *
 * <p>板的木框加铜圈约 33 稿子像素（总截图 1280×720 量：板外沿到纸面约 22 物理像素，k ≈ 0.67）；
 * 头像外沿离纸面边至少再留 20 —— 期望值按这两个量写死，不取被测对象的 {@code SIDE_ROOM}。
 */
class PortraitPickLayoutTest {

    private static final double FRAME = 33;
    private static final double BREATH = 20;
    private static final int[][] WINDOWS = {{1067, 600}, {1280, 720}, {1366, 768}, {1600, 900}, {1920, 1080}, {2560, 1440}};
    /** 医疗箱（头像 96 · 一格 132）与赠送 / 落海（80 · 112）。 */
    private static final double[][] KINDS = {{96, 132}, {80, 112}};

    @Test
    @DisplayName("一排人两头不贴板框：各档窗口 × 钉住 / 不钉住 × 1–8 人")
    void rowKeepsClearOfTheFrame() {
        int checked = 0;
        for (int[] w : WINDOWS) {
            for (boolean pinned : new boolean[]{false, true}) {
                PaneBudget b = PaneBudget.of(w[0], w[1], pinned, 16);
                SheetLayout l = b.sheet();
                Rect s = l.sheet();
                int margin = (int) Math.floor((FRAME + BREATH) * l.k());
                for (double[] kind : KINDS) {
                    for (int n = 1; n <= 8; n++) {
                        var g = PortraitPick.layout(l, n, s.y() + 200 * l.k(), kind[0], kind[1], kind[0] == 96, 0);
                        int lastRight = g.x()[n - 1] + (g.col() + g.d()) / 2;
                        String at = w[0] + "×" + w[1] + (pinned ? " 钉住" : "") + " 头像 " + kind[0] + " × " + n
                                + "：板 " + s + " · 牌左 " + g.cardX() + " · 最右头像右沿 " + lastRight + " · 要留 " + margin;
                        assertTrue(lastRight <= s.right() - margin, "最右头像贴着板右框：" + at);
                        assertTrue(g.cardX() >= s.x() + margin, "左边那张牌贴着板左框：" + at);
                        checked++;
                    }
                }
            }
        }
        assertTrue(checked > 100, "只核了 " + checked + " 种");
    }
}
