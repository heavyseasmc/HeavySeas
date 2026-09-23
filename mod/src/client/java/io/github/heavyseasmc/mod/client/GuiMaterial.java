package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.Identifier;

/**
 * GUI 的材质：界面不再是「模糊背景上的一层半透明黑」，而是一件画出来的东西（ADR-0037）。
 * 材质只在这里定义 —— 与颜色只在 {@link GuiLanguage}、版面常量只在 {@code GameScreen} 是同一条规矩。
 *
 * <p>两个主题共用一份版面骨架，只换这张材质表：
 * <ul>
 *   <li>浅色 · 海图桌：海图纸（经纬细线、右下一枚罗经花）、双线墨框、黄铜压角；标签是同一种纸。</li>
 *   <li>深色 · 船舱木作：上过油的柚木板、旧青铜包角（刻意做暗 —— 亮金只留给「你」）、一汪灯光；
 *       要印字的地方一律是白搪瓷牌，字不直接压在木纹上。</li>
 * </ul>
 *
 * <p>贴图由内部管线的 {@code build_gui_material.py} 从 {@code art/gui/} 的母版烘出，基准是
 * <b>4 个贴图像素 = 1 个 GUI 单位</b>，与牌面同一套载入方式（多级纹理 + 线性过滤），缩放是平滑的。
 * 贴图里不烘字：字一律由 {@link GuiText} 实时排。
 */
final class GuiMaterial {

    /** 贴图像素 / GUI 单位（管线里的 {@code UNIT}）。 */
    private static final int TEXELS_PER_UNIT = 4;
    private static final int SHEET_TEXELS = 512;
    private static final int CORNERS_TEXELS = 192;
    private static final int TAG_TEXELS = 96;
    /** 九宫格的边，贴图像素。 */
    private static final int TAG_BORDER_TEXELS = 24;
    /** 倒计时的框：横向三段（两头各一段不拉伸，中间拉伸）。 */
    private static final int GAUGE_W_TEXELS = 96;
    private static final int GAUGE_H_TEXELS = 32;
    private static final int GAUGE_CAP_TEXELS = 32;
    /** 框四周各占多少 —— 里面那一块由代码填。管线里的 {@code GAUGE_INSET} 必须与它一致。 */
    private static final int GAUGE_INSET_TEXELS = 8;
    /** 头像圈整张多大、其中头像那个圆的直径多大（管线里的 {@code RING_PORTRAIT}）。 */
    private static final int RING_TEXELS = 192;
    private static final int RING_PORTRAIT_TEXELS = 152;
    private static final int KEY_TEXELS = 64;
    private static final int KEY_BORDER_TEXELS = 16;

    /** 倒计时多高，GUI 单位。版面里的 {@code BAR_H} 取它。 */
    static final int GAUGE_H = GAUGE_H_TEXELS / TEXELS_PER_UNIT;
    /**
     * 浅色比例尺一格多长，GUI 单位。
     *
     * <p>❗照样张取：那边一格 40 物理像素（1280×720 · 界面尺寸 3 → 约 13 个单位）。
     * 原先写的 8 只有它的六成，格子密到整条读成一块实心黑 —— 用户 2026-09-22 看实拍时说
     * 「倒计时带怎么和设计稿里的出入那么大」，这是其中一条。
     */
    private static final int GAUGE_BLOCK = 13;
    /** 比例尺上墨块的不透明度：满格时整条曾是全屏最重的一块。朱砂那一档不走它。 */
    private static final int GAUGE_BLOCK_ALPHA = 0xC8;

    /** 材质板离舞台四边多远。 */
    static final int SHEET_MARGIN = 5;
    /** 材质板的框线从板边往里多远 —— 内容别画到这条线外面去。 */
    static final int SHEET_FRAME_INSET = 4;

    private GuiMaterial() {
    }

    private static Identifier texture(String name) {
        String theme = GuiLanguage.theme() == GuiLanguage.Theme.DARK ? "dark" : "light";
        return CardTexture.smooth(Identifier.of(HeavySeasMod.MOD_ID, "textures/gui/material/" + theme + "/" + name + ".png"));
    }

    /**
     * 压暗整个世界。不调 {@code Screen.renderBackground}：1.21.1 里那一层是模糊加压暗，
     * 而「模糊 + 半透明黑」正是界面读起来像深色模式应用的原因。暗部带色相，不用中性灰。
     */
    static void dimWorld(DrawContext context) {
        context.fill(0, 0, context.getScaledWindowWidth(), context.getScaledWindowHeight(), GuiLanguage.dimmer());
    }

