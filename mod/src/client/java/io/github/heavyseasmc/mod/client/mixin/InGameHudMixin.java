package io.github.heavyseasmc.mod.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.heavyseasmc.mod.client.ActionBarEcho;
import io.github.heavyseasmc.mod.client.GameHud;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 雾海里的热栏、心、饥饿、护甲、氧气、坐骑血量、经验条与等级共用维度/模式判据。
 * 创造与旁观模式不拦截这些绘制方法；其他模式不论有没有对局都隐藏。维度外不拦截。
 *
 * <h2>热栏只换底，不换行为</h2>
 * 包住 {@code renderHotbar} 里画底与选中框的那两次 {@code drawGuiTexture}：底换成样张的深色格；
 * 选中框只在选中的那一格有东西时才画（样张里没有它，而对局中背包是空的 —— 有东西时照旧看得出选的是哪一格）。
 * 物品、副手、攻击冷却一概照 Minecraft 自带的样子画。
 *
 * <h2>与版本绑死</h2>
 * 目标方法与字段是 1.21.1 的（{@code javap} 读过 Loom 的映射 jar）。{@code defaultRequire: 1}：对不上时启动即崩。
 */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {

    @Shadow
    @Final
    private static Identifier HOTBAR_TEXTURE;

    @Shadow
    @Final
    private static Identifier HOTBAR_SELECTION_TEXTURE;

    /** 动作栏收到的每一句都记一份：对局各面的纸板盖住了它，由 {@link ActionBarEcho} 在界面最上层再画一遍。 */
    @Inject(method = "setOverlayMessage", at = @At("HEAD"))
    private void heavyseas$echoOverlay(Text message, boolean tinted, CallbackInfo ci) {
        ActionBarEcho.record(message);
    }

    @Inject(method = "renderStatusBars", at = @At("HEAD"), cancellable = true)
    private void heavyseas$statusBars(DrawContext context, CallbackInfo ci) {
        if (GameHud.hotbarHidden()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderMountHealth", at = @At("HEAD"), cancellable = true)
    private void heavyseas$mountHealth(DrawContext context, CallbackInfo ci) {
        if (GameHud.hotbarHidden()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderExperienceBar", at = @At("HEAD"), cancellable = true)
    private void heavyseas$experienceBar(DrawContext context, int x, CallbackInfo ci) {
        if (GameHud.hotbarHidden()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderExperienceLevel", at = @At("HEAD"), cancellable = true)
    private void heavyseas$experienceLevel(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (GameHud.hotbarHidden()) {
            ci.cancel();
        }
    }

    /**
     * 热栏及其物品图标统一隐藏；创造与旁观豁免，北辰号上没开局时也使用同一判据。
     */
    @Inject(method = "renderHotbar", at = @At("HEAD"), cancellable = true)
    private void heavyseas$hideHotbar(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (GameHud.hotbarHidden()) {
            ci.cancel();
        }
    }

    /** 换格时浮在热栏上方的那一行物品名：热栏都不画了，它也跟着不画。 */
    @Inject(method = "renderHeldItemTooltip", at = @At("HEAD"), cancellable = true)
    private void heavyseas$hideHeldItemName(DrawContext context, CallbackInfo ci) {
        if (GameHud.hotbarHidden()) {
            ci.cancel();
        }
    }

    @WrapOperation(method = "renderHotbar", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;drawGuiTexture(Lnet/minecraft/util/Identifier;IIII)V"))
    private void heavyseas$hotbar(DrawContext context, Identifier texture, int x, int y, int w, int h,
                                  Operation<Void> original) {
        if (!GameHud.voyageHud()) {
            original.call(context, texture, x, y, w, h);
            return;
        }
        if (texture == HOTBAR_TEXTURE) {
            GameHud.drawHotbar(context, x, y, w, h);
            return;
        }
        if (texture == HOTBAR_SELECTION_TEXTURE) {
            var player = MinecraftClient.getInstance().player;
            if (player == null || player.getInventory().getMainHandStack().isEmpty()) {
                return;
            }
        }
        original.call(context, texture, x, y, w, h);
    }
}
