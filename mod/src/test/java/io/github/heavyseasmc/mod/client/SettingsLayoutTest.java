package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.ui.HudLayout;
import io.github.heavyseasmc.mod.ui.HudLayout.Rect;
import io.github.heavyseasmc.mod.ui.SettingsLayout;
import io.github.heavyseasmc.mod.ui.SettingsMenuState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设置菜单的版面在各种窗口下（ADR-0099 版式 A）：不重叠 · 不出界 · 字的格子放得下那一级字 · 说明签放得下（放不下就折行少给）·
 * 选中的签与行留在视野里。行高按<b>真实的字号梯子</b>算（{@link GuiText#linePxAt}，与画字同一个函数）——
 * 小窗口下字号有地板，按比例估的行高会偏矮（证伪表「版面写死 GUI 单位的上限」）。
 *
 * <p>窗口按「界面尺寸 × GUI 宽高」给：320×240、427×240、640×360、960×540 这几种 GUI 尺寸在常见的界面尺寸下各是多少物理像素，
 * 外加支持的最小窗口（1067×600）、4:3、带鱼屏与 4K。低于最小窗口时整面换成一张提示（WindowNotice），不排版。
 */
final class SettingsLayoutTest {

    /** {物理宽, 物理高}。 */
    private static final int[][] WINDOWS = {
            {HudLayout.MIN_WINDOW_W, HudLayout.MIN_WINDOW_H},
            {1280, 960},      // 320×240 · 界面尺寸 4（4:3）
            {1281, 720},      // 427×240 · 界面尺寸 3
            {1280, 720},      // 640×360 · 界面尺寸 2
            {1920, 1080},     // 640×360 · 界面尺寸 3 · 960×540 · 界面尺寸 2
            {2560, 1080},     // 带鱼屏
            {2880, 2160},     // 960×540 · 界面尺寸 4（3:2 偏高）
            {3840, 2160},     // 4K
            {1200, 1600},     // 竖着放的屏
    };

    private static SettingsLayout layout(int[] window, int categories, int rows) {
        return SettingsLayout.of(window[0], window[1], GuiText::linePxAt, categories, rows);
    }

    private static void inside(List<String> problems, String what, Rect outer, Rect inner) {
        if (inner.w() <= 0 || inner.h() <= 0) {
            problems.add(what + " 是空的：" + inner);
        } else if (!outer.contains(inner)) {
            problems.add(what + " " + inner + " 出了 " + outer);
        }
    }

    private static void apart(List<String> problems, String what, Rect a, Rect b) {
        if (a.intersects(b)) {
            problems.add(what + "：" + a + " 与 " + b + " 重叠");
        }
    }

    private static List<String> check(int[] window, int categories, int rows) {
        SettingsLayout l = layout(window, categories, rows);
        List<String> p = new ArrayList<>();
        String at = window[0] + "×" + window[1] + " 签 " + categories + " 行 " + rows + "：";
        Rect screen = new Rect(0, 0, window[0], window[1]);
        Rect sheet = l.sheet().sheet();
        inside(p, at + "板", screen, sheet);
        inside(p, at + "中间那一块", sheet, l.content());
        if (l.content().y() < l.sheet().phase(0).bottom()) {
            p.add(at + "中间那一块压到标题那一格");
        }
        if (l.content().bottom() > l.sheet().hintsTop()) {
            p.add(at + "中间那一块压到按键提示那一行");
        }
        inside(p, at + "左栏", l.content(), l.left());
        inside(p, at + "右栏", l.content(), l.right());
        apart(p, at + "左右两栏", l.left(), l.right());

        int tags = Math.min(categories, l.tagsVisible());
        for (int i = 0; i < tags; i++) {
            Rect t = l.tag(i);
            inside(p, at + "签 " + i, l.left(), t);
            if (t.h() < l.tagLine()) {
                p.add(at + "签 " + i + " 放不下一行字");
            }
            apart(p, at + "签与说明签", t, l.detailArea());
            for (int j = i + 1; j < tags; j++) {
                apart(p, at + "签 " + i + " · " + j, t, l.tag(j));
            }
        }
        inside(p, at + "说明签那一块", l.left(), l.detailArea());
        if (l.detailMaxLines() < SettingsLayout.DETAIL_MIN_LINES) {
            p.add(at + "说明签只放得下 " + l.detailMaxLines() + " 行（至少 " + SettingsLayout.DETAIL_MIN_LINES + "）");
        }
        for (int lines = 0; lines <= 12; lines++) {
            inside(p, at + "说明签 " + lines + " 行", l.detailArea(), l.detail(lines));
        }

        inside(p, at + "顶行", l.right(), l.header());
        inside(p, at + "锁牌", l.header(), l.lockTag());
        inside(p, at + "状态", l.header(), l.status());
        apart(p, at + "状态与锁牌", l.status(), l.lockTag());
        if (l.lockTag().h() < l.lockLine() || l.status().h() < l.noteLine()) {
            p.add(at + "顶行放不下一行字");
        }
        inside(p, at + "行那一块", l.right(), l.rowsArea());
        apart(p, at + "顶行与行", l.header(), l.rowsArea());
        if (l.rowsVisible() < 3) {
            p.add(at + "一屏只放得下 " + l.rowsVisible() + " 行");
        }
        Rect track = l.scrollTrack();
        inside(p, at + "滚动槽", l.right(), track);
        apart(p, at + "滚动槽与行那一块", track, l.rowsArea());
        for (int scroll = 0; scroll <= Math.max(0, rows - l.rowsVisible()); scroll++) {
            inside(p, at + "滚动条 " + scroll, track, l.scrollThumb(scroll));
        }
        int shown = Math.min(rows, l.rowsVisible());
        for (int i = 0; i < shown; i++) {
            Rect r = l.row(i);
            String row = at + "第 " + i + " 行";
            inside(p, row, l.rowsArea(), r);
            for (int j = i + 1; j < shown; j++) {
                apart(p, row + " 与第 " + j + " 行", r, l.row(j));
            }
            Rect name = l.name(r);
            Rect note = l.note(r);
            Rect widget = l.widget(r);
            inside(p, row + " 名字", r, name);
            inside(p, row + " 说明那一小行", r, note);
            inside(p, row + " 控件", r, widget);
            apart(p, row + " 名字与控件", name, widget);
            apart(p, row + " 小行与控件", note, widget);
            apart(p, row + " 名字与小行", name, note);
            if (name.h() < l.rowLine() || note.h() < l.noteLine() || widget.h() < l.widgetLine()) {
                p.add(row + " 有一格放不下它那一级字");
            }
            Rect[] toggle = l.toggle(widget);
            Rect[] cycle = l.cycle(widget);
            Rect[] gauge = l.gauge(widget);
            for (Rect[] parts : new Rect[][]{toggle, cycle, gauge}) {
                for (int a = 0; a < parts.length; a++) {
                    inside(p, row + " 控件的一截", widget, parts[a]);
                    for (int b = a + 1; b < parts.length; b++) {
                        apart(p, row + " 控件的两截", parts[a], parts[b]);
                    }
                }
            }
            inside(p, row + " 输入框", widget, l.field(widget));
        }
        return p;
    }

    @Test
    @DisplayName("各种窗口 × 签与行的多少：不重叠、不出界、字放得下、说明签至少三行")
    void noOverlapAcrossWindows() {
        List<String> problems = new ArrayList<>();
        int cases = 0;
        for (int[] window : WINDOWS) {
            for (int categories : new int[]{1, 4, 6, 12}) {
                for (int rows : new int[]{0, 1, 3, 14, 40}) {
                    problems.addAll(check(window, categories, rows));
                    cases++;
                }
            }
        }
        assertTrue(cases >= 150, "只核了 " + cases + " 种 —— 没在核");
        System.out.println("设置菜单版面：核了 " + cases + " 种窗口 × 签 × 行");
        assertEquals(List.of(), problems.stream().limit(30).toList(), String.join("\n", problems.stream().limit(30).toList()));
    }

    @Test
    @DisplayName("签放不下时只露选中的那一段：选中的那一张一定露着")
    void selectedTagStaysVisible() {
        for (int[] window : WINDOWS) {
            int categories = 30;
            SettingsLayout l = layout(window, categories, 5);
            assertTrue(l.tagsVisible() < categories, "前提：30 张签放不下");
            for (int selected = 0; selected < categories; selected++) {
                int first = l.tagScroll(selected);
                assertTrue(selected >= first && selected < first + l.tagsVisible(),
                        window[0] + "×" + window[1] + " 选中第 " + selected + " 张却露的是 " + first + " 起");
            }
        }
    }

    @Test
    @DisplayName("选中的行留在视野里：一屏放得下几行就按几行滚")
    void selectedRowStaysVisible() {
        for (int[] window : WINDOWS) {
            SettingsLayout l = layout(window, 4, 40);
            int visible = l.rowsVisible();
            int scroll = 0;
            for (int focus = 0; focus < 40; focus++) {
                scroll = SettingsMenuState.keepVisible(focus, scroll, visible, 40);
                assertTrue(focus >= scroll && focus < scroll + visible);
            }
            for (int focus = 39; focus >= 0; focus--) {
                scroll = SettingsMenuState.keepVisible(focus, scroll, visible, 40);
                assertTrue(focus >= scroll && focus < scroll + visible);
            }
        }
    }

    @Test
    @DisplayName("红测：判据认得出重叠与出界（造两个坏矩形，必须各点名一次）")
    void judgeNamesOverlapAndOverflow() {
        List<String> p = new ArrayList<>();
        apart(p, "坏", new Rect(0, 0, 10, 10), new Rect(5, 5, 10, 10));
        inside(p, "坏", new Rect(0, 0, 10, 10), new Rect(5, 5, 10, 10));
        apart(p, "好", new Rect(0, 0, 10, 10), new Rect(10, 0, 10, 10));
        inside(p, "好", new Rect(0, 0, 10, 10), new Rect(0, 0, 10, 10));
        assertEquals(2, p.size(), String.join("\n", p));
    }
}
