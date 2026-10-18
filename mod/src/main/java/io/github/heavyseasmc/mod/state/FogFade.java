package io.github.heavyseasmc.mod.state;

import net.minecraft.util.math.MathHelper;

/**
 * 雾的渐变数学（ADR-0034 §5.1.3）。放在 main 源码集里，是为了让单测不必起客户端就能核它。
 *
 * <p>只有三条纯函数：smoothstep 进度、线性插值、游戏默认的地形雾。客户端的 {@code MistFog} 只负责记时刻与状态。
 */
public final class FogFade {

    private FogFade() {
    }

    /** smoothstep：0 → 1，两端平、中间快，没有过冲（雾不该弹一下）。 */
    public static float progress(long elapsedMs, long durationMs) {
        if (durationMs <= 0) {
            return 1f;
        }
        float x = MathHelper.clamp(elapsedMs / (float) durationMs, 0f, 1f);
        return x * x * (3f - 2f * x);
    }

    public static float lerp(float from, float to, float t) {
        return from + (to - from) * t;
    }

    /**
     * 游戏默认的地形雾：起点 = 渲染距离 − clamp(距离 / 10, 4, 64)，终点 = 渲染距离。
     * 与 1.21.1 {@code BackgroundRenderer.applyFog} 无水无状态效果那一支同一条公式（反编译源码里读的）。
     */
    public static float[] vanilla(float viewDistance) {
        return new float[] {viewDistance - MathHelper.clamp(viewDistance / 10f, 4f, 64f), viewDistance};
    }
}
