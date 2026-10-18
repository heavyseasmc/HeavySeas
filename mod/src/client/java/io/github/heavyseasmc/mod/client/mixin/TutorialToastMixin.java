package io.github.heavyseasmc.mod.client.mixin;

import io.github.heavyseasmc.mod.client.GameHud;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;
import net.minecraft.client.toast.TutorialToast;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Movement tutorials cannot be completed while seated and must not cover decisions. Progress remains untouched. */
@Mixin(TutorialToast.class)
public abstract class TutorialToastMixin {
    @Shadow private Toast.Visibility visibility;

    @Inject(method = "draw", at = @At("HEAD"), cancellable = true)
    private void heavyseas$keepDecisionVisible(DrawContext context, ToastManager manager, long time,
                                             CallbackInfoReturnable<Toast.Visibility> cir) {
        if (GameHud.tutorialHidden()) cir.setReturnValue(visibility);
    }
}
