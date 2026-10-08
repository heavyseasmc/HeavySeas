package io.github.heavyseasmc.mod.client;

import java.util.List;
import java.util.function.Predicate;

/**
 * 世界视图里一次按键交给谁 —— {@link KeyPriority} 的判据本体，不碰 Minecraft，好写单测（审查 2026-10-07 U4）。
 *
 * <p>两条：
 * <ol>
 *   <li><b>让</b>：本模组里「撞键就让」的那几个（日志翻页 ↑ ↓ End，{@code yielding}）与别的绑定撞在同一个键上时，
 *       交给别的那一个 —— 对局里外都一样。默认键保持 ↑ ↓（用户认过），可把移动绑在方向键上的玩家（左手玩家常见）
 *       按 ↑ 不能被截走：1.21.1 一个键只存一个绑定，原先对局里一律归本模组，人走不动、日志反被钉住往回翻。
 *       对局外也要主动让：不让的话轮到谁全看 HashMap 的遍历顺序。</li>
 *   <li><b>先接</b>：别的本模组键对局里照旧先于 Minecraft 自带的与别的模组（ADR-0042：L 与「进度」撞，原先永远是进度赢）。</li>
 * </ol>
 */
final class KeyRoute {

    private KeyRoute() {
    }

    /**
     * @param onKey    绑在这个键上的全部绑定（按 Minecraft 列出的次序）
     * @param ours     是不是本模组的
     * @param yielding 本模组里「撞键就让」的那几个
     * @param live     这个世界有对局在走
     * @return 交给谁；{@code null} = 照 Minecraft 自己的办法（{@code KEY_TO_BINDINGS} 里存的那一个）
     */
    static <B> B route(List<B> onKey, Predicate<B> ours, Predicate<B> yielding, boolean live) {
        boolean soft = false;
        B foreign = null;
        B own = null;
        for (B b : onKey) {
            if (ours.test(b)) {
                soft |= yielding.test(b);
                if (own == null && !yielding.test(b)) {
                    own = b;
                }
            } else if (foreign == null) {
                foreign = b;
            }
        }
        if (soft && foreign != null) {
            return foreign;
        }
        if (!live) {
            return null;
        }
        if (own != null) {
            return own;
        }
        for (B b : onKey) {
            if (ours.test(b)) {
                return b;                     // 只有「会让」的那几个、又没人跟它撞：照常归本模组
            }
        }
        return null;
    }
}
