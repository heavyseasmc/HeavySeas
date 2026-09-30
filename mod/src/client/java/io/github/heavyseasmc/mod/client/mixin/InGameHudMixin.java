package io.github.heavyseasmc.mod.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.heavyseasmc.mod.client.GameHud;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 对局中 Minecraft 自带的 HUD 照样张 b-1（ADR-0046）：心 · 饥饿 · 护甲 · 氧气 · 坐骑血量 · 经验条与等级不画，热栏画成样张那一条。
 *
 * <h2>为什么可以不画</h2>
 * 对局中这几条不带信息：服务端每 tick 把饥饿钉在满、血量只是引擎体力的镜像、身体不许受伤（{@code PlayerBodies}），
 * 背包开航时托管清空（{@code MistSea}）。体力已经画在状态牌的点上。没有对局时（大厅、别的服）一切照 Minecraft 自带的；
 * 偏好 {@code vanillaHud=show} 也回到 Minecraft 自带的样子 —— 用户 2026-09-30：「不要直接删除这些功能」。
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

    @Inject(method = "renderStatusBars", at = @At("HEAD"), cancellable = true)
    private void heavyseas$statusBars(DrawContext context, CallbackInfo ci) {
        if (GameHud.voyageHud()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderMountHealth", at = @At("HEAD"), cancellable = true)
    private void heavyseas$mountHealth(DrawContext context, CallbackInfo ci) {
        if (GameHud.voyageHud()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderExperienceBar", at = @At("HEAD"), cancellable = true)
    private void heavyseas$experienceBar(DrawContext context, int x, CallbackInfo ci) {
        if (GameHud.voyageHud()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderExperienceLevel", at = @At("HEAD"), cancellable = true)
    private void heavyseas$experienceLevel(DrawContext context, RenderTickCounter tickCounter, CallbackInfo ci) {
        if (GameHud.voyageHud()) {
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
