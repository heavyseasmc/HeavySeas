package io.github.heavyseasmc.mod.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.heavyseasmc.mod.client.TelescopeEyepiece;
import net.minecraft.client.Mouse;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 望远镜视角（ADR-0109 ③）里鼠标灵敏度跟着拉近降下来：包住 {@code updateMouse} 末尾那一次 {@code changeLookDirection}，
 * 两个分量都乘 {@link TelescopeEyepiece#sensitivityScale}（按视角的半角正切之比，同一下鼠标在屏幕上挪的距离不变）。
 * 不拉近时乘 1，原样放过。
 *
 * <p>与版本绑死：{@code Mouse#updateMouse(double)} 里只有这一次 {@code ClientPlayerEntity.changeLookDirection(DD)V}
 * （1.21.1，{@code javap -c} 读过 Loom 的映射 jar）。{@code defaultRequire: 1}，对不上时启动即崩。
 */
@Mixin(Mouse.class)
public abstract class TelescopeMouseMixin {

    @WrapOperation(method = "updateMouse", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/network/ClientPlayerEntity;changeLookDirection(DD)V"))
    private void heavyseas$telescopeSensitivity(ClientPlayerEntity player, double dx, double dy, Operation<Void> original) {
        double k = TelescopeEyepiece.sensitivityScale();
        original.call(player, dx * k, dy * k);
    }
}
