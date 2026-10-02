package io.github.heavyseasmc.mod.client.mixin;

import io.github.heavyseasmc.mod.client.SkyOverride;
import io.github.heavyseasmc.mod.net.SkyS2C;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 每个人的屏幕各自画时刻（ADR-0054 §9.8 D12 第 4 条 (a)）。
 *
 * <h2>为什么注在 Properties 上</h2>
 * 客户端读一天里的时刻全都经过这一处：天的角度与月相（{@code getLunarTime}）、{@code World#getTimeOfDay}、
 * 天空颜色、光照明暗 —— 再往外的 Sodium、Iris 的 {@code worldTime} 也是从这里取。改在这一处，下游全跟着变；
 * 改在画天空的那一步，光照与光影就会与天对不上。服务端每 20 tick 照例发来的时刻照收不误，只是读的时候被换掉。
 *
 * <p>同一时刻只有一份 Properties（换维度时整个换掉），用「是不是当前世界那一份」把别的实例排除在外。
 */
@Mixin(ClientWorld.Properties.class)
public abstract class ClientWorldPropertiesMixin {

    @Inject(method = "getTimeOfDay", at = @At("HEAD"), cancellable = true)
    private void heavyseas$skyTime(CallbackInfoReturnable<Long> cir) {
        ClientWorld world = MinecraftClient.getInstance().world;
        if (world == null || world.getLevelProperties() != (Object) this) {
            return;
        }
        SkyS2C sky = SkyOverride.forWorld(world);
        if (sky != null) {
            cir.setReturnValue(sky.timeOfDay());
        }
    }
}
