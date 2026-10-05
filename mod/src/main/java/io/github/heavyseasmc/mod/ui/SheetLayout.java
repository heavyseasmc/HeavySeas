package io.github.heavyseasmc.mod.ui;

import io.github.heavyseasmc.mod.ui.HudLayout.Rect;

/**
 * 对局各面共用的那一副骨架：一块板 · 上带（四格阶段 + 海鸥）· 座位轨 · 舞台 · 倒计时 · 按键提示那一行。
 * 照样张 b-3 / b-4 / b-5 取值（{@code index.html} 的「共用骨架的几何（三个方向相同，只换材质）」那一段）。
 *
 * <h2>与 {@link HudLayout} 同一个坐标系</h2>
 * 常量是<b>稿子像素</b>（1280×720、界面尺寸 3 下的物理像素），乘一个系数 k 就是屏幕上的物理像素：
 * <pre>k = min(窗口宽 / 1280, 窗口高 / 720)</pre>
 * 与主画面共用窗口缩放；Minecraft 的界面尺寸只用于客户端坐标换算，不改变内容的物理尺寸。
 *
 * <h2>锚点</h2>
 * 板四边各离窗口 50 · 34（样张 {@code .sheet { left: 50px; top: 34px }}，1280×720 下正好 1180×652）。
 * 上带、座位轨、牌那一排<b>锚在板顶</b>；倒计时与提示那一行<b>锚在板底</b>（样张 1280×720 下倒计时顶在 574 =
 * 板底 686 − 112，提示那一行底在 666 = 686 − 20）；横向一律按板的中线居中。
 * 这样窗口比 16:9 高或宽时，多出来的那一截都落在舞台里，而不是把骨架拉开。
 *
 * <p>返回的都是<b>物理像素</b>。对局各面的内容仍按 GUI 单位排（{@code GameScreen} 的 {@code Bands}），
 * 由客户端除以界面尺寸换过去；骨架本身在物理像素里画。
 */
public final class SheetLayout {

    public static final int DESIGN_W = HudLayout.DESIGN_W;
    public static final int DESIGN_H = HudLayout.DESIGN_H;

    /** {@code .sheet { left: 50px; top: 34px }}：板离窗口四边。 */
    public static final double SHEET_X = 50;
    public static final double SHEET_Y = 34;
    /** {@code .dir-b .sheet} 的外圈：3 px 深 · 2 px 木（盒子外面）。 */
    public static final double SHEET_RING = 5;

    /** 上带 {@code .hd { top: 22px; gap: 26px }}：四格阶段（{@code .wheel .ph} 38，间距 6）+ 四只海鸥（26×20，间距 5）。 */
    public static final double HD_TOP = 22;
    private static final double HD_GAP = 26;
    public static final double PHASE = 38;
    private static final double PHASE_GAP = 6;
    public static final double GULL_W = 26;
    public static final double GULL_H = 20;
    private static final double GULL_GAP = 5;
    public static final int PHASES = 4;
    public static final int GULLS = 4;

    /** 座位轨 {@code .srail { left: 110px; right: 110px; top: 84px; justify-content: space-between }}。 */
    private static final double RAIL_INSET = 110;
    public static final double RAIL_TOP = 84;
    /** {@code .srail .seat { width: 96px }} · {@code .srail .seat .tok { width: 46px }} · 名字 15px × 1.2，与头像隔 3。 */
    public static final double SEAT_W = 96;
    public static final double TOKEN = 46;
    private static final double NAME_GAP = 3;
    public static final double NAME_PX = 15;
    public static final double NAME_LINE = 15 * 1.2;
    /** {@code .srail .crate { top: -20px; margin-left: -13px }}：补给箱在谁手里，那一格头上一只箱。 */
    private static final double CRATE_TOP = -20;
    private static final double CRATE_DX = -13;
    public static final double ICON = 24;

    /** 牌那一排 {@code .cards { top: 196px; gap: 13px }}（样张 b-3 是补给箱，一张 124 宽）。 */
    public static final double CARDS_TOP = 196;
    public static final double CARD_GAP = 13;
    public static final double PROVISION_CARD_W = 124;
    /** 选中的那张抬起 16（{@code .lifted { transform: translateY(-16px) }}）。 */
    public static final double LIFT = 16;

    /** 倒计时 {@code .count { left: 320px; right: 320px; top: 540px; gap: 14px }}：横杠 18 高，秒数 22px 一栏 64 宽。 */
    private static final double COUNT_FROM_BOTTOM = 652 - 540;
    public static final double COUNT_W = 540;
    public static final double BAR_H = 18;
    private static final double SEC_W = 64;
    private static final double SEC_GAP = 14;
    public static final double SEC_PX = 22;

    /** 提示那一行 {@code .hints { bottom: 20px; gap: 28px }}：按钮 {@code .btn { padding: 9px 20px }} 高 48.6。 */
    private static final double HINTS_BOTTOM = 20;
    public static final double HINTS_H = 48.6;
    public static final double HINTS_GAP = 28;
    public static final double HINTS_PX = 15;
    public static final double BTN_PAD_X = 20;
    public static final double BTN_GAP = 10;
    public static final double BTN_PX = 18;
    public static final double BTN_SPACING = 2;
    /** {@code .hints .row { gap: 7px }}：一组键帽与它那句话之间。 */
    public static final double ROW_GAP = 7;

