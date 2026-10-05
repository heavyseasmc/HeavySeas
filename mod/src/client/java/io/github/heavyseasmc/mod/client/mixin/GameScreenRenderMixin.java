package io.github.heavyseasmc.mod.client.mixin;

import io.github.heavyseasmc.mod.client.GameScreen;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 在各面计算布局、悬停和提示之前检查窗口下限；Minecraft 菜单保持原样。 */
@Mixin(Screen.class)
public abstract class GameScreenRenderMixin {

    @Inject(method = "renderWithTooltip", at = @At("HEAD"), cancellable = true)
    private void heavyseas$windowNotice(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if ((Object) this instanceof GameScreen screen && screen.renderWindowNotice(context)) {
            ci.cancel();
        }
    }
}
