package io.github.heavyseasmc.mod.client.mixin;

import io.github.heavyseasmc.mod.client.SkyOverride;
import io.github.heavyseasmc.mod.net.SkyS2C;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 每个人的屏幕各自画雨雷（ADR-0054 §9.8 D12 第 4 条 (a)）。
 *
 * <p>雨丝、雨声、天空变灰、光照变暗、{@code isRaining()} 都从这两个方法取强度。只改客户端的世界
 * （{@link SkyOverride#forWorld} 先问 {@code isClient}）：单人游戏里服务端的世界也是这个类，绝不能碰。
 * 游戏本来的雷暴强度 = 雷 × 雨，这里照同样的乘法给出。
 */
@Mixin(World.class)
public abstract class WorldWeatherMixin {

    @Inject(method = "getRainGradient", at = @At("HEAD"), cancellable = true)
    private void heavyseas$skyRain(float delta, CallbackInfoReturnable<Float> cir) {
        SkyS2C sky = SkyOverride.forWorld((World) (Object) this);
        if (sky != null) {
            cir.setReturnValue(sky.rain());
        }
    }

    @Inject(method = "getThunderGradient", at = @At("HEAD"), cancellable = true)
    private void heavyseas$skyThunder(float delta, CallbackInfoReturnable<Float> cir) {
        SkyS2C sky = SkyOverride.forWorld((World) (Object) this);
        if (sky != null) {
            cir.setReturnValue(sky.thunder() * sky.rain());
        }
    }
}
