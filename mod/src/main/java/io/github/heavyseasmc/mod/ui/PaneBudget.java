package io.github.heavyseasmc.mod.ui;

import io.github.heavyseasmc.mod.ui.HudLayout.Rect;

/**
 * 对局各面排在多宽里（物理像素）：航海日志钉住时，界面给它让出右边一栏，纸板往左收（用户 2026-10-13）。
 *
 * <h2>让出来的那一栏只放日志</h2>
 * 用户 2026-10-13 第二次定：界面里钉住时，右栏<b>只画航海日志</b>、从上到下占满，并且<b>收窄</b> ——
 * 天候卡与「只有你看得到」各面里本来就有，照搬世界视图那一整列（天候卡 + 私密 + 日志）时日志只露三五条，
 * 而 348 稿子像素宽的一列让 1280×720 的界面只剩约 900 宽，低于 ADR-0088 的 1067，日志一钉就收起（ux-v4 实拍 round4 02 · 04）。
 * <ul>
 *   <li>栏宽按<b>日志正文的字号</b>算（{@link #logWidth}）：正文一行放得下 {@link #LOG_LINE_CHARS} 个汉字再加半个字的余量，
 *       加上左右内边距与未读点那一格。字号随窗口与阅读字号（常规 / 大字）变，栏宽跟着变 ——
 *       1280×720 常规字号时约 245 物理像素，大字时约 295。</li>
 *   <li>栏贴着窗口右沿、留 {@link HudLayout#DOCK_RIGHT} 的边（与世界视图右上那一列同一条右沿）。</li>
 * </ul>
 * 它左边剩下的那一块当成一扇<b>虚拟窗口</b>交给骨架：{@link #sheet()} = {@code SheetLayout.of(paneWidth, height)}。
 * 各面本来就只从骨架取版面（ADR-0037 §7.10 的带位），所以座位轨、牌排、说明签、底栏一起按这扇窗口的系数等比收，
 * 不在任何一面里各自减。
 *
 * <h2>钉住时的下限：{@link #MIN_PINNED_PANE_W} × {@link HudLayout#MIN_WINDOW_H}</h2>
 * 整窗照旧先过 ADR-0088 的 1067×600；界面那一块另有一条更宽松的宽度下限（用户 2026-10-13：「钉住时面板下限放宽到约 1000 宽，
 * 让 1280×720 也放得下」）。取值来由见常量注释。界面那一块低于它时日志<b>收起</b>（{@link #logShown} 为假，整窗照旧给界面），
 * 导航那一排挂一枚小签，绝不盖在界面上。没钉住时不让，界面就是整窗，下限仍是 1067×600。
 *
 * <p>纯几何，不引用客户端类：单测拿它在一串窗口下核对「板不碰日志栏」「收不下时收起」。
 * 字号由调用方给（客户端按阅读字号梯子挑，{@code PaneWindow#logTextPx}），这里不碰字体。
 *
 * @param paneWidth 界面那一块的宽（物理像素，从窗口左沿起）；日志收起时就是整窗宽
 * @param height    窗口高（物理像素）
 * @param logShown  日志那一栏这一帧画不画
 * @param window    整窗宽（物理像素）
 * @param logWidth  日志那一栏的宽（物理像素）；收起时为 0
 */
public record PaneBudget(int paneWidth, int height, boolean logShown, int window, int logWidth) {

    /** 日志正文一行至少放得下几个汉字（用户 2026-10-13：「日志一行至少放得下约 12 个汉字」）。 */
    public static final int LOG_LINE_CHARS = 12;
    /** 再加多少个字宽的余量：字形的步进与取整差一两个像素时，第 12 个字不被挤到下一行。 */
    private static final double LOG_LINE_SLACK = 0.5;
    /** 正文左边未读点那一格（稿子像素，与 {@code GameHud#drawLog} 里正文往右挪的那一截同一个数）。 */
    public static final double LOG_TEXT_INDENT = 9;

