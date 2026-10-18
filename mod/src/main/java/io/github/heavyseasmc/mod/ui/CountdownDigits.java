package io.github.heavyseasmc.mod.ui;

/**
 * 倒计时那个数怎么写、什么时候变朱砂（用户 2026-10-13 定：「倒计时整数秒」· 最后 5 秒变朱砂）。
 *
 * <p>原先写一位小数（{@code %.1f}）：一秒跳十次，是静止画面里唯一一直在动的东西（2026-09-30 复盘「界面」第 7 条）。
 * 改成整数秒、<b>往上取整</b>：剩 0.3 秒时还写 1，到点那一刻才是 0 —— 往下取整的话「0」要挂将近一秒，
 * 读起来像已经超时了却还没结算。
 *
 * <p>❗朱砂按<b>写出来的那个数</b>判，不按毫秒：数字从 6 跳到 5 的那一帧同时变色，不会出现「6」是朱砂、
 * 或者「5」还是墨色的那一帧。倒计时条（液位管）的朱砂与数字用同一个判据。
 *
 * <p>纯函数，不碰 Minecraft，单测见 {@code CountdownDigitsTest}。
 */
public final class CountdownDigits {

    /** 剩几秒（写出来的数）起变朱砂。 */
    public static final int URGENT_SECONDS = 5;

    private CountdownDigits() {
    }

    /** 写出来的那个数：整秒，往上取整，不小于 0。 */
    public static long seconds(long leftMs) {
        if (leftMs <= 0L) {
            return 0L;
        }
        return (leftMs + 999L) / 1000L;
    }

    /** 这一帧的数要不要画成朱砂。 */
    public static boolean urgent(long leftMs) {
        return seconds(leftMs) <= URGENT_SECONDS;
    }

    /** 这一局最长的那种写法有几位（定宽用：数位变了，管子不横跳）。 */
    public static String widest(long totalMs) {
        return Long.toString(seconds(totalMs));
    }
}
