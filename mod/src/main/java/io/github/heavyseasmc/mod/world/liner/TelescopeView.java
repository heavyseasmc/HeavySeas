package io.github.heavyseasmc.mod.world.liner;

import net.minecraft.util.math.BlockPos;

/**
 * 望远镜柜右键进入望远镜视角（ADR-0109 §2 ③）的钩子：柜子在 {@code main} 里，拉近与暗角在 {@code client} 里。
 *
 * <p>同 {@link PortraitView} 那一个形状：{@code main} 里的类绝不引用客户端类（专用服务端一加载就崩，ADR-0013），
 * 客户端初始化时把「进视角」塞进来，柜子在客户端那一侧的 {@code onUse} 里调 {@link #look}。专用服务端上从来没人登记 ——
 * {@link #look} 什么都不做、不抛，返回 {@code false}。视角只是客户端自己的画面，服务端不知道、也不需要知道。
 */
public final class TelescopeView {

    /** 客户端那一半：从这一格的望远镜柜看出去。 */
    @FunctionalInterface
    public interface Viewer {
        void look(BlockPos cabinet);
    }

    private static volatile Viewer viewer;

    private TelescopeView() {
    }

    /** 客户端初始化时调一次。传 {@code null} = 撤掉（单测收尾用）。 */
    public static void install(Viewer v) {
        viewer = v;
    }

    /**
     * 进望远镜视角。
     *
     * @param cabinet 望远镜柜那一格
     * @return 交出去了没有：没登记（服务端、单测）时是 {@code false}
     */
    public static boolean look(BlockPos cabinet) {
        Viewer v = viewer;
        if (v == null) {
            return false;
        }
        v.look(cabinet.toImmutable());
        return true;
    }
}
