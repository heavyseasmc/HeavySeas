package io.github.heavyseasmc.mod.ui;

/**
 * 主画面 HUD 的装饰件：每一件都是样张 B 形制的 CSS 由浏览器渲出来的贴图（内部管线 {@code build_hud_parts.py}）。
 *
 * <p>木板 · 搪瓷 · 金签 · 键帽 · 头像圈 · 舷窗圈 —— 阴影、渐变、圆角都照样张原样，游戏里只按下面这张表拼：
 * 盒子多大（稿子像素）、四周留了多少给阴影（{@code margin}）、怎么拼（整张 · 横向三段 · 竖向三段）。
 * 每件烘了 1 · 2 · 3 倍三份，客户端按 {@link HudLayout#k()} 挑不小于它的那一份。
 *
 * <p>❗这张表与管线脚本里那张必须一致；{@code HudPartTest} 拿公开仓库里的 PNG 尺寸去对它 —— 两边不同源。
 */
public enum HudPart {

    PLAQUE("plaque", true, 312, 139, 30, Slice.FIXED, 0),
    RAIL("rail", true, 498, 57, 30, Slice.H3, 60),
    LOGTAB("logtab", true, 50, 50, 30, Slice.FIXED, 0),
    PIP_ON("pip_on", true, 13, 13, 3, Slice.FIXED, 0),
    PIP_OFF("pip_off", true, 13, 13, 3, Slice.FIXED, 0),
    RIBBON("ribbon", false, 92, 44, 20, Slice.FIXED, 0),
    KEY("key", false, 26, 26, 4, Slice.H3, 8),
    RIBBON_KEY("ribbon_key", false, 26, 26, 2, Slice.H3, 8),
    TOK40_PLAIN("tok40_plain", false, 40, 40, 12, Slice.FIXED, 0),
    TOK40_YOU("tok40_you", false, 40, 40, 12, Slice.FIXED, 0),
    TOK40_ACT("tok40_act", false, 40, 40, 12, Slice.FIXED, 0),
    TOK40_CINN("tok40_cinn", false, 40, 40, 12, Slice.FIXED, 0),
    TOK50_PLAIN("tok50_plain", false, 50, 50, 12, Slice.FIXED, 0),
    TOK50_YOU("tok50_you", false, 50, 50, 12, Slice.FIXED, 0),
    TOK50_ACT("tok50_act", false, 50, 50, 12, Slice.FIXED, 0),
    TOK50_CINN("tok50_cinn", false, 50, 50, 12, Slice.FIXED, 0),
    MEDAL("medal", false, 64, 64, 24, Slice.FIXED, 0),
    PHASE_ON("phase_on", false, 38, 38, 8, Slice.FIXED, 0),
    BADGE("badge", false, 34, 24, 4, Slice.H3, 12),
    COUNT("count", false, 20, 20, 4, Slice.H3, 9),
    ENAMEL("enamel", false, 348, 200, 16, Slice.V3, 24),
    TIP_POINTER("tip_pointer", false, 16, 16, 6, Slice.FIXED, 0),
    CARD_SHADOW("card_shadow", false, 348, 249, 20, Slice.FIXED, 0),
    // 图标：样张 .ic 的线稿在实际尺寸上渲，白色，着色由客户端给（GuiMaterial.hudIcon）
    IC_SUN("ic_sun", false, 24, 24, 1, Slice.FIXED, 0),
    IC_CRATE("ic_crate", false, 24, 24, 1, Slice.FIXED, 0),
    IC_FIST("ic_fist", false, 24, 24, 1, Slice.FIXED, 0),
    IC_BOAT("ic_boat", false, 24, 24, 1, Slice.FIXED, 0),
    IC_DROP("ic_drop", false, 24, 24, 1, Slice.FIXED, 0),
    IC_EYE("ic_eye", false, 24, 24, 1, Slice.FIXED, 0),
    IC_CARD("ic_card", false, 24, 24, 1, Slice.FIXED, 0),
    IC_BELL("ic_bell", false, 24, 24, 1, Slice.FIXED, 0),
    IC_BOOK("ic_book", false, 24, 24, 1, Slice.FIXED, 0),
    IC_PIN("ic_pin", false, 24, 24, 1, Slice.FIXED, 0),
    IC_HEART("ic_heart", false, 24, 24, 1, Slice.FIXED, 0),
    IC_HATE("ic_hate", false, 24, 24, 1, Slice.FIXED, 0),
    IC_LAUREL("ic_laurel", false, 24, 24, 1, Slice.FIXED, 0),
    IC_BUOY("ic_buoy", false, 24, 24, 1, Slice.FIXED, 0),
    IC_GEM("ic_gem", false, 24, 24, 1, Slice.FIXED, 0),
    IC_HELM18("ic_helm18", false, 18, 18, 1, Slice.FIXED, 0),
    IC_GULL("ic_gull", false, 26, 20, 1, Slice.FIXED, 0),
    // 对局各面（样张 b-3 · b-4 · b-5 共用骨架）：板 · 压暗 · 更多尺寸的头像圈 · 倒计时 · 按钮 · 选中框
    SHEET("sheet", true, 1180, 652, 60, Slice.NINE, 150, 2),
    DIMMER("dimmer", true, 1280, 720, 0, Slice.FIXED, 0, 1),
    TOK38_PLAIN("tok38_plain", false, 38, 38, 12, Slice.FIXED, 0),
    TOK38_YOU("tok38_you", false, 38, 38, 12, Slice.FIXED, 0),
    TOK38_ACT("tok38_act", false, 38, 38, 12, Slice.FIXED, 0),
    TOK38_CINN("tok38_cinn", false, 38, 38, 12, Slice.FIXED, 0),
    TOK42_PLAIN("tok42_plain", false, 42, 42, 12, Slice.FIXED, 0),
    TOK42_YOU("tok42_you", false, 42, 42, 12, Slice.FIXED, 0),
    TOK42_ACT("tok42_act", false, 42, 42, 12, Slice.FIXED, 0),
    TOK42_CINN("tok42_cinn", false, 42, 42, 12, Slice.FIXED, 0),
    TOK46_PLAIN("tok46_plain", false, 46, 46, 12, Slice.FIXED, 0),
    TOK46_YOU("tok46_you", false, 46, 46, 12, Slice.FIXED, 0),
    TOK46_ACT("tok46_act", false, 46, 46, 12, Slice.FIXED, 0),
    TOK46_CINN("tok46_cinn", false, 46, 46, 12, Slice.FIXED, 0),
    TOK48_PLAIN("tok48_plain", false, 48, 48, 12, Slice.FIXED, 0),
    TOK48_YOU("tok48_you", false, 48, 48, 12, Slice.FIXED, 0),
    TOK48_ACT("tok48_act", false, 48, 48, 12, Slice.FIXED, 0),
    TOK48_CINN("tok48_cinn", false, 48, 48, 12, Slice.FIXED, 0),
    COUNT_BAR("count_bar", false, 462, 18, 8, Slice.H3, 12),
    COUNT_FILL("count_fill", false, 462, 18, 0, Slice.H3, 12),
    COUNT_FILL_URGENT("count_fill_urgent", false, 462, 18, 0, Slice.H3, 12),
    BTN("btn", false, 120, 49, 12, Slice.H3, 16),
    BTN_FOCUS("btn_focus", false, 120, 49, 12, Slice.H3, 16),
    CARD_SEL("card_sel", false, 124, 174, 10, Slice.NINE, 14),
    // D1 (a) 座位的公开状态（ADR-0048）：取自 D1 样张页 —— 印章 · 「昏」签 · 两种压暗 · 波纹 · 叉；两种头像尺寸各一套
    SEAL("seal", false, 34, 20, 3, Slice.H3, 11),
    SEAL_HURT("seal_hurt", false, 34, 20, 3, Slice.H3, 11),
    TAG("tag", false, 20, 18, 1, Slice.H3, 5),
    SHADE_OUT40("shade_out40", false, 40, 40, 0, Slice.FIXED, 0),
    SHADE_GONE40("shade_gone40", false, 40, 40, 0, Slice.FIXED, 0),
    IC_WAVES40("ic_waves40", false, 25, 11, 1, Slice.FIXED, 0),
    IC_CROSS40("ic_cross40", false, 11, 11, 1, Slice.FIXED, 0),
    SHADE_OUT46("shade_out46", false, 46, 46, 0, Slice.FIXED, 0),
    SHADE_GONE46("shade_gone46", false, 46, 46, 0, Slice.FIXED, 0),
    IC_WAVES46("ic_waves46", false, 29, 13, 1, Slice.FIXED, 0),
    IC_CROSS46("ic_cross46", false, 13, 13, 1, Slice.FIXED, 0),
    // 有人落海那一瞬四边一闪的朱砂（ADR-0048，样张没画这一态）：整屏一张，只烘 1 倍（平滑的晕，放大不糊）
    FLASH("flash", false, 1280, 720, 0, Slice.FIXED, 0, 1);

