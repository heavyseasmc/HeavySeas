package io.github.heavyseasmc.mod.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.heavyseasmc.mod.client.ChatSkinHooks;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 按 T 打开的聊天界面（2026-10-13 用户定 C2 账本页）：只换输入框底下那一条黑底 —— 换成页里的米色输入槽，
 * 输入框横向收进槽里、字改成纸上的墨（{@code TextFieldWidgetMixin}）。输入、历史、补全、点击都不动。
 * 打开即算「看过了」：对局面板「聊天」小签上的未读数清零。
 */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin {

    @Shadow
    protected TextFieldWidget chatField;

    @Inject(method = "init", at = @At("TAIL"))
    private void heavyseas$opened(CallbackInfo ci) {
        ChatSkinHooks.fieldReady(chatField);
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;fill(IIIII)V"))
    private void heavyseas$inputSlot(DrawContext context, int x1, int y1, int x2, int y2, int color,
                                     Operation<Void> original) {
        if (ChatSkinHooks.skinned()) {
            ChatSkinHooks.slot(context);
            return;
        }
        original.call(context, x1, y1, x2, y2, color);
    }
}
