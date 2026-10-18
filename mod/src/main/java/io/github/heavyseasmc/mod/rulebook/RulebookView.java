package io.github.heavyseasmc.mod.rulebook;

/**
 * 讲台右键开规则书（ADR-0087 §2）的钩子：讲台在 {@code main} 里，书页（{@code RulebookBook} → {@code BookScreen}）在 {@code client} 里。
 *
 * <p>同 {@code PortraitView} 那一个形状：{@code main} 里的类绝不引用客户端类（专用服务端一加载就崩，ADR-0013），
 * 客户端初始化时把「开书」塞进来，讲台在客户端那一侧的 {@code onUse} 里调 {@link #open}。专用服务端上从来没人登记 ——
 * {@link #open} 什么都不做、不抛，返回 {@code false}。
 */
public final class RulebookView {

    private static volatile Runnable opener;

    private RulebookView() {
    }

    /** 客户端初始化时调一次。传 {@code null} = 撤掉（单测收尾用）。 */
    public static void install(Runnable o) {
        opener = o;
    }

    /** 开书。@return 开了没有：没登记（服务端、单测）时是 {@code false} */
    public static boolean open() {
        Runnable o = opener;
        if (o == null) {
            return false;
        }
        o.run();
        return true;
    }
}