    /** 整张画；横向三段（两头不伸缩，中间拉）；竖向三段；九宫格（两个方向各三段）。 */
    public enum Slice { FIXED, H3, V3, NINE }

    /** 烘了哪几倍（稿子像素 × 倍数 = 贴图像素）。 */
    public static final int[] BAKES = {1, 2, HudPart.TOP_BAKE};
    /** 最高烘到几倍（枚举的构造器里只能引用编译期常量，所以单列一个）。 */
    static final int TOP_BAKE = 3;

    private final String id;
    private final boolean themed;
    private final int w;
    private final int h;
    private final int margin;
    private final Slice slice;
    private final int cap;
    private final int maxBake;

    HudPart(String id, boolean themed, int w, int h, int margin, Slice slice, int cap) {
        this(id, themed, w, h, margin, slice, cap, TOP_BAKE);
    }

    /** {@code maxBake}：这一件最多烘到几倍（整屏的板与压暗只烘到 2 倍 / 1 倍，见管线脚本 part() 的 scales）。 */
    HudPart(String id, boolean themed, int w, int h, int margin, Slice slice, int cap, int maxBake) {
        this.id = id;
        this.themed = themed;
        this.w = w;
        this.h = h;
        this.margin = margin;
        this.slice = slice;
        this.cap = cap;
        this.maxBake = maxBake;
    }

