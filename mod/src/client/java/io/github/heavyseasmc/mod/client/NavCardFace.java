package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.game.NavCardText;
import io.github.heavyseasmc.mod.state.NavCardView;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * 画一张航海牌的牌面 —— 用字，不用贴图。
 *
 * <h2>为什么没有贴图</h2>
 * {@code art/cards} 里只有物资、角色与天候的卡面，航海牌一张都没有；卡面由内部管线生成（美术走 clean-room），
 * 不是这里能补的。M2 第一刀先求可玩：牌面上印的几件事照数据写成字 —— 海鸥、落海、口渴（含两个图示）——
 * 看得懂、分得清几张牌的不同就够。贴图到了之后换掉的只有这一个类（CURRENT_STATUS 的开放项）。
 *
 * <h2>字随牌缩放</h2>
 * 排版按一个固定的设计宽度做，再整体缩放到实际大小：一张牌不论画多大，折行的位置都一样。
 * 一排好几张时牌很窄，字会小到读不清 —— 所以划船与舵手两面在高亮那一张下面另有一行完整说明
 * （{@link NavCardText#describe}），与补给箱「说明只跟高亮走」同一个做法。
 */
public final class NavCardFace {

    /** 牌面按这个宽度排版（GUI 单位），再整体缩放。 */
    private static final int DESIGN_W = 100;
    private static final int PAD = 7;
    /** 一行字占多高：字高 9 加 1 行距。 */
    private static final int LINE = 10;
    private static final int RULE_GAP = 3;
    /** 字多到放不下时，在设计尺寸上最多再缩到这么小；还放不下就画到哪算哪 —— 完整说明在高亮下面那一行。 */
    private static final float MIN_FIT = 0.55f;

    /** 墨的几档透明度：细线、栏名、牌框。都是同一个墨，不另起颜色。 */
    private static final int RULE = 0x40000000 | (GuiLanguage.CARD_INK & 0xFFFFFF);
    private static final int LABEL = 0x99000000 | (GuiLanguage.CARD_INK & 0xFFFFFF);
    private static final int FRAME = 0x80000000 | (GuiLanguage.CARD_INK & 0xFFFFFF);

    private NavCardFace() {
    }

    /** 一行：字，或者一条细线。 */
    private record Row(OrderedText text, int color, boolean centered) {

        static Row rule() {
            return new Row(null, 0, false);
        }

        int height() {
            return text == null ? 2 * RULE_GAP + 1 : LINE;
        }
    }

    /** 在 (x, y) 画一张 w × h 的航海牌。调用方的矩阵变换（抬、发、顿、飞）照样作用在它上面。 */
    public static void draw(DrawContext context, TextRenderer text, NavCardView card, List<String> seats,
                            int x, int y, int w, int h) {
        context.fill(x, y, x + w, y + h, GuiLanguage.PAPER);
        context.drawBorder(x, y, w, h, FRAME);
        if (w < 16 || h < 20) {
            return;                                   // 小到这个程度，字只是噪点
        }
        float scale = w / (float) DESIGN_W;
        int innerW = DESIGN_W - 2 * PAD;
        int innerH = Math.round(h / scale) - 2 * PAD;

        // 放得下就不缩；放不下就一点点缩，每缩一次按新的宽度重新折行。
        float fit = 1f;
        List<Row> rows = layout(text, card, seats, innerW);
        while (fit > MIN_FIT && height(rows) * fit > innerH) {
            fit *= 0.9f;
            rows = layout(text, card, seats, Math.round(innerW / fit));
        }

        int span = Math.round(innerW / fit);
        int limit = Math.round(innerH / fit);
        context.getMatrices().push();
        context.getMatrices().translate(x + PAD * scale, y + PAD * scale, 0);
        context.getMatrices().scale(scale * fit, scale * fit, 1f);
        int cy = 0;
        for (Row row : rows) {
            if (cy + row.height() > limit) {
                break;
            }
            if (row.text() == null) {
                context.fill(0, cy + RULE_GAP, span, cy + RULE_GAP + 1, RULE);
            } else {
                int lx = row.centered() ? (span - text.getWidth(row.text())) / 2 : 0;
                context.drawText(text, row.text(), lx, cy, row.color(), false);
            }
            cy += row.height();
        }
        context.getMatrices().pop();
    }

    private static int height(List<Row> rows) {
        return rows.stream().mapToInt(Row::height).sum();
    }

    /** 自上而下：海鸥（这张牌动海鸥才有）· 落海 · 口渴。栏名淡一档，名单是墨。 */
    private static List<Row> layout(TextRenderer text, NavCardView card, List<String> seats, int width) {
        List<Row> rows = new ArrayList<>();
        Text gull = NavCardText.gull(card);
        if (gull != null) {
            for (OrderedText line : text.wrapLines(gull, width)) {
                rows.add(new Row(line, GuiLanguage.CARD_INK, true));
            }
            rows.add(Row.rule());
        }
        rows.add(new Row(Text.translatable("heavyseas.nav.overboard").asOrderedText(), LABEL, false));
        for (OrderedText line : text.wrapLines(NavCardText.names(card.overboard(), seats), width)) {
            rows.add(new Row(line, GuiLanguage.CARD_INK, false));
        }
        rows.add(Row.rule());
        rows.add(new Row(Text.translatable("heavyseas.nav.thirst").asOrderedText(), LABEL, false));
        for (OrderedText line : text.wrapLines(NavCardText.thirst(card, seats), width)) {
            rows.add(new Row(line, GuiLanguage.CARD_INK, false));
        }
        return rows;
    }
}
