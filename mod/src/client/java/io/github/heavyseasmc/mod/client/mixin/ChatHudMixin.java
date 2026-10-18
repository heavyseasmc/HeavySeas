package io.github.heavyseasmc.mod.client.mixin;

import io.github.heavyseasmc.mod.client.ChatSkinHooks;
import java.util.List;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.gui.hud.MessageIndicator;
import net.minecraft.network.message.MessageSignatureData;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 聊天栏的皮肤（2026-10-13 用户定 C1 · C2）：只换<b>画法</b>。收消息、存消息、删消息、发消息、指令补全一概照旧；
 * 画的那一下交给 {@code ChatSkin}，「指针下是哪个字」（点链接、悬停说明）与滚动的夹法按它排出来的版面答。
 * 另记一份给对局面板的窄条与未读数（{@code addMessage} 开头）。
 *
 * <p>目标是 1.21.1 的 yarn 名（Loom 映射 jar 反编译核过）；{@code defaultRequire: 1}，对不上时启动即崩。
 * 生产 jar 由 {@code checkMixinRemapped} 核对。
 */
@Mixin(ChatHud.class)
public abstract class ChatHudMixin {

    @Shadow
    @Final
    private List<ChatHudLine> messages;

    @Shadow
    private int scrolledLines;

    @Shadow
    private boolean hasUnreadNewMessages;

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void heavyseas$skin(DrawContext context, int currentTick, int mouseX, int mouseY, boolean focused,
                                CallbackInfo ci) {
        if (ChatSkinHooks.skinned()) {
            ChatSkinHooks.paint(context, messages, currentTick, focused, scrolledLines, (ChatHud) (Object) this);
            ci.cancel();
        }
    }

    @Inject(method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
            at = @At("HEAD"))
    private void heavyseas$arrived(Text message, MessageSignatureData signature, MessageIndicator indicator,
                                   CallbackInfo ci) {
        ChatSkinHooks.received(message);
    }

    @Inject(method = "clear", at = @At("HEAD"))
    private void heavyseas$cleared(boolean clearHistory, CallbackInfo ci) {
        ChatSkinHooks.wiped();
    }

    @Inject(method = "getTextStyleAt", at = @At("HEAD"), cancellable = true)
    private void heavyseas$styleAt(double x, double y, CallbackInfoReturnable<Style> cir) {
        if (ChatSkinHooks.skinned()) {
            cir.setReturnValue(ChatSkinHooks.pick(x, y));
        }
    }

    /** 皮肤下不画消息来源的小标记，指针也就不会落在标记上（悬停说明交给字本身的样式）。 */
    @Inject(method = "getIndicatorAt", at = @At("HEAD"), cancellable = true)
    private void heavyseas$indicatorAt(double x, double y, CallbackInfoReturnable<MessageIndicator> cir) {
        if (ChatSkinHooks.skinned()) {
            cir.setReturnValue(null);
        }
    }

    /** 往上翻几行按皮肤排出来的行数夹（两边字宽不同，Minecraft 自己折出来的行数对不上）。 */
    @Inject(method = "scroll", at = @At("HEAD"), cancellable = true)
    private void heavyseas$scroll(int amount, CallbackInfo ci) {
        if (ChatSkinHooks.skinned()) {
            scrolledLines = ChatSkinHooks.clampRows(scrolledLines + amount);
            if (scrolledLines == 0) {
                hasUnreadNewMessages = false;
            }
            ci.cancel();
        }
    }
}
