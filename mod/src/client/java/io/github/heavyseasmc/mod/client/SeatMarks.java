package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.engine.state.Condition;
import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.state.TableView;
import io.github.heavyseasmc.mod.ui.HudLayout.Rect;
import io.github.heavyseasmc.mod.ui.HudPart;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.Optional;

/**
 * D1 (a)：座位轨上每一座的公开状态（ADR-0048；用户 2026-09-30 在 D1 那四张样张里选的 (a)）。
 *
 * <ul>
 *   <li>头像右下一枚印章「剩 / 体型」：满的是墨边，少了的是朱砂边；</li>
 *   <li>受伤还醒着：头像按伤了几成渐渐压暗（B3），到昏迷那一刻正好是昏迷那一层；</li>
 *   <li>昏迷：头像压暗，正中一枚「昏」签 —— 不只靠颜色；</li>
 *   <li>落海移出：深色罩加两道波纹；死在船上：深色罩加一个叉。这两种不再印印章。</li>
 * </ul>
 *
 * <p>全是公开信息（体型与伤势全船可见，决策 ③），数据取自 {@link TableView} —— 与座位上那张悬停签同一个来源。
 *
 * <p>几何取自 D1 样张：印章中心在头像半径的 (0.82, 0.73) 处，字 13 / 11 px。❗字号按字号梯子取（粗体最小 14），
 * 所以印章的宽高按排出来的字算，不跟着头像等比缩 —— 缩下去字就读不出来了。底边不低于头像的底，
 * 免得压到各面座位轨上的名字。
 *
 * <p>分两步画：{@link #shade} 在头像之后、外圈之前（暗只压在头像上，圈的语义色 —— 你 · 正轮到 · 被抢 —— 照旧看得见）；
 * {@link #marks} 在外圈之后（印章与签压在圈上，与样张一样）。
 */
final class SeatMarks {

    /** 印章：{@code height: 20px; min-width: 34px; padding: 0 5px}，边 1.5，字 13 / 11 px。 */
    private static final double SEAL_H = 20;
    private static final double SEAL_MIN_W = 34;
    private static final double SEAL_PAD = 5;
    private static final double SEAL_BORDER = 1.5;
    /** 数字前那枚秤砣（体型，ADR-0090）的高占大字行高的比例，与数字之间的空（设计单位）。 */
    private static final double SEAL_ICON_OF_LINE = 0.78;
    private static final double SEAL_ICON_GAP = 1.5;
    private static final double SEAL_BIG_PX = 13;
    private static final double SEAL_SMALL_PX = 11;
    /** 印章中心相对头像中心，以半径为单位（样张 71 的头像上左 +12、上 +16，宽 34、高 20）。 */
    private static final double SEAL_CX = 0.82;
    private static final double SEAL_CY = 0.73;
    /** 印章底边最多伸出头像底下多少（稿子像素）。各面的轨上名字就在头像下 3：伸出去就压到名字（2026-09-30 实拍），所以是 0。 */
    private static final double SEAL_BELOW = 0;
    /** 「昏」签：{@code font: 700 12px/16px; padding: 0 4px}。 */
    private static final double TAG_PX = 12;
    private static final double TAG_PAD = 4;

    /** 样张 {@code --ink · --muted · --cinnabar · --paper}：印章是纸，两个主题下都一样。 */
    private static final int INK = 0xFF241E1A;
    private static final int MUTED = 0xFF5A4D3E;
    private static final int CINNABAR = 0xFFB83F28;
    private static final int PAPER = 0xFFEFE6CF;

    private SeatMarks() {
    }

