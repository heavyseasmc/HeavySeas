package io.github.heavyseasmc.mod.ui;

/**
 * 主画面 HUD 的几何：照样张 b-1（收着）与 b-2（展开）逐项取值，一个坐标系、一套锚点、一个缩放系数。
 *
 * <h2>坐标系：稿子像素</h2>
 * 样张是 1280×720 的游戏画面，按界面尺寸 3（这个窗口「自动」给的就是 3）画的 —— 所以稿子上的 1 px
 * 就是那个窗口里的 1 个物理像素。这里的常量全部是<b>稿子像素</b>，取自样张页 {@code index.html} 的 B 形制
 * （{@code .plaque} · {@code .rail} · {@code .dock} · {@code .drawer} 那几条 CSS），每一处都注了出处。
 * 换成屏幕上的物理像素只乘一个系数 {@link #k()}：
 * <pre>k = min(界面尺寸 / 3, 窗口宽 / 1280)</pre>
 * 前一半让 HUD 跟着 Minecraft 的界面尺寸走（热栏也是这么走的，两者的比例因此永远与稿子相同）；
 * 后一半只在窗口比稿子窄时起作用 —— 那时整块 HUD 等比缩到放得下，而不是某一件挪开、某一件缩小。
 * ❗<b>不许给某一件单独加补偿</b>（用户 2026-09-30）：放不下时改的是 k，一处。
 *
 * <h2>锚点</h2>
 * 状态牌 · 金签 · 座位轨锚在左上，舷窗 · 页签 · 展开的抽屉锚在右上。锚在右边的，常量照稿子写它的左边
 * （{@code 1194} 这种），换算时从窗口右沿往回量 {@code (1280 − x) × k}，所以窗口多宽它都贴着右边。
 *
 * <h2>为什么在这里、不在客户端</h2>
 * 与 {@link NotificationSidebarLayout} 同一个理由：不引用任何 Minecraft 客户端类，单测就能拿稿子上量出来的
 * 外框去对它（{@code HudLayoutTest}），并在一串窗口尺寸下核对「谁都不压着谁」—— 2026-09-30 用户指出的
 * 四处重叠（舵轮盖住头像 · 眼睛与 R 压在包角上 · 天候牌与日志压着东西 · Minecraft 自带的心与饥饿同屏）
 * 前三处都是几何，都该在这里红。
 *
 * <p>返回的矩形一律是<b>物理像素</b>（帧缓冲坐标）。客户端把矩阵缩到 1 / 界面尺寸再画，一个像素不差。
 */
public final class HudLayout {

    /** 样张画在多大的画面上、按界面尺寸几画的。 */
    public static final int DESIGN_W = 1280;
    public static final int DESIGN_H = 720;
    public static final int DESIGN_GUI_SCALE = 3;

    // ---------------------------------------------------------------- 状态牌（.plaque，锚左上）
    /** {@code .plaque { left: 22px; top: 20px; padding: 12px 16px 12px 14px }}。宽高是 Chrome 排出来的整数（量过 PNG）。 */
    public static final double PLAQUE_X = 22;
    public static final double PLAQUE_Y = 20;
    public static final double PLAQUE_W = 312;
    public static final double PLAQUE_H = 139;
    public static final double PLAQUE_PAD_T = 12;
    public static final double PLAQUE_PAD_R = 16;
    public static final double PLAQUE_PAD_L = 14;
    /** {@code .paperbit} 的外圈：{@code 0 0 0 2px 深 · 0 0 0 4px 木 · 0 0 0 5px 深}，画在盒子外面。 */
    public static final double RING = 5;

    /** 头像 {@code tok(…, "you", 50)}；它与右边那一栏之间是 {@code .row} 的 10 加 {@code margin-left: 6}。 */
    public static final double TOKEN = 50;
    private static final double TOKEN_TO_COLUMN = 16;
    /** 第一行（体力点 · 水滴 · 眼睛）高 24，第二行（阶段）高 38，中间 {@code gap: 7}。 */
    private static final double ROW_A1_H = 24;
    private static final double ROW_A_GAP = 7;
    private static final double ROW_A_H = 69;
    /** 两大行之间 {@code .plaque { gap: 9px }}。 */
    private static final double ROWS_GAP = 9;
    /** 第三行按罗马数字那一格的行高排：22px × 行高 1.7。 */
    private static final double ROW_B_H = 37.4;

