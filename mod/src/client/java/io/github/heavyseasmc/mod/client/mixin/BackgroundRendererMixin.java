package io.github.heavyseasmc.mod.client.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import io.github.heavyseasmc.mod.client.MistFog;
import net.minecraft.block.enums.CameraSubmersionType;
import net.minecraft.client.render.BackgroundRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.FogShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 雾海的雾（ADR-0034 §5.1.1）：本模组唯一的 mixin。
 *
 * <h2>为什么注在 applyFog 的 TAIL</h2>
 * 1.21.1 的 {@code BackgroundRenderer.applyFog} 只有一个出口，最后三句是 setShaderFogStart / End / Shape；
 * 天空与地形两次雾都经过它（{@code WorldRenderer.render} 里一次直接调、一次经 renderSky 的回调）。
 * TAIL 在那三句之后再写一遍，两种雾都盖到；换成截 {@code render} 里那一次调用的话，天空那一次截不到。
 *
 * <h2>与版本绑死</h2>
 * 目标签名是 1.21.1 的。{@code heavyseas.client.mixins.json} 里 {@code defaultRequire: 1}：
 * 升级 MC 后目标对不上时客户端启动即崩，而不是在海上安静地没雾。
 */
@Mixin(BackgroundRenderer.class)
public abstract class BackgroundRendererMixin {

    @Inject(method = "applyFog", at = @At("TAIL"))
    private static void heavyseas$mistSeaFog(Camera camera, BackgroundRenderer.FogType fogType, float viewDistance,
                                             boolean thickFog, float tickDelta, CallbackInfo ci) {
        if (camera.getSubmersionType() != CameraSubmersionType.NONE) {
            return;                                   // 落水 / 岩浆 / 细雪：交给游戏默认
        }
        MistFog.Frame frame = MistFog.frame(viewDistance);
        if (frame == null) {
            return;
        }
        RenderSystem.setShaderFogStart(fogType == BackgroundRenderer.FogType.FOG_SKY ? 0f : frame.start());
        RenderSystem.setShaderFogEnd(frame.end());
        RenderSystem.setShaderFogShape(FogShape.SPHERE);
    }
}
