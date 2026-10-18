package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.ui.HudLayout.Rect;
import io.github.heavyseasmc.mod.ui.HudPart;
import io.github.heavyseasmc.mod.ui.SeatClock;
import net.minecraft.client.gui.DrawContext;

/**
 * 座位轨上一座的那一圈与右上那枚小圆片（2026-10-13 样张 A）：主画面（头像 40）与对局各面（头像 46）<b>共用这一份</b>，
 * 各自只给尺寸那一套贴图与小圆片的位置。一座是什么样子由 {@link SeatClock#mark} 算（两处同一个算法）。
 *
 * <ul>
 *   <li>那一圈：金 = 你 · 朱砂 = 被指的人 · 铜绿 = 在等他；在等他且有钟时，那一圈（不管哪种颜色）从 12 点起顺时针被吃掉；
 *       在等他却没有钟（不限时 · 没开窗口）时铜绿那一圈呼吸。</li>
 *   <li>小圆片：纸底 · 墨边 · 底下一线影子，里面补给箱（墨）或准星（朱砂，矢量画）。</li>
 * </ul>
 */
final class SeatToken {

    private SeatToken() {
    }

    /** 一种头像尺寸那一套圈。 */
    enum Kit {
        TOKEN40(HudPart.TOK40_PLAIN, HudPart.TOK40_YOU, HudPart.TOK40_ACT, HudPart.TOK40_CINN),
        TOKEN46(HudPart.TOK46_PLAIN, HudPart.TOK46_YOU, HudPart.TOK46_ACT, HudPart.TOK46_CINN);

        private final HudPart plain;
        private final HudPart you;
        private final HudPart act;
        private final HudPart cinn;

        Kit(HudPart plain, HudPart you, HudPart act, HudPart cinn) {
            this.plain = plain;
            this.you = you;
            this.act = act;
            this.cinn = cinn;
        }

        HudPart of(SeatClock.Ring ring) {
            return switch (ring) {
                case YOU -> you;
                case AIMED -> cinn;
                case WAITED -> act;
                case PLAIN -> plain;
            };
        }
    }

    /** 那一圈（坐标同 {@link GuiMaterial#hudPart}：头像那个圆的方框）。在头像与压暗之后、印章之前画。 */
    static void ring(DrawContext context, Kit kit, Rect t, double k, SeatClock.Mark mark) {
        HudPart ring = kit.of(mark.ring());
        if (mark.clock() != SeatClock.NONE) {
            GuiMaterial.clockRing(context, kit.plain, ring, t.x(), t.y(), t.w(), t.h(), k, mark.clock());
        } else if (mark.breathes()) {
            GuiMaterial.breathingRing(context, kit.plain, ring, t.x(), t.y(), t.w(), t.h(), k);
        } else {
            GuiMaterial.hudPart(context, ring, t.x(), t.y(), t.w(), t.h(), k);
        }
    }

    /** 右上那枚小圆片；{@link SeatClock.Chip#NONE} 时什么都不画。{@code c} 是它的方框，{@code k} 是稿子像素换物理像素的倍率。 */
    static void chip(DrawContext context, Rect c, double k, SeatClock.Chip chip) {
        if (chip == SeatClock.Chip.NONE) {
            return;
        }
        float cx = c.x() + c.w() / 2f;
        float cy = c.y() + c.h() / 2f;
        float r = c.w() / 2f;
        GuiMaterial.disc(context, cx, cy + (float) k, r, 0x73000000);
        GuiMaterial.disc(context, cx, cy, r, SeatMarks.INK);
        GuiMaterial.disc(context, cx, cy, r - (float) (1.5 * k), SeatMarks.PAPER);
        if (chip == SeatClock.Chip.CRATE) {
            int s = (int) Math.round(13 * k);
            GuiMaterial.hudIcon(context, HudPart.IC_CRATE, Math.round(cx - s / 2f), Math.round(cy - s / 2f), s, s,
                    SeatMarks.INK, s / 24.0);
            return;
        }
        // 准星：直径 13 —— 一道圈（半径 0.62）、正中一点、四根从外沿伸进 0.42 的短刻度，线宽 1.6（样张 aim_svg）
        float rr = (float) (6.5 * k);
        float stroke = (float) (1.6 * k);
        float ringR = rr * 0.62f;
        GuiMaterial.annulus(context, cx, cy, ringR - stroke / 2, ringR + stroke / 2, SeatMarks.CINNABAR);
        GuiMaterial.disc(context, cx, cy, (float) (1.4 * k), SeatMarks.CINNABAR);
        float tick = rr * 0.42f;
        float hs = stroke / 2;
        GuiMaterial.rectF(context, cx - hs, cy - rr, cx + hs, cy - rr + tick, SeatMarks.CINNABAR);
        GuiMaterial.rectF(context, cx - hs, cy + rr - tick, cx + hs, cy + rr, SeatMarks.CINNABAR);
        GuiMaterial.rectF(context, cx - rr, cy - hs, cx - rr + tick, cy + hs, SeatMarks.CINNABAR);
        GuiMaterial.rectF(context, cx + rr - tick, cy - hs, cx + rr, cy + hs, SeatMarks.CINNABAR);
    }
}