    /** {@code .pip { width: 13px }}，{@code gap: 5px}；最后一点之后一段 {@code width: 8px} 的空，两边各有一个 5 的 gap。 */
    public static final double PIP = 13;
    private static final double PIP_GAP = 5;
    private static final double PIPS_TO_DROP = 5 + 8 + 5;
    /** {@code .ic { width: 24px }}。 */
    public static final double ICON = 24;
    private static final double ICON_GAP = 5;

    /** {@code .wheel .ph { width: 38px }}，{@code .wheel { gap: 6px }}，四格：天候 · 补给 · 行动 · 航海。 */
    public static final double PHASE = 38;
    private static final double PHASE_GAP = 6;
    public static final int PHASES = 4;

    /** 罗马数字那一格 {@code width: 50px}，与头像同宽、居中。 */
    private static final double ROMAN_W = 50;
    private static final double ROW_B_GAP = 14;
    /** {@code .gulls .ic { width: 26px; height: 20px }}，{@code gap: 5px}。 */
    public static final double GULL_W = 26;
    public static final double GULL_H = 20;
    private static final double GULL_GAP = 5;
    /** {@code .key { min-width: 26px; height: 26px; padding: 0 7px }}。 */
    public static final double KEY = 26;
    public static final double KEY_PAD_X = 7;
    private static final double CLUSTER_GAP = 5;

    // ---------------------------------------------------------------- 金签（.dir-b .ribbon，挂在状态牌下面）
    /** {@code left: 30px; bottom: -50px; width: 92px; height: 44px; border-radius: 22px}（相对状态牌的盒子）。 */
    private static final double RIBBON_DX = 30;
    private static final double RIBBON_BELOW = 50;
    public static final double RIBBON_W = 92;
    public static final double RIBBON_H = 44;
    /** 吊绳 {@code ::before { top: -14px; width: 3px; height: 16px }}，在金签横向正中。 */
    private static final double STRING_W = 3;
    private static final double STRING_TOP = -14;
    private static final double STRING_H = 16;
    /** 金签里：铃 24 · {@code gap: 7px} · 键帽 26，整组居中。 */
    private static final double RIBBON_GAP = 7;

    // ---------------------------------------------------------------- 座位轨（.rail，锚左上）
    /** {@code .rail { left: 372px; top: 18px; gap: 14px; padding: 8px 16px 9px }}。 */
    public static final double RAIL_X = 372;
    public static final double RAIL_Y = 18;
    private static final double RAIL_PAD_T = 8;
    private static final double RAIL_PAD_X = 16;
    private static final double RAIL_PAD_B = 9;
    private static final double SEAT_GAP = 14;
    /** {@code .seat { width: 46px }}，里面的头像 {@code .seat .tok { width: 40px }} 横向居中。 */
    public static final double SEAT_W = 46;
    public static final double SEAT_TOKEN = 40;
    /** 舵轮那枚 {@code .seat .mk { top: 25px; right: -13px; padding: 1px 4px 1px 2px }}，图标 18，数字 13px，高按数字的行高 22.1 算。 */
    private static final double BADGE_TOP = 25;
    private static final double BADGE_OUT = 13;
    public static final double BADGE_ICON = 18;
    public static final double BADGE_H = 24;
    private static final double BADGE_PAD_L = 2;
    private static final double BADGE_PAD_R = 4;
    private static final double BADGE_GAP = 2;

    // ---------------------------------------------------------------- 右上：收着（.dock）与展开（.drawer），锚右上
    /** {@code .dock { right: 22px; top: 20px; gap: 12px }}，横向居中；最宽的是舷窗 64。 */
    public static final double DOCK_RIGHT = 22;
    public static final double DOCK_Y = 20;
    private static final double DOCK_GAP = 12;
    /** {@code .medal { width: 64px }}，外圈 {@code 3px 深 · 9px 铜 · 10px 深}。 */
    public static final double MEDAL = 64;
    public static final double MEDAL_RING = 10;
    /** {@code .logtab { width: 50px; height: 50px }}；未读数 {@code .n { right: -7px; top: -7px; min-width: 20px; height: 20px }}。 */
    public static final double LOGTAB = 50;
    public static final double COUNT = 20;
    private static final double COUNT_OUT = 7;

