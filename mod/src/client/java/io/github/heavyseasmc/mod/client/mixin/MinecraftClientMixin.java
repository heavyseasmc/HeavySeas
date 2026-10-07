package io.github.heavyseasmc.mod.client.mixin;

import io.github.heavyseasmc.mod.client.DesignationAim;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 指定模式里的右键与轮廓（ADR-0095 F2）。
 *
 * <ul>
 *   <li>{@code doItemUse}：举着拳头时，右键确定 {@link DesignationAim} 选中的那个人，不再交给 Minecraft 的右键 ——
 *       坐成一排时准星射线先打到中间那个人，靠它点不到后面的。</li>
 *   <li>{@code hasOutline}：选中的那个人在自己屏幕上描一圈轮廓（只画在本地，别人看不见你指着谁）。</li>
 * </ul>
 * 不在指定模式里时两处都原样放过。与版本绑死：两个方法都是 1.21.1 的（{@code javap} 读过 Loom 的映射 jar）。
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {

    @Inject(method = "doItemUse", at = @At("HEAD"), cancellable = true)
    private void heavyseas$designate(CallbackInfo ci) {
        if (DesignationAim.confirm()) {
            ci.cancel();
        }
    }

    @Inject(method = "hasOutline", at = @At("HEAD"), cancellable = true)
    private void heavyseas$aimOutline(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (DesignationAim.outlined(entity)) {
            cir.setReturnValue(true);
        }
    }
}