    private final int width;
    private final int height;
    private final double k;

    private SheetLayout(int width, int height, double k) {
        this.width = width;
        this.height = height;
        this.k = k;
    }

    public static SheetLayout of(int framebufferWidth, int framebufferHeight) {
        return new SheetLayout(framebufferWidth, framebufferHeight, HudLayout.windowScale(framebufferWidth, framebufferHeight));
    }

    public double k() {
        return k;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int len(double design) {
        return (int) Math.round(design * k);
    }

    // ---------------------------------------------------------------- 板

    /** 板的盒子（外圈另算，{@link #SHEET_RING}）：四边各离窗口 50 · 34（稿子像素）。 */
    public Rect sheet() {
        int x0 = len(SHEET_X);
        int y0 = len(SHEET_Y);
        return new Rect(x0, y0, width - 2 * x0, height - 2 * y0);
    }

    private double top() {
        return sheet().y() / k;
    }

    private double bottom() {
        return sheet().bottom() / k;
    }

    private double centerX() {
        return width / 2.0 / k;
    }

    private Rect at(double x, double y, double w, double h) {
        int x0 = (int) Math.round(x * k);
        int y0 = (int) Math.round(y * k);
        return new Rect(x0, y0, (int) Math.round((x + w) * k) - x0, (int) Math.round((y + h) * k) - y0);
    }

    // ---------------------------------------------------------------- 上带

    private double hdLeft() {
        double wheel = PHASES * PHASE + (PHASES - 1) * PHASE_GAP;
        double gulls = GULLS * GULL_W + (GULLS - 1) * GULL_GAP;
        return centerX() - (wheel + HD_GAP + gulls) / 2;
    }

    /** 上带第 {@code i} 格阶段（0 天候 · 1 补给 · 2 行动 · 3 航海）。 */
    public Rect phase(int i) {
        return at(hdLeft() + i * (PHASE + PHASE_GAP), top() + HD_TOP, PHASE, PHASE);
    }

    /** 上带第 {@code i} 只海鸥（与阶段那一排竖向居中）。 */
    public Rect gull(int i) {
        double x = hdLeft() + PHASES * PHASE + (PHASES - 1) * PHASE_GAP + HD_GAP + i * (GULL_W + GULL_GAP);
        return at(x, top() + HD_TOP + (PHASE - GULL_H) / 2, GULL_W, GULL_H);
    }

    // ---------------------------------------------------------------- 座位轨

    private double seatX(int i, int seats) {
        double from = sheet().x() / k + RAIL_INSET;
        double span = sheet().right() / k - RAIL_INSET - from;
        if (seats <= 1) {
            return from + (span - SEAT_W) / 2;
        }
        return from + i * (span - SEAT_W) / (seats - 1);
    }

    /** 第 {@code i} 个座位（共 {@code seats} 个）的头像。 */
    public Rect seatToken(int i, int seats) {
        return at(seatX(i, seats) + (SEAT_W - TOKEN) / 2, top() + RAIL_TOP, TOKEN, TOKEN);
    }

    /** 那一格的名字（一行，座位宽）。 */
    public Rect seatName(int i, int seats) {
        return at(seatX(i, seats), top() + RAIL_TOP + TOKEN + NAME_GAP, SEAT_W, NAME_LINE);
    }

    /** 整一格（头像 + 名字）：悬停与右键的命中。 */
    public Rect seatCell(int i, int seats) {
        return at(seatX(i, seats), top() + RAIL_TOP, SEAT_W, TOKEN + NAME_GAP + NAME_LINE);
    }

    /** 补给箱在谁手里：那一格头上的箱。 */
    public Rect crate(int i, int seats) {
        return at(seatX(i, seats) + SEAT_W / 2 + CRATE_DX, top() + RAIL_TOP + CRATE_TOP, ICON, ICON);
    }

    /** 座位轨的底（物理像素）：名字那一行的下沿。 */
    public int railBottom() {
        return (int) Math.round((top() + RAIL_TOP + TOKEN + NAME_GAP + NAME_LINE) * k);
    }

    /** 牌那一排的顶（物理像素）。 */
    public int cardsTop() {
        return (int) Math.round((top() + CARDS_TOP) * k);
    }

    // ---------------------------------------------------------------- 倒计时与提示（锚在板底）

    /** 倒计时的横杠。 */
    public Rect countBar() {
        return at(centerX() - COUNT_W / 2, bottom() - COUNT_FROM_BOTTOM, COUNT_W - SEC_W - SEC_GAP, BAR_H);
    }

    /** 倒计时的秒数那一栏（右对齐，与横杠竖向居中）。 */
    public Rect countSeconds() {
        return at(centerX() + COUNT_W / 2 - SEC_W, bottom() - COUNT_FROM_BOTTOM - (SEC_PX * 1.7 - BAR_H) / 2,
                SEC_W, SEC_PX * 1.7);
    }

    /** 提示那一行的竖向中线（物理像素）：按钮、键帽、那句话都按它居中。 */
    public int hintsCenterY() {
        return (int) Math.round((bottom() - HINTS_BOTTOM - HINTS_H / 2) * k);
    }

    /** 提示那一行的顶（物理像素）。 */
    public int hintsTop() {
        return (int) Math.round((bottom() - HINTS_BOTTOM - HINTS_H) * k);
    }
}