    /** 一张材质板：平铺的地、框线、四个角、一处点缀。不透明 —— 聊天从底下透上来的问题由它自然兜住。 */
    static void sheet(DrawContext context, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        context.fill(x + 2, y + 3, x + w + 2, y + h + 3, 0x66000000);                 // 落在世界上的影子
        Identifier sheet = texture("sheet");
        int tile = SHEET_TEXELS / TEXELS_PER_UNIT;
        for (int ty = 0; ty < h; ty += tile) {
            for (int tx = 0; tx < w; tx += tile) {
                int tw = Math.min(tile, w - tx);
                int th = Math.min(tile, h - ty);
                context.drawTexture(sheet, x + tx, y + ty, tw, th, 0f, 0f,
                        tw * TEXELS_PER_UNIT, th * TEXELS_PER_UNIT, SHEET_TEXELS, SHEET_TEXELS);
            }
        }
        accent(context, x, y, w, h);
        frame(context, x, y, w, h);
        corners(context, x, y, w, h);
    }

    /** 点缀：浅色是右下角的罗经花，深色是桌面中间那一汪灯光。都很淡，不压字。 */
    private static void accent(DrawContext context, int x, int y, int w, int h) {
        boolean dark = GuiLanguage.theme() == GuiLanguage.Theme.DARK;
        int size = dark ? Math.max(w, h) : Math.min(w, h) * 2 / 5;
        int ax = dark ? x + (w - size) / 2 : x + w - size * 3 / 4;       // 罗经花只露大半个，压在右下角的框线里
        int ay = dark ? y + (h - size) / 2 : y + h - size * 3 / 4;
        context.enableScissor(x, y, x + w, y + h);
        context.setShaderColor(1f, 1f, 1f, dark ? 0.30f : 0.07f);      // 深色那汪灯光再亮就吃掉压在它上面的字（实拍过）
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        context.drawTexture(texture("accent"), ax, ay, size, size, 0f, 0f, 1, 1, 1, 1);
        context.setShaderColor(1f, 1f, 1f, 1f);
        context.disableScissor();
    }

    /** 框线用代码画：整数个 GUI 单位的直线在任何界面尺寸下都是锐利的，不必进贴图。颜色取自调色板，与贴图同源。 */
    private static void frame(DrawContext context, int x, int y, int w, int h) {
        int line = GuiLanguage.frame();
        int i = SHEET_FRAME_INSET;
        context.drawBorder(x, y, w, h, GuiLanguage.rim());
        context.drawBorder(x + i - 2, y + i - 2, w - 2 * (i - 2), h - 2 * (i - 2), line);
        context.drawBorder(x + i - 1, y + i - 1, w - 2 * (i - 1), h - 2 * (i - 1), line);
        context.drawBorder(x + i + 1, y + i + 1, w - 2 * (i + 1), h - 2 * (i + 1), line);
    }

    private static void corners(DrawContext context, int x, int y, int w, int h) {
        Identifier id = texture("corners");
        int cell = CORNERS_TEXELS / 2;
        int size = cell / TEXELS_PER_UNIT;
        if (w < 3 * size || h < 3 * size) {
            return;                                           // 板太小就不压角：角比板还抢眼
        }
        int[][] at = {{x - 1, y - 1, 0, 0}, {x + w - size + 1, y - 1, cell, 0},
                {x - 1, y + h - size + 1, 0, cell}, {x + w - size + 1, y + h - size + 1, cell, cell}};
        for (int[] c : at) {
            context.drawTexture(id, c[0], c[1], size, size, c[2], c[3], cell, cell, CORNERS_TEXELS, CORNERS_TEXELS);
        }
    }

    /**
     * 座位轨与舞台之间那一道**通栏线**（样张里是木板之间的一道缝）。
     *
     * <p>它取代了第四刀为截图判据加的舞台角标 —— 角标是我加的，稿子里没有（§7.12）。
     * 这道线稿子里本来就有，而且**只在这一处出现一次**，所以 `band_check.py` 改认它：
     * 一整行同一个颜色、跨满舞台宽，别的地方不会有。
     *
     * <p>用 {@link GuiLanguage#rim()}（板最外一圈那个色）：它不与任何一处的字同色 ——
     * 与墨色同色的话，每一行正文都会混进判据里。
     */
    static void bandRule(DrawContext context, int x, int y, int w) {
        if (w <= 0) {
            return;
        }
        context.fill(x, y, x + w, y + 1, GuiLanguage.rim());
    }

