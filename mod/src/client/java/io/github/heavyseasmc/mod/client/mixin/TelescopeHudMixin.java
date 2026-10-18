package io.github.heavyseasmc.mod.client.mixin;

import io.github.heavyseasmc.mod.client.TelescopeEyepiece;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 望远镜视角（ADR-0109 ③）的屏幕那一层：
 * <ul>
 *   <li>{@code renderMiscOverlays} 的 TAIL：画自己的圆形暗角 —— 这一层是 Minecraft 画南瓜头、传送门、自带望远镜镜框的那一层，
 *       在准星与快捷栏之前，所以快捷栏、动作栏、本模组的 HUD 照样画在暗角上面。按 F1 隐藏界面时这一层也不画（同游戏自带的望远镜）。</li>
 *   <li>{@code renderCrosshair} 的 HEAD：看着时不画准星。</li>
 * </ul>
 * 与版本绑死：两个方法都是 1.21.1 的（{@code javap} 读过 Loom 的映射 jar：{@code (DrawContext, RenderTickCounter)V}）。
 */
@Mixin(InGameHud.class)
public abstract class TelescopeHudMixin {

    @Inject(method = "renderMiscOverlays", at = @At("TAIL"))
    private void heavyseas$telescopeRim(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        TelescopeEyepiece.render(context, tickCounter.getTickDelta(false));
    }

    @Inject(method = "renderCrosshair", at = @At("HEAD"), cancellable = true)
    private void heavyseas$telescopeCrosshair(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (TelescopeEyepiece.active()) {
            ci.cancel();
        }
    }
}
