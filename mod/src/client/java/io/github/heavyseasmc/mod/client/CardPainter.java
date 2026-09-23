package io.github.heavyseasmc.mod.client;

import com.mojang.blaze3d.systems.RenderSystem;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.card.CardFace;
import io.github.heavyseasmc.mod.card.CardLayout;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.resource.Resource;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把一张牌按槽位图画出来（ADR-0039）：边框 → 插画 → 牌名 → 角标 → 口渴排。
 *
 * <p>❗<b>画牌只有这一段</b>。合成进纹理（{@link CardComposite}，贴图自己的像素空间）与合成好之前那几帧
 * 直接画上屏幕（GUI 单位），走的是同一段代码 —— 两条路长得不一样的话，合成好的那一刻牌会「跳」一下，
 * 而且老路坏了没人看得出来（ADR-0039 §8）。
 *
 * <p>坐标一律按母版单位写、按画出来的大小等比缩：调用方给多大是它自己的版面问题。
 */
final class CardPainter {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private static final String CARDS = "textures/gui/cards/";
    private static final Identifier LAYOUT_ID = Identifier.of(HeavySeasMod.MOD_ID, "cards/layout.json");
    /** 被划掉的头像压暗到这么多：还认得出是谁，但一眼看得出「不是他」。 */
    private static final float STRUCK_ALPHA = 0.45f;
    /** 图示在底盘里占的比例（与样张相同）。 */
    private static final float ICON_IN_DISC = 0.62f;

    private static CardLayout layout;
    /** 已经报过「缺插画」的：同一张只报一次，不刷屏。 */
    private static final Set<String> MISSING = new HashSet<>();

    private CardPainter() {
    }

    /** 版面描述。资源重载时由 {@link #reset} 作废。读不到是打包错误 —— 当场抛，不退回一套写死的坐标。 */
    static CardLayout layout() {
        if (layout == null) {
            Resource res = MinecraftClient.getInstance().getResourceManager().getResource(LAYOUT_ID)
                    .orElseThrow(() -> new IllegalStateException("缺版面描述 " + LAYOUT_ID + " —— 牌面没法拼"));
            try (Reader in = new InputStreamReader(res.getInputStream(), StandardCharsets.UTF_8)) {
                layout = CardLayout.parse(in);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("版面描述读坏了：" + e, e);
            }
        }
        return layout;
    }

    static void reset() {
        layout = null;
        MISSING.clear();
    }

    /**
     * 画一整张牌。
     *
     * @param textScale 排字的倍率：合成进纹理时给 1（贴图像素空间），直接画上屏幕时给 0（用窗口的界面尺寸）
     */
    static void paint(DrawContext context, CardFace face, String tier, int x, int y, int w, int h, int textScale) {
        CardLayout lay = layout();
        CardLayout.Shape s = lay.shapeOf(face.kind());
        float fx = w / (float) s.masterW();
        float fy = h / (float) s.masterH();

        blit(context, Identifier.of(HeavySeasMod.MOD_ID, CARDS + "frame/" + face.kind() + "/" + tier + ".png"), x, y, w, h);
        Identifier art = Identifier.of(HeavySeasMod.MOD_ID, CARDS + "art/" + face.kind() + "/" + face.id() + ".png");
        if (exists(art)) {
            blit(context, art, x, y, w, h);
        } else {
            // 缺插画：画一块显眼的占位，并说一声。静默退回「什么都不画」会让它与「这张牌本来就空」长得一样。
            context.fill(x + Math.round(40 * fx), y + Math.round(90 * fy), x + w - Math.round(40 * fx),
                    y + Math.round(240 * fy), 0x66A8402C);
            if (MISSING.add(art.toString())) {
                LOGGER.warn("牌面缺插画：{} —— 画了占位", art);
            }
        }

        float badgeLeft = drawBadges(context, s.badges(), face.badges(), tier, x, y, fx, fy, textScale);
        drawTitle(context, s.title(), face.title(), tier, x, y, fx, fy, badgeLeft, textScale);
        drawRoll(context, lay, face, tier, x, y, fx, fy);
    }

    // ---------------------------------------------------------------- 牌名

    private static void drawTitle(DrawContext context, CardLayout.Title t, CardFace.Title title, String tier,
                                  int x, int y, float fx, float fy, float badgeLeftMaster, int textScale) {
        if (!t.tiers().contains(tier)) {
            return;
        }
        int size = t.size().get(tier);
        // 牌名绝不许压到角标：右边界取标题带右沿与角标左沿里靠左的那个，再空出 6 个单位
        float right = Math.min(t.right(), badgeLeftMaster - 6);
        int boxW = Math.max(1, Math.round((right - t.x()) * fx));
        int guiSize = Math.max(1, Math.round(size * fy));
        // 行框高是字号的 1.25 倍（GuiText 的度量）：在标题带里垂直居中
        float mid = (t.top() + t.bottom()) / 2f;
        int top = y + Math.round((mid - size * 1.25f / 2f) * fy);
        GuiText.draw(context, resolve(title).getString(), x + Math.round(t.x() * fx), top, boxW, guiSize, true,
                GuiLanguage.CARD_INK, GuiText.Align.LEFT, 1, false, textScale <= 0, textScale);
    }