    /** 这一件烘了到几倍（1 起）。 */
    public int maxBake() {
        return maxBake;
    }

    /** 这一件在系数 {@code k} 下用哪一倍：{@link #bakeFor} 再夹到它自己烘到的那一倍。 */
    public int bakeFor(double k, boolean perPart) {
        return Math.min(maxBake, bakeFor(k));
    }

    public String id() {
        return id;
    }

    /** 两个主题各一份（木板与体力点）；否则两个主题共用 {@code common/} 那一份。 */
    public boolean themed() {
        return themed;
    }

    public int w() {
        return w;
    }

    public int h() {
        return h;
    }

    public int margin() {
        return margin;
    }

    public Slice slice() {
        return slice;
    }

    /** 三段拼时，两头各多长（稿子像素，不含 margin）。 */
    public int cap() {
        return cap;
    }

    /** 挑哪一倍：不小于 {@code k} 的最小那一份，超过 3 用 3（再往上多级纹理只会更糊，不会更清楚）。 */
    public static int bakeFor(double k) {
        int want = (int) Math.ceil(k - 1e-6);
        return Math.max(BAKES[0], Math.min(BAKES[BAKES.length - 1], want));
    }

    /** 资源路径（不含命名空间）。{@code theme} 是 {@code dark} / {@code light}，不分主题的件忽略它。 */
    public String path(String theme, int bake) {
        return "textures/gui/hud/" + (themed ? theme : "common") + "/" + id + "_" + bake + "x.png";
    }

    /** 这一倍贴图多大（贴图像素）。 */
    public int texW(int bake) {
        return (w + 2 * margin) * bake;
    }

    public int texH(int bake) {
        return (h + 2 * margin) * bake;
    }
}
