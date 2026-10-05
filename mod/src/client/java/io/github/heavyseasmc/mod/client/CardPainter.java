package io.github.heavyseasmc.mod.client;

import com.mojang.blaze3d.platform.GlStateManager;
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
        drawRoll(context, lay, face, tier, x, y, fx, fy, textScale);
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
        int ink = switch (title.tone()) {
            case HARM -> GuiLanguage.CARD_HARM;
            case QUIET -> GuiLanguage.CARD_QUIET;
            case INK -> GuiLanguage.CARD_INK;
        };
        GuiText.draw(context, resolve(title).getString(), x + Math.round(t.x() * fx), top, boxW, guiSize, true,
                ink, GuiText.Align.LEFT, 1, false, textScale <= 0, textScale);
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

    /**
     * 数字的字面中线离行框上沿多远（字号的倍数）：{@link GuiText} 把基线放在行框上沿下 1.005 倍字号处，
     * 数字高约 0.71 倍字号，字面中线因此在 0.65 倍处。角标一行按这条线对齐图标与箭头。
     */
    private static final double DIGIT_MID = 0.65;
    /** 符号箭头与数字之间的空（母版单位，随档放大）。 */
    private static final double SIGN_GAP = 1.0;

    /** 一枚角标拆开的样子：图标 · 符号（+1 上箭头、-1 下箭头、0 没有）· 数字，各自多宽（母版单位，已含这一档的放大）。 */
    private record Piece(String icon, double iconW, int sign, double signW, String digits, double digitsW) {

        double width(CardLayout.Badges b, double k) {
            double w = digitsW;
            if (iconW > 0) {
                w += iconW + b.iconGap() * k;
            }
            if (sign != 0) {
                w += signW + SIGN_GAP * k;
            }
            return w;
        }
    }

    private static Piece piece(CardLayout.Badges b, CardFace.Badge badge, String tier, float fx, int textScale) {
        double k = b.scaleAt(tier);
        String text = badge.text();
        int sign = text.startsWith("+") ? 1 : text.startsWith("-") ? -1 : 0;
        String digits = sign == 0 ? text : text.substring(1);
        double iconW = b.iconTiers().contains(tier) ? b.iconSize() * k * CardTexture.aspect(icon(badge.icon())) : 0;
        double signW = sign == 0 ? 0 : b.signSize() * k * CardTexture.aspect(signIcon(sign));
        int guiSize = Math.max(1, Math.round((float) (b.digitSize() * k * fx)));
        double digitsW = GuiText.drawnWidth(digits, guiSize, true, textScale <= 0, textScale) / fx;
        return new Piece(badge.icon(), iconW, sign, signW, digits, digitsW);
    }

    /**
     * 一组角标排出来多宽（母版单位）。画与量走同一段：牌名让多少（{@link CardNameFit}）就是这里排出来的宽。
     *
     * @param fx 母版单位 → 排字坐标的倍率（合成进贴图时是贴图宽 / 母版宽）
     */
    static double badgeUnits(CardLayout.Badges b, List<CardFace.Badge> badges, String tier, float fx, int textScale) {
        if (badges.isEmpty() || !b.tiers().contains(tier)) {
            return 0;
        }
        double k = b.scaleAt(tier);
        double total = (badges.size() - 1) * b.gap() * k;
        for (CardFace.Badge badge : badges) {
            total += piece(b, badge, tier, fx, textScale).width(b, k);
        }
        return total;
    }

    /**
     * 画角标（ADR-0090）：每枚是「图标 · 符号箭头 · 数字」一行，不加框，右对齐到 {@code right}。
     * 三档都画图标 —— 只剩数字的「3」与「1」玩家读不出是什么（用户 2026-10-05）。
     * 返回最左那一枚的左沿（母版单位）—— 牌名据此让开。没有角标时返回标题带右沿。
     */
    private static float drawBadges(DrawContext context, CardLayout.Badges b, List<CardFace.Badge> badges, String tier,
                                    int x, int y, float fx, float fy, int textScale) {
        if (badges.isEmpty() || !b.tiers().contains(tier)) {
            return Float.MAX_VALUE;
        }
        double k = b.scaleAt(tier);
        double left = b.right() - badgeUnits(b, badges, tier, fx, textScale);
        double mid = b.top() + b.h() * k / 2;
        double cx = left;
        for (CardFace.Badge badge : badges) {
            Piece p = piece(b, badge, tier, fx, textScale);
            if (p.iconW() > 0) {
                double ih = b.iconSize() * k;
                blit(context, icon(p.icon()), x + Math.round((float) (cx * fx)), y + Math.round((float) ((mid - ih / 2) * fy)),
                        Math.round((float) (p.iconW() * fx)), Math.round((float) (ih * fy)));
                cx += p.iconW() + b.iconGap() * k;
            }
            if (p.sign() != 0) {
                double sh = b.signSize() * k;
                blit(context, signIcon(p.sign()), x + Math.round((float) (cx * fx)),
                        y + Math.round((float) ((mid - sh / 2) * fy)), Math.round((float) (p.signW() * fx)),
                        Math.round((float) (sh * fy)));
                cx += p.signW() + SIGN_GAP * k;
            }
            double digit = b.digitSize() * k;
            int guiSize = Math.max(1, Math.round((float) (digit * fx)));   // 与量宽那一路（piece）同一个字号
            // 框宽给足（量出来的宽再多一点）：放得下就不会缩字号，与量的那一路字号相同
            GuiText.draw(context, p.digits(), x + Math.round((float) (cx * fx)),
                    y + Math.round((float) ((mid - digit * DIGIT_MID) * fy)),
                    (int) Math.ceil(p.digitsW() * fx) + 2, guiSize, true, GuiLanguage.CARD_INK, GuiText.Align.LEFT,
                    1, false, textScale <= 0, textScale);
            cx += p.digitsW() + b.gap() * k;
        }
        return (float) left;
    }

    // ---------------------------------------------------------------- 信息带（口渴排 · 效果图示）

    /** 数字在圆盘里占多高（与图示的线稿同一档分量）。 */
    private static final float COUNT_IN_DISC = 0.46f;

    private static void drawRoll(DrawContext context, CardLayout lay, CardFace face, String tier,
                                 int x, int y, float fx, float fy, int textScale) {
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
                case ICON -> drawIconChip(context, chip.ref(), cx, cy, d);
                case STRUCK -> {
                    // 划掉 = 这一回合不算 / 没有（ADR-0040）：与「除他之外」同一道朱砂斜线
                    drawIconChip(context, chip.ref(), cx, cy, d);
                    blit(context, icon("strike"), cx, cy, d, d);
                }
                case ARROW -> {
                    // 箭头 = 变成。没有圆盘：它是两枚图示之间的连接，不是第三件东西
                    int is = Math.round(d * ICON_IN_DISC);
                    blit(context, icon(io.github.heavyseasmc.mod.card.CardFaces.ARROW),
                            cx + (d - is) / 2, cy + (d - is) / 2, is, is);
                }
                case COUNT -> {
                    // 数字（+1）：与角标同一套排字，写的是符号不是话，与语言无关
                    blit(context, icon("chip_disc"), cx, cy, d, d);
                    int size = Math.max(1, Math.round(d * COUNT_IN_DISC));
                    GuiText.draw(context, chip.ref(), cx, cy + Math.round((d - size * 1.25f) / 2f), d, size, true,
                            GuiLanguage.CARD_INK, GuiText.Align.CENTER, 1, false, textScale <= 0, textScale);
                }
            }
        }
    }

    /** 一枚图示：纸色圆盘 + 木刻小图（「全员」也一样，ADR-0090 起它不再自带底盘）。 */
    private static void drawIconChip(DrawContext context, String ref, int cx, int cy, int d) {
        blit(context, icon("chip_disc"), cx, cy, d, d);
        // 按贴图的宽高比放进圆盘：长边占 ICON_IN_DISC —— 海鸥是扁的，压成方的就成了一团（ADR-0090）
        int is = Math.round(d * ICON_IN_DISC);
        float a = CardTexture.aspect(icon(ref));
        int iw = a >= 1 ? is : Math.round(is * a);
        int ih = a >= 1 ? Math.round(is / a) : is;
        blit(context, icon(ref), cx + (d - iw) / 2, cy + (d - ih) / 2, iw, ih);
    }

    // ---------------------------------------------------------------- 取贴图

    /**
     * 界面上借用卡牌的木刻小图（ADR-0090）：同一个意思，牌上与界面上画同一样东西 —— 计分的「财宝」就是财宝牌上的钱币，
     * 座位印章的体型就是角标上的秤砣（Codex 复核：计分换成钻石、印章只有「4/4」，都与牌对不上）。
     * 按贴图的宽高比画，高 {@code h}，返回画了多宽。
     */
    static int drawCardIcon(DrawContext context, String name, int x, int y, int h, float alpha) {
        int w = cardIconWidth(name, h);
        RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
        blit(context, icon(name), x, y, w, h);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        return w;
    }

    /** {@link #drawCardIcon} 画成高 {@code h} 时有多宽。 */
    static int cardIconWidth(String name, int h) {
        return Math.max(1, Math.round(h * CardTexture.aspect(icon(name))));
    }

    /** 符号箭头：{@code +} 画实心上箭头，{@code -} 画实心下箭头（用户 2026-10-05：「➕改成实心上箭头」）。 */
    private static Identifier signIcon(int sign) {
        return sign > 0 ? icon("arrow_up") : icon("arrow_down");
    }

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
    /**
     * 画一层（边框层 · 画层）。
     *
     * <p>❗混合要分开写透明度那一路：Minecraft 的 {@code defaultBlendFunc} 是
     * {@code (SRC_ALPHA, ONE_MINUS_SRC_ALPHA, ONE, ZERO)} —— 透明度<b>被最后画的那一层覆盖</b>，不是叠加。
     * 画上屏幕时没人看帧缓冲的透明度，看不出来；合成进牌的贴图（{@link CardComposite}）时，
     * 画层里 22% 不透明的那一片海天就把整条插画带的透明度改成了 22%，牌的中段变成半透明，
     * 背后是什么颜色就透出什么颜色 —— 2026-09-30 主画面展开的天候卡压在夜空上，那一条整个发灰（实拍 (92,93,93)，
     * 纸上应为 (194,194,171)）。透明度按 {@code (ONE, ONE_MINUS_SRC_ALPHA)} 叠加，纸是不透明的，合成出来就还是不透明。
     */
    private static void blit(DrawContext context, Identifier id, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(GlStateManager.SrcFactor.SRC_ALPHA, GlStateManager.DstFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SrcFactor.ONE, GlStateManager.DstFactor.ONE_MINUS_SRC_ALPHA);
        context.drawTexture(CardTexture.smooth(id), x, y, w, h, 0f, 0f, 1, 1, 1, 1);
        RenderSystem.defaultBlendFunc();
    }
}
