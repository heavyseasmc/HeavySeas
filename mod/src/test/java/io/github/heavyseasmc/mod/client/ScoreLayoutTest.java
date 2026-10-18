package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.ui.HudLayout;
import io.github.heavyseasmc.mod.ui.HudLayout.Rect;
import io.github.heavyseasmc.mod.ui.ScoreLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 计分面板的版面（样张 b-5）在各种窗口 × 两档阅读字号 × 四到八个人下：不出板 · 不相叠 · 左下那行小字压不到提示行。
 * 行高按<b>真实的字号梯子</b>算（{@link GuiText#linePxAt} 与 {@link GuiText#readingSizePx}，与画字同一组函数）——
 * 小窗口下字号有地板，按比例估的行高会偏矮（证伪表「版面写死 GUI 单位的上限」「有下限的东西不跟着缩」）。
 *
 * <p>854×480 低于支持的最小窗口（{@link HudLayout#MIN_WINDOW_W}×{@link HudLayout#MIN_WINDOW_H}），
 * 那一档整面换成一张提示（WindowNotice），不排这一面 —— 这里只核对它确实在线下。
 */
final class ScoreLayoutTest {

    /** {物理宽, 物理高}。 */
    private static final int[][] WINDOWS = {
            {HudLayout.MIN_WINDOW_W, HudLayout.MIN_WINDOW_H},
            {1280, 720},
            {1920, 1080},
            {1280, 960},
            {2560, 1080},
            {3840, 2160},
            {1200, 1600},
    };

    private static ScoreLayout layout(int[] window, boolean large, int people) {
        return ScoreLayout.of(window[0], window[1], GuiText::linePxAt,
                (px, bold) -> GuiText.linePxAt(GuiText.readingSizePx(px, bold, large), bold), people);
    }

    private static void inside(List<String> p, String what, Rect outer, Rect inner) {
        if (inner.w() <= 0 || inner.h() <= 0) {
            p.add(what + " 是空的：" + inner);
        } else if (!outer.contains(inner)) {
            p.add(what + " " + inner + " 出了 " + outer);
        }
    }

    private static void apart(List<String> p, String what, Rect a, Rect b) {
        if (a.intersects(b)) {
            p.add(what + "：" + a + " 与 " + b + " 相叠");
        }
    }

    private static void above(List<String> p, String what, Rect upper, Rect lower) {
        if (upper.bottom() > lower.y()) {
            p.add(what + "：" + upper + " 的下沿压到 " + lower);
        }
    }

    static List<String> check(int[] window, boolean large, int people) {
        ScoreLayout l = layout(window, large, people);
        List<String> p = new ArrayList<>();
        String at = window[0] + "×" + window[1] + (large ? " 大字" : " 常规") + " " + people + " 人：";
        Rect sheet = l.sheet().sheet();
        Rect foot = l.foot();
        inside(p, at + "标题", sheet, l.title());
        inside(p, at + "副标题", sheet, l.subtitle());
        above(p, at + "标题与副标题", l.title(), l.subtitle());
        inside(p, at + "左下小字", sheet, foot);
        if (foot.bottom() > l.sheet().hintsTop()) {
            p.add(at + "左下小字 " + foot + " 压到提示行（顶 " + l.sheet().hintsTop() + "）");
        }
        assertEquals(people, l.rows().size(), at + "行数");
        for (int i = 0; i < l.rows().size(); i++) {
            ScoreLayout.Row r = l.rows().get(i);
            String row = at + "第 " + (i + 1) + " 行";
            inside(p, row, sheet, r.row());
            above(p, row + "与副标题", l.subtitle(), r.row());
            above(p, row + "与左下小字", r.row(), foot);
            inside(p, row + "桂冠", r.row(), r.laurel());
            inside(p, row + "头像", r.row(), r.avatar());
            inside(p, row + "名字", r.row(), r.name());
            inside(p, row + "合计", r.row(), r.total());
            apart(p, row + "桂冠与头像", r.laurel(), r.avatar());
            apart(p, row + "头像与名字", r.avatar(), r.name());
            apart(p, row + "名字与合计", r.name(), r.total());
            apart(p, row + "与右边的账", r.row(), l.card());
            if (r.name().w() < l.len(80)) {
                p.add(row + "名字只剩 " + r.name().w() + " 宽");
            }
            if (i > 0) {
                above(p, row + "与上一行", l.rows().get(i - 1).row(), r.row());
            }
        }
        Rect card = l.card();
        inside(p, at + "账", sheet, card);
        above(p, at + "副标题与账", l.subtitle(), card);
        above(p, at + "账与左下小字", card, foot);
        inside(p, at + "账头像", card, l.headAvatar());
        inside(p, at + "账小标题", card, l.caption());
        inside(p, at + "账名字", card, l.headName());
        above(p, at + "账小标题与名字", l.caption(), l.headName());
        apart(p, at + "账头像与名字", l.headAvatar(), l.headName());
        Rect previous = l.headName().y() > l.headAvatar().y() ? l.headName() : l.headAvatar();
        for (int i = 0; i < l.items().size(); i++) {
            ScoreLayout.Item it = l.items().get(i);
            String item = at + "第 " + (i + 1) + " 项";
            inside(p, item, card, it.row());
            inside(p, item + "图标", it.row(), it.icon());
            inside(p, item + "字", it.row(), it.text());
            inside(p, item + "数", it.row(), it.value());
            apart(p, item + "图标与字", it.icon(), it.text());
            apart(p, item + "字与数", it.text(), it.value());
            above(p, item + "与上面", previous, it.row());
            previous = it.row();
        }
        above(p, at + "第 4 项与那道线", previous, l.rule());
        inside(p, at + "那道线", card, l.rule());
        above(p, at + "那道线与合计", l.rule(), l.sum());
        inside(p, at + "合计数", card, l.sum());
        inside(p, at + "合计字", card, l.sumLabel());
        apart(p, at + "合计字与数", l.sumLabel(), l.sum());
        return p;
    }

    @Test
    @DisplayName("各种窗口 × 两档阅读字号 × 四到八个人：计分面板不出板、不相叠、压不到提示行")
    void fitsEverywhere() {
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (int[] window : WINDOWS) {
            for (boolean large : new boolean[]{false, true}) {
                for (int people = 4; people <= 8; people++) {
                    problems.addAll(check(window, large, people));
                    checked++;
                }
            }
        }
        // 正向对照：确实排了这么多种（一种都没排时「没有问题」与「没在查」长得一样）
        assertEquals(WINDOWS.length * 2 * 5, checked);
        assertEquals(List.of(), problems, String.join("\n", problems));
    }

    @Test
    @DisplayName("大字在最小窗口下确实比常规高：两档阅读字号真的排了两遍，不是同一份")
    void largeReadingIsReallyLarger() {
        int[] min = WINDOWS[0];
        ScoreLayout normal = layout(min, false, 8);
        ScoreLayout large = layout(min, true, 8);
        assertTrue(large.items().get(0).text().h() > normal.items().get(0).text().h(),
                "大字的标签行 " + large.items().get(0).text() + " 不比常规 " + normal.items().get(0).text() + " 高");
    }

    @Test
    @DisplayName("854×480 低于最小窗口：那一档不排这一面（换成提示）")
    void tinyWindowIsNotLaidOut() {
        assertFalse(HudLayout.supportsWindow(854, 480));
    }
}