    /** {@code .drawer { right: 22px; top: 20px; width: 348px; gap: 12px }}。 */
    public static final double DRAWER_W = 348;
    public static final double DRAWER_GAP = 12;
    /** 天候卡 {@code .card.wide { height: calc(var(--w) * .7143) }}。 */
    public static final double DRAWER_CARD_H = 348 * 0.7143;
    /** 说明签 {@code margin-top: -2px; padding: 14px 20px 16px}，字 17px × 行高 1.65；尖角 16×16 转 45°、{@code top: -9px}。 */
    public static final double TIP_OVERLAP = 2;
    public static final double TIP_PAD_T = 14;
    public static final double TIP_PAD_X = 20;
    public static final double TIP_PAD_B = 16;
    public static final double TIP_TEXT = 17;
    public static final double TIP_LINE = 17 * 1.65;
    public static final double TIP_POINTER = 16;
    public static final double TIP_POINTER_TOP = -9;
    /** 日志 {@code .log { padding: 14px 18px 16px; font-size: 15.5px; line-height: 1.75 }}。 */
    public static final double LOG_PAD_T = 14;
    public static final double LOG_PAD_X = 18;
    public static final double LOG_PAD_B = 16;
    public static final double LOG_TEXT = 15.5;
    public static final double LOG_LINE = 15.5 * 1.75;
    /** 栏头 {@code .log .h { font-size: 13px; margin-bottom: 4px }}，右头图钉 24 与键帽 26，{@code gap: 5px}。 */
    public static final double LOG_HEAD_TEXT = 13;
    public static final double LOG_HEAD_H = 26;
    public static final double LOG_HEAD_GAP = 4;
    /** 每一条左边那一格 {@code .log .l i { width: 34px; font-size: 13px; padding-top: 2px }}，与正文 {@code gap: 9px}。 */
    public static final double LOG_LABEL_W = 34;
    public static final double LOG_LABEL_GAP = 9;
    /** 自动收回的那道短横 {@code .autoclose { height: 3px; width: 62%; margin-top: 10px }}。 */
    public static final double AUTOCLOSE_H = 3;
    public static final double AUTOCLOSE_GAP = 10;

    private final int width;
    private final int height;
    private final double k;

    private HudLayout(int width, int height, double k) {
        this.width = width;
        this.height = height;
        this.k = k;
    }

    /**
     * @param framebufferWidth  帧缓冲宽（物理像素）
     * @param framebufferHeight 帧缓冲高（物理像素）
     * @param guiScale          Minecraft 此刻的界面尺寸（整数，1 起）
     */
    public static HudLayout of(int framebufferWidth, int framebufferHeight, int guiScale) {
        if (framebufferWidth <= 0 || framebufferHeight <= 0 || guiScale <= 0) {
            throw new IllegalArgumentException("窗口 " + framebufferWidth + "×" + framebufferHeight + " · 界面尺寸 " + guiScale);
        }
        double k = Math.min((double) guiScale / DESIGN_GUI_SCALE, (double) framebufferWidth / DESIGN_W);
        return new HudLayout(framebufferWidth, framebufferHeight, k);
    }

