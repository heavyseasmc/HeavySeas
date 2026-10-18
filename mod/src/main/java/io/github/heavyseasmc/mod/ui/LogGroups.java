package io.github.heavyseasmc.mod.ui;

/**
 * 航海日志按「第几天 · 阶段」分组；终局那几条另成一组「第 N 天 · 终局」。哪几条算终局，按播报的翻译键认。
 *
 * <p>❗按<b>键的段</b>认，不按字符串前缀：2026-10-13 第二轮验收 52，第 2 天航海中途冒出一节「第 2 天 · 终局」，
 * 里面只有「落海：水手」—— 原先的判据是 {@code key.startsWith("game.over")}，而落海那一条的键是
 * {@code heavyseas.game.overboard}，前缀恰好也是 {@code game.over}。
 */
public final class LogGroups {

    private static final String NAMESPACE = "heavyseas.";

    private LogGroups() {
    }

    /** 这条播报（翻译键）是不是终局那一组的。 */
    public static boolean endgameKey(String key) {
        if (key == null || !key.startsWith(NAMESPACE)) {
            return false;
        }
        String k = key.substring(NAMESPACE.length());
        return k.startsWith("endgame.") || under(k, "game.over") || k.startsWith("game.final_line");
    }

    /** {@code k} 就是 {@code segment}，或在它下面一层（{@code segment.xxx}）。 */
    private static boolean under(String k, String segment) {
        return k.equals(segment) || k.startsWith(segment + ".");
    }
}