    /** 这一座此刻的公开状态；没有对局或找不到这一座就是空。 */
    static Optional<TableView.Seat> seat(String characterId) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || characterId.isEmpty()) {
            return Optional.empty();
        }
        for (TableView.Seat seat : GameComponents.of(client.world).tableView().seats()) {
            if (seat.id().equals(characterId)) {
                return Optional.of(seat);
            }
        }
        return Optional.empty();
    }

    /** 头像之后、外圈之前：昏迷压暗 · 移出与死亡的深色罩和它上面那一枚图示。 */
    static void shade(DrawContext context, Rect t, Kit kit, TableView.Seat seat, double k) {
        if (gone(seat) || dead(seat)) {
            GuiMaterial.hudPart(context, kit.shadeGone, t.x(), t.y(), t.w(), t.h(), k);
            HudPart icon = gone(seat) ? kit.waves : kit.cross;
            int w = (int) Math.round(icon.w() * k);
            int h = (int) Math.round(icon.h() * k);
            GuiMaterial.hudIcon(context, icon, t.x() + (t.w() - w) / 2, t.y() + (t.h() - h) / 2, w, h, PAPER, k);
        } else if (seat.condition() == Condition.UNCONSCIOUS) {
            GuiMaterial.hudPart(context, kit.shadeOut, t.x(), t.y(), t.w(), t.h(), k);
        } else if (seat.health() < seat.size()) {
            // 受了伤还醒着：同一层暗按伤了几成压上去，伤到昏迷那一刻正好是昏迷那一层（ADR-0045 §5.2 B3 最低成本那一格：
            // 「第六天比第一天难熬」要画在人身上，而不只是印章上的一个数）。不另画裂纹 —— 那要一张新的覆盖贴图，见 ADR-0048 §8
            float hurt = (seat.size() - Math.max(0, seat.health())) / (float) seat.size();
            context.setShaderColor(1f, 1f, 1f, hurt);
            GuiMaterial.hudPart(context, kit.shadeOut, t.x(), t.y(), t.w(), t.h(), k);
            context.setShaderColor(1f, 1f, 1f, 1f);
        }
    }

    /**
     * 外圈之后：「昏」签与印章。
     *
     * @param sealUp 印章挪到右上 —— 主画面上舵手那一座的右下挂着舵轮（样张 {@code .seat .mk}），让给它
     */
    static void marks(DrawContext context, Rect t, TableView.Seat seat, double k, boolean sealUp) {
        if (gone(seat) || dead(seat)) {
            return;
        }
        Rect seal = drawSeal(context, t, seat, k, sealUp);
        if (seat.condition() == Condition.UNCONSCIOUS) {
            drawTag(context, t, seal, k);
        }
    }

    /**
     * 「昏」签：样张里在头像正中。❗主画面的头像只有 40，正中那一枚会被右下的印章压住下半截（2026-09-30 实拍：「昏」只剩上半个字），
     * 所以与印章相交时挪到印章上面（印章在右上时挪到下面），横向仍居中。
     */
    private static void drawTag(DrawContext context, Rect t, Rect seal, double k) {
        String s = Text.translatable("heavyseas.seat.mark.unconscious").getString();
        int px = len(TAG_PX, k);
        int textW = GuiText.widthPx(s, px, true, 0);
        int line = GuiText.linePxAt(px, true);
        int w = textW + 2 * len(TAG_PAD, k);
        int x = t.x() + (t.w() - w) / 2;
        int y = t.y() + (t.h() - line) / 2;
        Rect tag = new Rect(x, y, w, line);
        if (tag.intersects(seal)) {
            y = seal.y() > t.y() + t.h() / 2 ? seal.y() - 1 - line : seal.y() + seal.h() + 1;
        }
        GuiMaterial.hudPart(context, HudPart.TAG, x, y, w, line, k);
        GuiText.drawPx(context, s, x, y, w, px, true, PAPER, GuiText.Align.CENTER, 0);
    }

    /** 画印章，返回它占的那一块（「昏」签要躲开它）。 */
    private static Rect drawSeal(DrawContext context, Rect t, TableView.Seat seat, double k, boolean up) {
        int hp = Math.max(0, seat.health());
        boolean hurt = hp < seat.size();
        String big = Integer.toString(hp);
        String small = "/" + seat.size();
        int bigPx = len(SEAL_BIG_PX, k);
        int smallPx = len(SEAL_SMALL_PX, k);
        int bigW = GuiText.widthPx(big, bigPx, true, 0);
        int smallW = GuiText.widthPx(small, smallPx, false, 0);
        int bigLine = GuiText.linePxAt(bigPx, true);
        int smallLine = GuiText.linePxAt(smallPx, false);
        int edge = len(SEAL_BORDER, k);
        int h = Math.max(len(SEAL_H, k), bigLine + 2 * edge);
        // 数字前印一枚秤砣：分母是体型，与卡角那枚同一个图（Codex 复核：「4/4」读不出分母是什么）
        String size = io.github.heavyseasmc.mod.card.CardFaces.SIZE;
        int iconH = Math.max(1, (int) Math.round(bigLine * SEAL_ICON_OF_LINE));
        int iconW = CardPainter.cardIconWidth(size, iconH);
        int iconGap = len(SEAL_ICON_GAP, k);
        int w = Math.max(len(SEAL_MIN_W, k), iconW + iconGap + bigW + smallW + 2 * (len(SEAL_PAD, k) + edge));
        double r = t.w() / 2.0;
        int cx = t.x() + (int) Math.round(r + SEAL_CX * r);
        int x = cx - w / 2;
        int y;
        if (up) {
            y = t.y() + (int) Math.round(r - SEAL_CY * r) - h / 2;
            y = Math.max(y, t.y() - len(SEAL_BELOW, k));
        } else {
            y = t.y() + (int) Math.round(r + SEAL_CY * r) - h / 2;
            y = Math.min(y, t.y() + t.h() + len(SEAL_BELOW, k) - h);
        }
        GuiMaterial.hudPart(context, hurt ? HudPart.SEAL_HURT : HudPart.SEAL, x, y, w, h, k);
        // 两段字共一条基线：大字在印章里竖向居中，小字的行框底对齐大字的行框底
        int lineX = x + (w - iconW - iconGap - bigW - smallW) / 2;
        CardPainter.drawCardIcon(context, size, lineX, y + (h - iconH) / 2, iconH, 1f);
        int textX = lineX + iconW + iconGap;
        int bigTop = y + (h - bigLine) / 2;
        GuiText.drawPx(context, big, textX, bigTop, bigW + 2, bigPx, true, hurt ? CINNABAR : INK, GuiText.Align.LEFT, 0);
        GuiText.drawPx(context, small, textX + bigW, bigTop + bigLine - smallLine, smallW + 2, smallPx, false, MUTED,
                GuiText.Align.LEFT, 0);
        return new Rect(x, y, w, h);
    }

    private static boolean gone(TableView.Seat seat) {
        return seat.removed();
    }

    private static boolean dead(TableView.Seat seat) {
        return seat.condition() == Condition.DEAD;
    }

    private static int len(double design, double k) {
        return Math.max(1, (int) Math.round(design * k));
    }

    /** 一种头像尺寸用的那一套：主画面 40 · 对局各面 46。 */
    enum Kit {
        TOKEN40(HudPart.SHADE_OUT40, HudPart.SHADE_GONE40, HudPart.IC_WAVES40, HudPart.IC_CROSS40),
        TOKEN46(HudPart.SHADE_OUT46, HudPart.SHADE_GONE46, HudPart.IC_WAVES46, HudPart.IC_CROSS46);

        private final HudPart shadeOut;
        private final HudPart shadeGone;
        private final HudPart waves;
        private final HudPart cross;

        Kit(HudPart shadeOut, HudPart shadeGone, HudPart waves, HudPart cross) {
            this.shadeOut = shadeOut;
            this.shadeGone = shadeGone;
            this.waves = waves;
            this.cross = cross;
        }
    }
}
