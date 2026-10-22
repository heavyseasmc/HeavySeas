package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.ui.HudLayout.Rect;
import io.github.heavyseasmc.mod.ui.HudPart;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 我的猜测（ADR-0043 A17 · §11 第九组）：给某个人记一笔「我猜这人爱我 / 恨我」，座位轨上他头像左下一枚铅笔灰的心或碎心加问号。
 *
 * <p><b>只在这一台、不发任何包</b>（藏身份的游戏：你在怀疑谁，别人不能知道，ADR-0043 §8）；换一局（播报纪元变了）、换了世界就清空。
 */
final class GuessMarks {

    enum Mark {
        NONE("heavyseas.guess.none"),
        LOVES_ME("heavyseas.guess.loves_me"),
        HATES_ME("heavyseas.guess.hates_me");

        private final String key;

        Mark(String key) {
            this.key = key;
        }

        Text label() {
            return Text.translatable(key);
        }

        Mark next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /** 铅笔灰：不是语义色（金 · 朱砂 · 铜绿都另有意思），一眼看得出是自己随手记的。 */
    private static final int PENCIL = 0xFF5C5854;

    private static final Map<String, Mark> MARKS = new HashMap<>();
    private static Object world;
    private static UUID epoch;

    private GuessMarks() {
    }

    private static void sync() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            MARKS.clear();
            world = null;
            epoch = null;
            return;
        }
        UUID now = GameComponents.of(client.world).notificationEpoch();
        if (world != client.world || !now.equals(epoch)) {
            MARKS.clear();
            world = client.world;
            epoch = now;
        }
    }

    static Mark of(String id) {
        sync();
        return MARKS.getOrDefault(id, Mark.NONE);
    }

    /** 轮到下一种：记一笔 → 爱我？ → 恨我？ → 清掉。 */
    static Mark cycle(String id) {
        sync();
        Mark next = of(id).next();
        if (next == Mark.NONE) {
            MARKS.remove(id);
        } else {
            MARKS.put(id, next);
        }
        return next;
    }

    /** 头像左下那一枚（物理像素坐标，调用方已在 px 模式）。没记就什么都不画。 */
    static void draw(DrawContext context, Rect token, String id, double k) {
        Mark mark = of(id);
        if (mark == Mark.NONE) {
            return;
        }
        // 头像左下一枚胶囊形小签：纸底、铅笔灰的边，里面一颗心 / 碎心和一个问号（印章在右下、舵轮在左上、补给箱在右上）。
        // 2026-10-20 实拍：问号画在签外面时压在深色的衣服上，看不见 —— 两样都放进纸底里
        float h = (float) (15 * k);
        float w = (float) (25 * k);
        float x0 = token.x() - (float) (3 * k);
        float y0 = token.y() + token.h() - h + (float) (2 * k);
        pill(context, x0, y0 + (float) k, w, h, 0x73000000);
        pill(context, x0, y0, w, h, PENCIL);
        float b = (float) (1.2 * k);
        pill(context, x0 + b, y0 + b, w - 2 * b, h - 2 * b, SeatMarks.PAPER);
        int s = (int) Math.round(10 * k);
        GuiMaterial.hudIcon(context, mark == Mark.LOVES_ME ? HudPart.IC_HEART : HudPart.IC_HATE,
                Math.round(x0 + 3.5f * (float) k), Math.round(y0 + (h - s) / 2f), s, s, PENCIL, s / 24.0);
        int px = (int) Math.round(11 * k);
        GuiText.drawPx(context, "?", Math.round(x0 + 3.5f * (float) k + s + (float) k),
                Math.round(y0 + (h - GuiText.linePxAt(px, true)) / 2f), px * 2, px, true, PENCIL, GuiText.Align.LEFT, 0);
    }

    /** 两头半圆、中间一段方的胶囊。 */
    private static void pill(DrawContext context, float x, float y, float w, float h, int argb) {
        float r = h / 2f;
        GuiMaterial.disc(context, x + r, y + r, r, argb);
        GuiMaterial.disc(context, x + w - r, y + r, r, argb);
        GuiMaterial.rectF(context, x + r, y, x + w - r, y + h, argb);
    }
}