    /** 牌名翻成当前语言。航海卡要把点名的角色按 {@code heavyseas.nav.name_sep} 连起来填进 {@code %s}。 */
    static Text resolve(CardFace.Title title) {
        if (title.names().isEmpty()) {
            return Text.translatable(title.key());
        }
        MutableText names = Text.empty();
        for (int i = 0; i < title.names().size(); i++) {
            if (i > 0) {
                names.append(Text.translatable("heavyseas.nav.name_sep"));
            }
            names.append(Text.translatable("heavyseas.character." + title.names().get(i)));
        }
        return Text.translatable(title.key(), names);
    }

    // ---------------------------------------------------------------- 角标

    /** 画角标，返回最左那一枚的左沿（母版单位）—— 牌名据此让开。没有角标时返回标题带右沿。 */
    private static float drawBadges(DrawContext context, CardLayout.Badges b, List<CardFace.Badge> badges, String tier,
                                    int x, int y, float fx, float fy, int textScale) {
        if (badges.isEmpty() || !b.tiers().contains(tier)) {
            return Float.MAX_VALUE;
        }
        double k = b.scaleAt(tier);
        double bw = b.w() * k;
        double bh = b.h() * k;
        double gap = b.gap() * k;
        boolean icons = b.iconTiers().contains(tier);
        int n = badges.size();
        double left = b.right() - n * bw - (n - 1) * gap;
        for (int i = 0; i < n; i++) {
            CardFace.Badge badge = badges.get(i);
            double bx = left + i * (bw + gap);
            int px = x + Math.round((float) (bx * fx));
            int py = y + Math.round((float) (b.top() * fy));
            int pw = Math.round((float) (bw * fx));
            int ph = Math.round((float) (bh * fy));
            blit(context, icon("badge_box"), px, py, pw, ph);
            double digit = b.digitSize() * k;
            double textTop;
            if (icons) {
                double is = b.iconSize() * k;
                blit(context, icon(badge.icon()), px + Math.round((float) ((bw - is) / 2 * fx)),
                        py + Math.round((float) (2.5 * k * fy)), Math.round((float) (is * fx)), Math.round((float) (is * fy)));
                textTop = b.top() + bh - digit * 1.25 - 1 * k;       // 图标在上、数字贴底
            } else {
                textTop = b.top() + (bh - digit * 1.25) / 2;          // 没有图标：数字居中
            }
            GuiText.draw(context, badge.text(), px, y + Math.round((float) (textTop * fy)), pw,
                    Math.max(1, Math.round((float) (digit * fy))), true, GuiLanguage.CARD_INK, GuiText.Align.CENTER,
                    1, false, textScale <= 0, textScale);
        }
        return (float) left;
    }

    // ---------------------------------------------------------------- 口渴排

    private static void drawRoll(DrawContext context, CardLayout lay, CardFace face, String tier,
                                 int x, int y, float fx, float fy) {
        List<CardLayout.Slot> slots = lay.chips(face.kind(), tier, face.roll().size());
        for (int i = 0; i < slots.size(); i++) {
            CardLayout.Slot sl = slots.get(i);
            CardFace.Chip chip = face.roll().get(i);
            int cx = x + Math.round((float) (sl.x() * fx));
            int cy = y + Math.round((float) (sl.y() * fy));
            int d = Math.round((float) (sl.d() * fx));
            switch (chip.type()) {
                case WHO -> {
                    blit(context, portrait(chip.ref()), cx, cy, d, d);
                    blit(context, icon("chip_ring"), cx, cy, d, d);
                }
                case NOT -> {
                    RenderSystem.setShaderColor(1f, 1f, 1f, STRUCK_ALPHA);
                    blit(context, portrait(chip.ref()), cx, cy, d, d);
                    RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
                    blit(context, icon("chip_ring"), cx, cy, d, d);
                    blit(context, icon("strike"), cx, cy, d, d);
                }
                case ICON -> {
                    if (chip.ref().equals(io.github.heavyseasmc.mod.card.CardFaces.EVERYONE)) {
                        blit(context, icon(chip.ref()), cx, cy, d, d);   // 自带底盘
                    } else {
                        blit(context, icon("chip_disc"), cx, cy, d, d);
                        int is = Math.round(d * ICON_IN_DISC);
                        blit(context, icon(chip.ref()), cx + (d - is) / 2, cy + (d - is) / 2, is, is);
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- 取贴图

    private static Identifier icon(String name) {
        return Identifier.of(HeavySeasMod.MOD_ID, CARDS + "icon/" + name + ".png");
    }

    private static Identifier portrait(String character) {
        return Identifier.of(HeavySeasMod.MOD_ID, "textures/gui/portrait/" + character + ".png");
    }

    private static boolean exists(Identifier id) {
        return MinecraftClient.getInstance().getResourceManager().getResource(id).isPresent();
    }

    /** 一律经 {@link CardTexture#smooth}：多级纹理 + 线性过滤，缩小时才不闪。 */
    private static void blit(DrawContext context, Identifier id, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        RenderSystem.enableBlend();
        context.drawTexture(CardTexture.smooth(id), x, y, w, h, 0f, 0f, 1, 1, 1, 1);
    }
}