    /**
     * 一块标签（九宫格）：浅色是纸签，深色是搪瓷牌。按钮、提示签、航海日志的底都是它。
     * ❗标签永远是<b>浅底</b>，印在上面的字要用 {@link GuiLanguage#onTag} 换成深墨。
     */
    static void tag(DrawContext context, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        context.fill(x + 1, y + 2, x + w + 1, y + h + 2, 0x55000000);                 // 标签落在板上的影子
        nineSlice(context, texture("tag"), x, y, w, h, TAG_TEXELS, TAG_BORDER_TEXELS);
    }

    /** 提示签上那个尖角多高、多宽（宽 = 2 倍高），GUI 单位。 */
    static final int PLATE_POINT = 5;

    /**
     * 提示签：一块标签，顶上伸出一个指着牌的尖角（样张里牌下面吊的那张白搪瓷牌）。
     *
     * <p>❗尖角不另取颜色，而是<b>一条一条地画标签贴图正中那一个纹素</b> ——
     * 标签是九宫格贴图，浅深两个主题各一张，另起一个色值迟早与贴图漂开
     * （「同一件东西两种画法」正是 §7.12 要收掉的毛病）。从贴图上取，换主题时它自己就跟着换。
     *
     * @param pointX 尖角尖端的 x（一般是那张牌的横中线），会被夹进签子自己的宽度里；
     *               {@code < 0} 表示不要尖角（签子在牌<b>旁边</b>而不是下面时就是这样）
     */
    static void plate(DrawContext context, int x, int y, int w, int h, int pointX) {
        if (w <= 0 || h <= 0) {
            return;
        }
        if (pointX < 0) {
            tag(context, x, y, w, h);
            return;
        }
        int tip = Math.max(x + PLATE_POINT + 1, Math.min(x + w - PLATE_POINT - 1, pointX));
        Identifier id = texture("tag");
        int mid = TAG_TEXELS / 2;
        for (int i = 0; i < PLATE_POINT; i++) {
            int half = PLATE_POINT - i;
            context.drawTexture(id, tip - half, y - PLATE_POINT + i, 2 * half, 1, mid, mid, 1, 1, TAG_TEXELS, TAG_TEXELS);
        }
        tag(context, x, y, w, h);
    }

