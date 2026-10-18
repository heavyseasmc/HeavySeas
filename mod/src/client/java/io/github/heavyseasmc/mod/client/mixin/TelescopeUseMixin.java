package io.github.heavyseasmc.mod.client.mixin;

import io.github.heavyseasmc.mod.client.TelescopeEyepiece;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 望远镜视角（ADR-0109 ③）里再按一下右键退出：{@code doItemUse} 的 HEAD 先问 {@link TelescopeEyepiece#swallowUse} ——
 * 看着时右键只用来退出，不再交给 Minecraft（不会顺手打开别的东西）；进来与退出的那一下右键按着不放时，
 * 游戏每 4 tick 会再「用」一次，松开之前一律吞掉（不吞的话一进来就退出、一退出又进来）。闸的规则在 {@code TelescopeSight.UseGate}。
 *
 * <p>与版本绑死：{@code MinecraftClient#doItemUse()} 是 1.21.1 的（同 {@code MinecraftClientMixin}）。
 */
@Mixin(MinecraftClient.class)
public abstract class TelescopeUseMixin {

    @Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
    private void heavyseas$telescopeUse(CallbackInfo ci) {
        if (TelescopeEyepiece.swallowUse()) {
            ci.cancel();
        }
    }
}
