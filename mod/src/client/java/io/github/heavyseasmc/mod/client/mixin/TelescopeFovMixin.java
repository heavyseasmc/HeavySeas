package io.github.heavyseasmc.mod.client.mixin;

import io.github.heavyseasmc.mod.client.TelescopeEyepiece;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 望远镜柜的望远镜视角（ADR-0109 ③）：拉近视野、不画手。
 *
 * <ul>
 *   <li>{@code getFov} 的 RETURN：画世界那一次（{@code changingFov == true}）把算好的视野再乘 {@link TelescopeEyepiece#fovFactor} ——
 *       跟在游戏自带的那些修正（疾跑、水下、视野效果比例）之后，所以拉近的倍数不受它们影响。画手那一次（{@code false}）是固定 70°，不动。</li>
 *   <li>{@code renderHand} 的 HEAD：看着时不画手（同游戏自带的望远镜）。</li>
 * </ul>
 * 与版本绑死：两个方法都是 1.21.1 的（{@code javap} 读过 Loom 的映射 jar：{@code getFov(Camera, float, boolean)} 返回 double ·
 * {@code renderHand(Camera, float, Matrix4f)}）。{@code defaultRequire: 1}，对不上时启动即崩。
 */
@Mixin(GameRenderer.class)
public abstract class TelescopeFovMixin {

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void heavyseas$telescopeZoom(Camera camera, float tickDelta, boolean changingFov, CallbackInfoReturnable<Double> cir) {
        if (!changingFov) {
            return;
        }
        double k = TelescopeEyepiece.fovFactor(tickDelta);
        if (k != 1) {
            cir.setReturnValue(cir.getReturnValueD() * k);
        }
    }

    @Inject(method = "renderHand", at = @At("HEAD"), cancellable = true)
    private void heavyseas$telescopeHand(Camera camera, float tickDelta, Matrix4f matrix, CallbackInfo ci) {
        if (TelescopeEyepiece.active()) {
            ci.cancel();
        }
    }
}