    /**
     * 一枚键帽（九宫格）：印着键名的小块，底下一道厚边。与标签一样永远是浅底，键名用 {@link GuiLanguage#onTag} 换色。
     * 厚边占最底下 1 个单位 —— 键名要在它上面那一块里居中。
     */
    static void keycap(DrawContext context, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) {
            return;
        }
        nineSlice(context, texture("key"), x, y, w, h, KEY_TEXELS, KEY_BORDER_TEXELS);
    }

    /** 键帽比它里面的字宽多少、高多少（两边的边与底下那道厚边）。 */
    static final int KEY_PAD_X = 4;
    static final int KEY_PAD_TOP = 1;
    static final int KEY_PAD_BOTTOM = 3;

    private static void nineSlice(DrawContext context, Identifier id, int x, int y, int w, int h, int texels, int bt) {
        int b = Math.min(bt / TEXELS_PER_UNIT, Math.min(w, h) / 2);
        int mid = texels - 2 * bt;
        int[] xs = {x, x + b, x + w - b};
        int[] ws = {b, w - 2 * b, b};
        int[] us = {0, bt, texels - bt};
        int[] uw = {bt, mid, bt};
        int[] ys = {y, y + b, y + h - b};
        int[] hs = {b, h - 2 * b, b};
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                if (ws[c] > 0 && hs[r] > 0) {
                    context.drawTexture(id, xs[c], ys[r], ws[c], hs[r], us[c], us[r], uw[c], uw[r], texels, texels);
                }
            }
        }
    }

    /**
     * 倒计时：浅色是海图比例尺（墨块一格一格被吃掉，上沿一排刻度），深色是青铜框玻璃液位管（液面往左退）。
     * 贴图只是盖在上面的那个框；剩多少用代码填 —— 整数个 GUI 单位的矩形在任何界面尺寸下都是锐利的。
     *
     * <p>❗语义照旧：最后一段用朱砂（{@code urgent}），它只给紧迫。平时浅色用墨、深色用铜绿 —— 比例尺本来就是墨印的。
     *
     * @param frac 还剩多少，0–1
     */
    static void gauge(DrawContext context, int x, int y, int w, float frac, boolean urgent) {
        int inset = GAUGE_INSET_TEXELS / TEXELS_PER_UNIT;
        int cap = GAUGE_CAP_TEXELS / TEXELS_PER_UNIT;
        if (w < 2 * cap) {
            return;                                            // 两头都放不下就不画：画出半个框比不画更怪
        }
        boolean dark = GuiLanguage.theme() == GuiLanguage.Theme.DARK;
        int ix = x + inset;
        int iy = y + inset;
        int iw = w - 2 * inset;
        int ih = GAUGE_H - 2 * inset;
        int left = Math.round(iw * Math.max(0f, Math.min(1f, frac)));
        int color = urgent ? GuiLanguage.cinnabar() : dark ? GuiLanguage.verdigris() : GuiLanguage.ink();
        if (dark) {
            context.fill(ix, iy, ix + iw, iy + ih, GuiLanguage.ground());
            context.fill(ix, iy, ix + left, iy + ih, color);
        } else {
            // 满格那一刻整条比例尺是全屏最重的一块（2026-09-22 实拍），所以墨块压淡一档；
            // 紧迫的朱砂不淡 —— 它要压得住，那正是这个语义要做的事。
            int block = urgent ? color : (color & 0xFFFFFF) | (GAUGE_BLOCK_ALPHA << 24);
            for (int bx = 0; bx < left; bx += 2 * GAUGE_BLOCK) {
                context.fill(ix + bx, iy, ix + Math.min(bx + GAUGE_BLOCK, left), iy + ih, block);
            }
            if (left > 0 && left < iw) {
                context.fill(ix + left - 1, iy, ix + left, iy + ih, color);      // 吃到哪了：空白格里也看得出
            }
            // 刻度：每半格一道短的、每一格一道长的，压在框线上沿。只往上探 1 个单位。
            int half = GAUGE_BLOCK / 2;
            for (int k = 0; k * half <= iw; k++) {
                int tx = Math.min(ix + k * half, ix + iw - 1);
                context.fill(tx, k % 2 == 0 ? y - 1 : y, tx + 1, y + 1, GuiLanguage.frame());
            }
        }
        Identifier id = texture("gauge");
        int[] xs = {x, x + cap, x + w - cap};
        int[] ws = {cap, w - 2 * cap, cap};
        int[] us = {0, GAUGE_CAP_TEXELS, GAUGE_W_TEXELS - GAUGE_CAP_TEXELS};
        int[] uw = {GAUGE_CAP_TEXELS, GAUGE_W_TEXELS - 2 * GAUGE_CAP_TEXELS, GAUGE_CAP_TEXELS};
        for (int c = 0; c < 3; c++) {
            if (ws[c] > 0) {
                context.drawTexture(id, xs[c], y, ws[c], GAUGE_H, us[c], 0, uw[c], GAUGE_H_TEXELS, GAUGE_W_TEXELS, GAUGE_H_TEXELS);
            }
        }
    }

    /** 头像圈比头像那个圆往外多出多少，GUI 单位。排版要给它留出来。 */
    static int ringMargin(int diameter) {
        return Math.round(diameter * (RING_TEXELS - RING_PORTRAIT_TEXELS) / (2f * RING_PORTRAIT_TEXELS));
    }

    /**
     * 一枚头像：从角色卡上裁下来的圆，外面一圈。座位轨用它。
     *
     * @param x     头像那个圆的左上角（圈还要往外多出 {@link #ringMargin}）
     * @param mark  圈上的语义色：金 = 你，铜绿 = 正轮到；{@code 0} = 不着色，只有主题自己的那一圈
     * @param alpha 头像的不透明度：还没轮到的人淡下去
     */
    static void avatar(DrawContext context, String characterId, int x, int y, int diameter, int mark, float alpha) {
        if (diameter <= 0 || characterId.isEmpty()) {
            return;
        }
        Identifier portrait = CardTexture.smooth(
                Identifier.of(HeavySeasMod.MOD_ID, "textures/gui/portrait/" + characterId + ".png"));
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        context.setShaderColor(1f, 1f, 1f, alpha);
        context.drawTexture(portrait, x, y, diameter, diameter, 0f, 0f, 1, 1, 1, 1);
        context.setShaderColor(1f, 1f, 1f, 1f);
        int m = ringMargin(diameter);
        int size = diameter + 2 * m;
        context.drawTexture(texture("ring"), x - m, y - m, size, size, 0f, 0f, 1, 1, 1, 1);
        if (mark != 0) {
            context.setShaderColor((mark >> 16 & 0xFF) / 255f, (mark >> 8 & 0xFF) / 255f, (mark & 0xFF) / 255f, 1f);
            context.drawTexture(texture("ring_mark"), x - m, y - m, size, size, 0f, 0f, 1, 1, 1, 1);
            context.setShaderColor(1f, 1f, 1f, 1f);
        }
    }
}