    /**
     * 钉住时界面那一块最窄多宽（物理像素）。
     *
     * <p>来由（2026-10-13 扫过）：把各面已有的版面判据（骨架每一件在板里 · 计分两档字号 4–8 人 · 设置 · 补给箱 1–8 张 ×
     * 签 1–3 行 · 手牌两排 × 界面尺寸 2–4）在高 600–1440 之间、宽 600–1600 逐个像素跑一遍：九档高度下最后一个红的宽度
     * 都是 668（大字计分的左下小字压到提示行），669 起全过。骨架等比收，几何判据卡不住 1000 附近 ——
     * 真正往下掉的是可读性：手牌缩略牌是 104 × k，ADR-0088 的 86 物理像素要 k ≥ 0.83（界面那一块约 1058 宽），
     * 那样 1280×720 一钉就收起，与用户要的相反。所以取用户给的 1000：缩略牌 81 物理像素（比 86 小 5 个像素，是这一条明知的代价），
     * 1280×720 常规字号（界面那一块约 1013）放得下；大字（约 963）与更小的 16:9 窗口收起、挂小签。
     * 改它要连 {@code PaneBudgetTest} 一起跑：它在恰好 1000 宽处逐个面核对，并核对 999 收起。
     */
    public static final int MIN_PINNED_PANE_W = 1000;

    /**
     * @param framebufferWidth  整窗宽（物理像素）
     * @param framebufferHeight 整窗高（物理像素）
     * @param logWanted         这一帧要不要日志那一栏（钉住 · 对局进行中 · 没按 F1）
     * @param logTextPx         日志正文的字号（物理像素，已按阅读字号梯子挑过）
     */
    public static PaneBudget of(int framebufferWidth, int framebufferHeight, boolean logWanted, int logTextPx) {
        if (logWanted && HudLayout.supportsWindow(framebufferWidth, framebufferHeight)) {
            int pane = logLeft(framebufferWidth, framebufferHeight, logTextPx);
            if (supportsPane(pane, framebufferHeight)) {
                return new PaneBudget(pane, framebufferHeight, true, framebufferWidth,
                        logWidth(framebufferWidth, framebufferHeight, logTextPx));
            }
        }
        return new PaneBudget(framebufferWidth, framebufferHeight, false, framebufferWidth, 0);
    }

    /** 钉住时界面那一块够不够排（宽按 {@link #MIN_PINNED_PANE_W}，高照 ADR-0088）。 */
    public static boolean supportsPane(int paneWidth, int height) {
        return paneWidth >= MIN_PINNED_PANE_W && height >= HudLayout.MIN_WINDOW_H;
    }

    /** 钉住了却收起：窗口太窄（导航那一排给一枚小签，告诉人日志去哪了）。 */
    public static boolean folded(int framebufferWidth, int framebufferHeight, boolean logWanted, int logTextPx) {
        return logWanted && HudLayout.supportsWindow(framebufferWidth, framebufferHeight)
                && !of(framebufferWidth, framebufferHeight, true, logTextPx).logShown();
    }

    /**
     * 日志那一栏多宽（物理像素）：正文 {@link #LOG_LINE_CHARS} 个字（加半个字余量）+ 左右内边距 + 未读点那一格。
     * 内边距与那一格按整窗的 {@link HudLayout} 换算，与 {@code GameHud#drawLog} 同一套数。
     */
    public static int logWidth(int framebufferWidth, int framebufferHeight, int logTextPx) {
        HudLayout l = HudLayout.of(framebufferWidth, framebufferHeight);
        return (int) Math.ceil((LOG_LINE_CHARS + LOG_LINE_SLACK) * logTextPx)
                + 2 * l.len(HudLayout.LOG_PAD_X) + l.len(LOG_TEXT_INDENT);
    }

    /** 日志那一栏的左沿（物理像素）：从窗口右沿往回量右边距与栏宽。界面那一块到这里为止。 */
    public static int logLeft(int framebufferWidth, int framebufferHeight, int logTextPx) {
        HudLayout l = HudLayout.of(framebufferWidth, framebufferHeight);
        return framebufferWidth - l.len(HudLayout.DOCK_RIGHT) - logWidth(framebufferWidth, framebufferHeight, logTextPx);
    }

    /** 这一块的骨架：各面一律从这里取（{@code GameScreen#sheet}）。 */
    public SheetLayout sheet() {
        return SheetLayout.of(paneWidth, height);
    }

    /** 这一块按 {@link HudLayout} 的口径（旧稿子 GUI 长度的换算 {@code GuiMetrics} 用它）。 */
    public HudLayout hud() {
        return HudLayout.of(paneWidth, height);
    }

    /** 日志那一栏占的竖条（物理像素，整窗高）；收起时宽为 0。 */
    public Rect logColumn() {
        if (!logShown) {
            return new Rect(window, 0, 0, height);
        }
        return new Rect(paneWidth, 0, logWidth, height);
    }
}
