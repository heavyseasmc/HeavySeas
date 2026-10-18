package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.ui.HudLayout;
import io.github.heavyseasmc.mod.ui.HudLayout.Rect;
import io.github.heavyseasmc.mod.ui.PaneBudget;
import io.github.heavyseasmc.mod.ui.SheetLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对局界面开着、日志钉住时，界面给日志让出右栏（用户 2026-10-13，{@link PaneBudget}）。
 *
 * <p>判据分两层，与结论正交：
 * <ol>
 *   <li><b>让</b>：日志那一栏按<b>整窗</b>的 {@link HudLayout} 算（与世界视图钉住时同一处），板（连外圈）按<b>界面那一块</b>的骨架算，
 *       两者不相交；界面那一块达不到 ADR-0088 的下限时日志收起（{@code logShown} 为假、整窗给界面），而不是盖上去。</li>
 *   <li><b>排得下</b>：把界面那一块当成一扇窗口，交给各面已有的版面判据（计分 · 设置 · 补给箱牌排 · 手牌两排）再跑一遍，
 *       外加骨架上每一件（上带 · 座位轨 1–8 座的头像 / 名字 / 小圆片 · 倒计时 · 提示行）都在板里、在日志栏左边。</li>
 * </ol>
 * 窗口：1067×600 · 1280×720 · 1920×1080（用户点名的三档）再加 1366×768 · 1280×800 · 1280×1024 · 1600×900 · 2560×1440 · 3840×2160 ·
 * 带鱼屏；× 钉住 / 不钉住 × 两档阅读字号（栏宽跟着正文字号变）。
 *
 * <p>用户 2026-10-13 第二次定：界面里钉住时右栏<b>只放日志、收窄</b>，钉住时界面那一块的下限放宽到约 1000 宽（{@link PaneBudget#MIN_PINNED_PANE_W}），
 * 让 1280×720 也放得下；不钉住时下限仍是 ADR-0088 的 1067×600。
 */
final class PaneBudgetTest {

    /** {物理宽, 物理高}。 */
    private static final int[][] WINDOWS = {
            {HudLayout.MIN_WINDOW_W, HudLayout.MIN_WINDOW_H},
            {1280, 720},
            {1920, 1080},
            {1366, 768},
            {1280, 800},
            {1280, 1024},
            {1600, 900},
            {2560, 1440},
            {3840, 2160},
            {2560, 1080},
    };

    /** 用户 2026-10-13：「日志一行至少放得下约 12 个汉字」。 */
    private static final int LINE_CHARS = 12;

    /** 这一窗口、这一档阅读字号下的预算：日志正文字号照客户端的算法挑（{@link PaneWindow#logTextPx}）。 */
    static PaneBudget budget(int w, int h, boolean pinned, boolean large) {
        return PaneBudget.of(w, h, pinned, PaneWindow.logTextPx(w, h, large));
    }

    @Test
    @DisplayName("钉住时：1280×720 与 1920×1080 让出右栏，1067×600 让不出就收起并挂小签；不钉住时整窗给界面")
    void foldsWhenThePaneWouldDropBelowTheMinimum() {
        assertTrue(budget(1280, 720, true, false).logShown(), "1280×720 钉住时显示日志（用户 2026-10-13：钉住时下限放宽到约 1000）");
        assertTrue(budget(1920, 1080, true, false).logShown(), "1920×1080 放得下：日志该画在让出来的那一栏");
        assertTrue(budget(1920, 1080, true, true).logShown(), "1920×1080 大字也放得下");
        assertFalse(budget(1067, 600, true, false).logShown(), "下限窗口：该收起");
        assertTrue(PaneBudget.folded(1067, 600, true, PaneWindow.logTextPx(1067, 600, false)), "1067×600 钉住却收起：导航那一排要给小签");
        assertFalse(PaneBudget.folded(1280, 720, true, PaneWindow.logTextPx(1280, 720, false)), "放得下时不给「已收起」小签");
        assertFalse(PaneBudget.folded(1067, 600, false, PaneWindow.logTextPx(1067, 600, false)), "没钉住时不给「已收起」小签");
        for (int[] w : WINDOWS) {
            for (boolean large : new boolean[]{false, true}) {
                PaneBudget off = budget(w[0], w[1], false, large);
                assertEquals(w[0], off.paneWidth(), "没钉住时界面就是整窗");
                assertFalse(off.logShown());
            }
        }
    }

    @Test
    @DisplayName("钉住时的下限：界面那一块恰好 1000 宽让、999 宽收；高照旧 600；整窗仍先过 1067×600")
    void pinnedPaneMinimumIsExactlyTheConstant() {
        int min = PaneBudget.MIN_PINNED_PANE_W;
        assertTrue(PaneBudget.supportsPane(min, HudLayout.MIN_WINDOW_H));
        assertFalse(PaneBudget.supportsPane(min - 1, HudLayout.MIN_WINDOW_H));
        assertFalse(PaneBudget.supportsPane(min, HudLayout.MIN_WINDOW_H - 1));
        assertTrue(min < HudLayout.MIN_WINDOW_W, "钉住时的下限应比 ADR-0088 的 1067 宽松，否则 1280×720 一钉就收起");
        // 找一扇让出来恰好落在下限上的窗口：边界两侧各一像素
        int at = -1;
        for (int w = HudLayout.MIN_WINDOW_W; w <= 1600 && at < 0; w++) {
            if (PaneBudget.logLeft(w, 720, PaneWindow.logTextPx(w, 720, false)) >= min) {
                at = w;
            }
        }
        assertTrue(at > HudLayout.MIN_WINDOW_W, "找不到让得出的窗口");
        assertTrue(budget(at, 720, true, false).logShown(), at + "×720：界面那一块到了下限，该让");
        assertFalse(budget(at - 1, 720, true, false).logShown(), (at - 1) + "×720：界面那一块差一点，该收");
    }

    @Test
    @DisplayName("日志那一栏：正文一行放得下 12 个汉字（两档字号），比世界视图那一列窄，1280×720 时在 220–300 物理像素之间")
    void logColumnIsNarrowButHoldsALine() {
        List<String> p = new ArrayList<>();
        int shown = 0;
        for (int[] w : WINDOWS) {
            HudLayout hud = HudLayout.of(w[0], w[1]);
            for (boolean large : new boolean[]{false, true}) {
                PaneBudget b = budget(w[0], w[1], true, large);
                if (!b.logShown()) {
                    continue;
                }
                shown++;
                int textPx = PaneWindow.logTextPx(w[0], w[1], large);
                // 与 GameHud#drawLogIn 同一套内边距：正文框 = 栏宽 − 左右内边距 − 未读点那一格
                int textBox = b.logWidth() - 2 * hud.len(HudLayout.LOG_PAD_X) - hud.len(PaneBudget.LOG_TEXT_INDENT);
                String at = w[0] + "×" + w[1] + (large ? " 大字" : " 常规") + "：";
                if (textBox < LINE_CHARS * textPx) {   // 用户要的数写死在这里，不取被测对象的常量（与结论正交）
                    p.add(at + "正文框 " + textBox + " 放不下 " + LINE_CHARS + " 个 " + textPx + " px 的字");
                }
                if (b.logWidth() >= hud.len(HudLayout.DRAWER_W)) {
                    p.add(at + "栏宽 " + b.logWidth() + " 不比世界视图那一列（" + hud.len(HudLayout.DRAWER_W) + "）窄");
                }
                if (b.logColumn().right() + hud.len(HudLayout.DOCK_RIGHT) != w[0]) {
                    p.add(at + "栏没贴着窗口右沿留边：右沿 " + b.logColumn().right());
                }
            }
        }
        int at1280 = budget(1280, 720, true, false).logWidth();
        assertTrue(at1280 >= 220 && at1280 <= 300, "1280×720 常规字号的栏宽 " + at1280);
        assertTrue(shown >= 10, "只有 " + shown + " 种让出了日志栏 —— 没在查");
        assertEquals(List.of(), p, String.join("\n", p));
    }

    @Test
    @DisplayName("让出来时：板（连外圈）与日志栏不相交、日志栏在窗口里、界面那一块不低于 ADR-0088 的下限")
    void sheetAndLogNeverMeet() {
        List<String> p = new ArrayList<>();
        int shown = 0;
        for (int[] w : WINDOWS) {
          for (boolean large : new boolean[]{false, true}) {
            int textPx = PaneWindow.logTextPx(w[0], w[1], large);
            PaneBudget b = budget(w[0], w[1], true, large);
            String at = w[0] + "×" + w[1] + (large ? " 大字" : "") + "：";
            if (!b.logShown()) {
                assertEquals(w[0], b.paneWidth(), at + "收起时界面要拿回整窗");
                if (HudLayout.supportsWindow(w[0], w[1])
                        && PaneBudget.supportsPane(PaneBudget.logLeft(w[0], w[1], textPx), w[1])) {
                    p.add(at + "放得下却收起了");
                }
                continue;
            }
            shown++;
            if (!PaneBudget.supportsPane(b.paneWidth(), b.height())) {
                p.add(at + "界面那一块 " + b.paneWidth() + "×" + b.height() + " 低于钉住时的下限却让了");
            }
            Rect log = b.logColumn();
            Rect window = new Rect(0, 0, w[0], w[1]);
            if (!window.contains(log)) {
                p.add(at + "日志栏 " + log + " 出了窗口");
            }
            SheetLayout l = b.sheet();
            Rect sheet = l.sheet().grow((int) Math.ceil(SheetLayout.SHEET_RING * l.k()));
            if (sheet.intersects(log)) {
                p.add(at + "板（连外圈）" + sheet + " 压到日志栏 " + log);
            }
          }
        }
        assertTrue(shown >= 8, "只有 " + shown + " 档窗口让出了日志栏 —— 没在查");
        assertEquals(List.of(), p, String.join("\n", p));
    }

    @Test
    @DisplayName("各面在界面那一块里排得下：骨架每一件在板里、在日志栏左边；计分 · 设置 · 补给箱 · 手牌照各自的判据")
    void everyFaceFitsTheNarrowedPane() {
        List<String> p = new ArrayList<>();
        int cases = 0;
        for (int[] w : WINDOWS) {
            for (int mode = 0; mode < 3; mode++) {
                boolean pinned = mode > 0;
                PaneBudget b = budget(w[0], w[1], pinned, mode == 2);
                int[] pane = {b.paneWidth(), b.height()};
                String at = w[0] + "×" + w[1] + (pinned ? " 钉住" + (mode == 2 ? "（大字）" : "") : " 不钉住")
                        + (b.logShown() ? "（让出 → " + pane[0] + "）" : "") + "：";
                int logLeft = b.logColumn().x();
                SheetLayout l = b.sheet();
                Rect sheet = l.sheet();
                List<Rect> parts = new ArrayList<>();
                for (int i = 0; i < SheetLayout.PHASES; i++) parts.add(l.phase(i));
                for (int i = 0; i < SheetLayout.GULLS; i++) parts.add(l.gull(i));
                for (int n = 1; n <= 8; n++) {
                    for (int i = 0; i < n; i++) {
                        parts.add(l.seatToken(i, n));
                        parts.add(l.seatName(i, n));
                        parts.add(l.seatChip(i, n));
                        parts.add(l.seatCell(i, n));
                    }
                }
                parts.add(l.countBar());
                parts.add(l.countSeconds());
                parts.add(new Rect(sheet.x(), l.hintsTop(), sheet.w(), (int) Math.round(SheetLayout.HINTS_H * l.k())));
                for (Rect r : parts) {
                    if (!sheet.contains(r)) {
                        p.add(at + "骨架一件 " + r + " 出了板 " + sheet);
                    }
                    if (r.right() > logLeft) {
                        p.add(at + "骨架一件 " + r + " 伸进日志栏（左沿 " + logLeft + "）");
                    }
                }
                for (boolean large : new boolean[]{false, true}) {
                    for (int people = 4; people <= 8; people++) {
                        for (String s : ScoreLayoutTest.check(pane, large, people)) p.add(at + s);
                        cases++;
                    }
                }
                for (int categories : new int[]{1, 6, 12}) {
                    for (int rows : new int[]{0, 3, 40}) {
                        for (String s : SettingsLayoutTest.check(pane, categories, rows)) p.add(at + s);
                        cases++;
                    }
                }
                cases += CardRowTest.provisionProblems(pane[0], pane[1], at + "补给箱", p);
                for (int gui = 2; gui <= 4; gui++) {
                    if (w[0] / gui >= 320 && w[1] / gui >= 240) {
                        CardRowTest.handRowProblems(pane[0], pane[1], gui, at + "手牌 @" + gui, p);
                        cases++;
                    }
                }
            }
        }
        assertTrue(cases >= 500, "只核了 " + cases + " 种 —— 没在核");
        System.out.println("界面让出日志栏：核了 " + cases + " 种窗口 × 钉住 × 面");
        assertEquals(List.of(), p.stream().limit(30).toList(), String.join("\n", p.stream().limit(30).toList()));
    }
}
