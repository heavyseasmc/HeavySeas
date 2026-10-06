package io.github.heavyseasmc.mod.client.mixin;

import io.github.heavyseasmc.mod.client.GameHud;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 主画面里（没开界面）航海日志钉住时，滚轮翻日志（用户 2026-10-07：「航海日志不能滚动」）。
 *
 * <p>界面里的滚轮走 {@code GameScreen#mouseScrolled}，不经过这里；这里只管没开界面的那一半 ——
 * 那时滚轮本来是换快捷栏，而对局中背包托管清空，换格没有意义。判不判全在 {@link GameHud#scrollLog}，这里只转过去。
 *
 * <p>与版本绑死：{@code Mouse#onMouseScroll(long, double, double)} 是 1.21.1 的（{@code javap} 读过 Loom 的映射 jar）。
 * 只认游戏自己那扇窗口的回调，别的窗口句柄一律放过。
 */
@Mixin(Mouse.class)
public abstract class MouseScrollMixin {

    @Inject(method = "onMouseScroll", at = @At("HEAD"), cancellable = true)
    private void heavyseas$scrollLog(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (window == MinecraftClient.getInstance().getWindow().getHandle() && GameHud.scrollLog(vertical)) {
            ci.cancel();
        }
    }
}
