package io.github.heavyseasmc.mod.world.liner;

/**
 * 肖像画框右键看牌（C3 第二轮 · ADR-0086 §3）的钩子：方块在 {@code main} 里，看牌的界面在 {@code client} 里。
 *
 * <p>❗{@code main} 里的类绝不能引用客户端类（专用服务端一加载就崩，ADR-0013）。所以方块只认这一个接口：
 * 客户端初始化时把「开界面」的实现塞进来（{@code HeavySeasClient}），方块在客户端那一侧的 {@code onUse} 里调 {@link #open}。
 * 专用服务端上从来没人登记 —— {@link #open} 什么都不做、不抛，返回 {@code false}。
 */
public final class PortraitView {

    /** 客户端那一半：开一面看这一位人物牌的界面。 */
    @FunctionalInterface
    public interface Opener {
        void open(String sitter);
    }

    private static volatile Opener opener;

    private PortraitView() {
    }

    /** 客户端初始化时调一次。传 {@code null} = 撤掉（单测收尾用）。 */
    public static void install(Opener o) {
        opener = o;
    }

    /**
     * 开看牌界面。
     *
     * @param sitter 角色 id（{@link LinerProp.Sitter} 的值名，同 {@code data/roster}）
     * @return 开了没有：没登记（服务端、单测）时是 {@code false}
     */
    public static boolean open(String sitter) {
        Opener o = opener;
        if (o == null) {
            return false;
        }
        o.open(sitter);
        return true;
    }
}