    /** 稿子像素 → 物理像素的系数。 */
    public double k() {
        return k;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** 一段长度（稿子像素）在屏幕上多少物理像素。 */
    public int len(double design) {
        return (int) Math.round(design * k);
    }

    /** 一个物理像素的矩形。 */
    public record Rect(int x, int y, int w, int h) {
        public int right() {
            return x + w;
        }

        public int bottom() {
            return y + h;
        }

        public int centerX() {
            return x + w / 2;
        }

        public int centerY() {
            return y + h / 2;
        }

        public boolean intersects(Rect o) {
            return x < o.right() && o.x < right() && y < o.bottom() && o.y < bottom();
        }

        public boolean contains(Rect o) {
            return o.x >= x && o.y >= y && o.right() <= right() && o.bottom() <= bottom();
        }

        public Rect grow(int d) {
            return new Rect(x - d, y - d, w + 2 * d, h + 2 * d);
        }

        /** 与 {@code o} 重叠的面积（物理像素²）。 */
        public long overlapArea(Rect o) {
            long ow = Math.max(0, Math.min(right(), o.right()) - Math.max(x, o.x));
            long oh = Math.max(0, Math.min(bottom(), o.bottom()) - Math.max(y, o.y));
            return ow * oh;
        }
    }

    /** 锚在左上的一块：四条边各自取整，不是「起点取整 + 宽度取整」—— 那样误差会沿着一排累积。 */
    private Rect left(double x, double y, double w, double h) {
        int x0 = (int) Math.round(x * k);
        int y0 = (int) Math.round(y * k);
        return new Rect(x0, y0, (int) Math.round((x + w) * k) - x0, (int) Math.round((y + h) * k) - y0);
    }

    /** 锚在右上的一块：{@code x} 仍按稿子的左边写，换算时从窗口右沿往回量。 */
    private Rect right(double x, double y, double w, double h) {
        int x0 = width - (int) Math.round((DESIGN_W - x) * k);
        int x1 = width - (int) Math.round((DESIGN_W - x - w) * k);
        int y0 = (int) Math.round(y * k);
        return new Rect(x0, y0, x1 - x0, (int) Math.round((y + h) * k) - y0);
    }

    // ---------------------------------------------------------------- 状态牌

    /** 状态牌的盒子（外圈另算，见 {@link #RING}）。 */
    public Rect plaque() {
        return left(PLAQUE_X, PLAQUE_Y, PLAQUE_W, PLAQUE_H);
    }

    /** 状态牌的内区（盒子减去内边距）：里面的每一件都该在它之内。与各件同一套取整，按边算。 */
    public Rect plaqueInner() {
        return left(PLAQUE_X + PLAQUE_PAD_L, PLAQUE_Y + PLAQUE_PAD_T, PLAQUE_W - PLAQUE_PAD_L - PLAQUE_PAD_R,
                PLAQUE_H - 2 * PLAQUE_PAD_T);
    }

    private double contentX() {
        return PLAQUE_X + PLAQUE_PAD_L;
    }

    private double contentY() {
        return PLAQUE_Y + PLAQUE_PAD_T;
    }

    private double columnX() {
        return contentX() + TOKEN + TOKEN_TO_COLUMN;
    }

    /** 你的头像：在第一大行里竖向居中（{@code .row { align-items: center }}）。 */
    public Rect token() {
        return left(contentX(), contentY() + (ROW_A_H - TOKEN) / 2, TOKEN, TOKEN);
    }

    /** 第 {@code i} 枚体力点（{@code count} 是体型，一枚一点）。 */
    public Rect pip(int i) {
        return left(columnX() + i * (PIP + PIP_GAP), contentY() + (ROW_A1_H - PIP) / 2, PIP, PIP);
    }

    private double pipsEnd(int count) {
        return columnX() + count * PIP + Math.max(0, count - 1) * PIP_GAP;
    }

    /** 口渴那滴水：跟在体力点后面。 */
    public Rect drop(int pips) {
        return left(pipsEnd(pips) + PIPS_TO_DROP, contentY(), ICON, ICON);
    }

    /** 清醒的眼睛：跟在水滴后面。 */
    public Rect eye(int pips) {
        return left(pipsEnd(pips) + PIPS_TO_DROP + ICON + ICON_GAP, contentY(), ICON, ICON);
    }

    /** 阶段那一排的第 {@code i} 格（0 天候 · 1 补给 · 2 行动 · 3 航海）。图标 24 居中在 38 的格里。 */
    public Rect phase(int i) {
        return left(columnX() + i * (PHASE + PHASE_GAP), contentY() + ROW_A1_H + ROW_A_GAP, PHASE, PHASE);
    }

    private double rowBY() {
        return contentY() + ROW_A_H + ROWS_GAP;
    }

    /** 第几天那一格（罗马数字在里面居中）。 */
    public Rect roman() {
        return left(contentX(), rowBY(), ROMAN_W, ROW_B_H);
    }

    /** 第 {@code i} 只海鸥。 */
    public Rect gull(int i) {
        return left(contentX() + ROMAN_W + ROW_B_GAP + i * (GULL_W + GULL_GAP), rowBY() + (ROW_B_H - GULL_H) / 2,
                GULL_W, GULL_H);
    }

    /** 右头那枚键帽（开手牌的键）；{@code keyW} 是键帽宽（稿子像素，至少 {@link #KEY}）。 */
    public Rect handKey(double keyW) {
        double w = Math.max(KEY, keyW);
        return left(PLAQUE_X + PLAQUE_W - PLAQUE_PAD_R - w, rowBY() + (ROW_B_H - KEY) / 2, w, KEY);
    }

    /** 手牌张数那一格：右沿贴着键帽左边 5，宽 {@code numW}（稿子像素）。 */
    public Rect handCount(double keyW, double numW) {
        double keyX = PLAQUE_X + PLAQUE_W - PLAQUE_PAD_R - Math.max(KEY, keyW);
        return left(keyX - CLUSTER_GAP - numW, rowBY(), numW, ROW_B_H);
    }

    /** 手牌那枚图标：在张数左边 5。 */
    public Rect handIcon(double keyW, double numW) {
        double keyX = PLAQUE_X + PLAQUE_W - PLAQUE_PAD_R - Math.max(KEY, keyW);
        return left(keyX - CLUSTER_GAP - numW - CLUSTER_GAP - ICON, rowBY() + (ROW_B_H - ICON) / 2, ICON, ICON);
    }

    // ---------------------------------------------------------------- 金签

    /** 金签的胶囊（圆角半径 = 高的一半）。 */
    public Rect ribbon() {
        return left(PLAQUE_X + RIBBON_DX, PLAQUE_Y + PLAQUE_H + RIBBON_BELOW - RIBBON_H, RIBBON_W, RIBBON_H);
    }

    /** 吊绳：从状态牌底边垂到金签顶上。 */
    public Rect ribbonString() {
        double top = PLAQUE_Y + PLAQUE_H + RIBBON_BELOW - RIBBON_H;
        return left(PLAQUE_X + RIBBON_DX + (RIBBON_W - STRING_W) / 2, top + STRING_TOP, STRING_W, STRING_H);
    }

    private double ribbonGroupX() {
        return PLAQUE_X + RIBBON_DX + (RIBBON_W - (ICON + RIBBON_GAP + KEY)) / 2;
    }

    public Rect ribbonBell() {
        double top = PLAQUE_Y + PLAQUE_H + RIBBON_BELOW - RIBBON_H;
        return left(ribbonGroupX(), top + (RIBBON_H - ICON) / 2, ICON, ICON);
    }

    public Rect ribbonKey() {
        double top = PLAQUE_Y + PLAQUE_H + RIBBON_BELOW - RIBBON_H;
        return left(ribbonGroupX() + ICON + RIBBON_GAP, top + (RIBBON_H - KEY) / 2, KEY, KEY);
    }

    // ---------------------------------------------------------------- 座位轨

    /** 座位轨的盒子：座位数不同，宽度跟着变，左边不动。 */
    public Rect rail(int seats) {
        int n = Math.max(1, seats);
        return left(RAIL_X, RAIL_Y, 2 * RAIL_PAD_X + n * SEAT_W + (n - 1) * SEAT_GAP, RAIL_PAD_T + SEAT_TOKEN + RAIL_PAD_B);
    }

    private double seatX(int i) {
        return RAIL_X + RAIL_PAD_X + i * (SEAT_W + SEAT_GAP);
    }

    /** 第 {@code i} 个座位的头像。 */
    public Rect seatToken(int i) {
        return left(seatX(i) + (SEAT_W - SEAT_TOKEN) / 2, RAIL_Y + RAIL_PAD_T, SEAT_TOKEN, SEAT_TOKEN);
    }

    /** 舵手那枚舵轮：右沿伸出座位 13，顶在头像的 25 处；{@code numW} 是划船堆张数那几个字宽（稿子像素）。 */
    public Rect helmBadge(int seat, double numW) {
        double w = BADGE_PAD_L + BADGE_ICON + BADGE_GAP + numW + BADGE_PAD_R;
        double right = seatX(seat) + SEAT_W + BADGE_OUT;
        return left(right - w, RAIL_Y + RAIL_PAD_T + BADGE_TOP, w, BADGE_H);
    }

    public Rect helmBadgeIcon(int seat, double numW) {
        double w = BADGE_PAD_L + BADGE_ICON + BADGE_GAP + numW + BADGE_PAD_R;
        double right = seatX(seat) + SEAT_W + BADGE_OUT;
        return left(right - w + BADGE_PAD_L, RAIL_Y + RAIL_PAD_T + BADGE_TOP + (BADGE_H - BADGE_ICON) / 2,
                BADGE_ICON, BADGE_ICON);
    }

    public Rect helmBadgeText(int seat, double numW) {
        double right = seatX(seat) + SEAT_W + BADGE_OUT;
        return left(right - BADGE_PAD_R - numW, RAIL_Y + RAIL_PAD_T + BADGE_TOP, numW, BADGE_H);
    }

    // ---------------------------------------------------------------- 右上：收着

    private double dockX() {
        return DESIGN_W - DOCK_RIGHT - MEDAL;
    }

    /** 天候舷窗（圆玻璃那一块；外圈 {@link #MEDAL_RING} 另算）。 */
    public Rect medal() {
        return right(dockX(), DOCK_Y, MEDAL, MEDAL);
    }

    /** 日志页签（外圈 {@link #RING} 另算）。 */
    public Rect logTab() {
        return right(dockX() + (MEDAL - LOGTAB) / 2, DOCK_Y + MEDAL + DOCK_GAP, LOGTAB, LOGTAB);
    }

    /** 页签右上角的未读数（圆，{@code min-width} 20）。 */
    public Rect logCount(double w) {
        double cw = Math.max(COUNT, w);
        double tabX = dockX() + (MEDAL - LOGTAB) / 2;
        return right(tabX + LOGTAB + COUNT_OUT - cw, DOCK_Y + MEDAL + DOCK_GAP - COUNT_OUT, cw, COUNT);
    }

    /** 页签下面那枚日志键。 */
    public Rect logKey(double keyW) {
        double w = Math.max(KEY, keyW);
        return right(dockX() + (MEDAL - w) / 2, DOCK_Y + MEDAL + DOCK_GAP + LOGTAB + DOCK_GAP, w, KEY);
    }

    // ---------------------------------------------------------------- 右上：展开

    private double drawerX() {
        return DESIGN_W - DOCK_RIGHT - DRAWER_W;
    }

    /** 展开时那张天候卡。 */
    public Rect drawerCard() {
        return right(drawerX(), DOCK_Y, DRAWER_W, DRAWER_CARD_H);
    }

    /**
     * 抽屉里第二、第三块（说明签 · 日志）从哪一行起、多宽：它们的高由字排出来，版面只给横向与起点。
     *
     * @param aboveBottomDesign 上一块的底（稿子像素）
     * @param overlap           这一块往上压多少（说明签 {@link #TIP_OVERLAP}，日志 0）
     */
    public Rect drawerBlock(double aboveBottomDesign, double overlap, double heightDesign) {
        return right(drawerX(), aboveBottomDesign + DRAWER_GAP - overlap, DRAWER_W, heightDesign);
    }

    /** 抽屉的左沿与横向中线（稿子像素）：说明签的尖角指着天候卡的横向正中。 */
    public static double drawerCenterDesign() {
        return DESIGN_W - DOCK_RIGHT - DRAWER_W / 2;
    }

    /** 稿子像素里的一个 y（右上那一列的竖向排版在客户端累加，最后才换成物理像素）。 */
    public int y(double design) {
        return (int) Math.round(design * k);
    }

    /** 锚在右边的一个 x。 */
    public int xRight(double design) {
        return width - (int) Math.round((DESIGN_W - design) * k);
    }

    /** 锚在左边的一个 x。 */
    public int xLeft(double design) {
        return (int) Math.round(design * k);
    }
}
