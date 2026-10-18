package io.github.heavyseasmc.mod.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.github.heavyseasmc.mod.client.ChatSkinHooks;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.text.OrderedText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 聊天输入框落在米色输入槽上（2026-10-13 C2）：字照旧由输入框自己画（位置、光标、选区、补全都按它那套字宽），
 * 只把<b>那一个</b>输入框的字改成纸上的墨 —— 去掉黑底才需要的阴影，给黑底配的亮色（指令高亮）换成深色，光标换成金色。
 * 别的输入框（设置、告示牌、书……）一概不动：每一处先问 {@code ChatSkinHooks.isChatField}。
 *
 * <p>去掉阴影后 Minecraft 量出来的字宽少 1（带阴影时多算 1），这里补回去，光标与后半截字的位置与原来逐像素相同。
 */
@Mixin(TextFieldWidget.class)
public abstract class TextFieldWidgetMixin {

    @WrapOperation(method = "renderWidget", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/OrderedText;III)I"))
    private int heavyseas$inkText(DrawContext context, TextRenderer renderer, OrderedText text, int x, int y, int color,
                                  Operation<Integer> original) {
        if (!ChatSkinHooks.isChatField((TextFieldWidget) (Object) this)) {
            return original.call(context, renderer, text, x, y, color);
        }
        return context.drawText(renderer, ChatSkinHooks.onPaper(text), x, y, color, false) + 1;
    }

    @WrapOperation(method = "renderWidget", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Ljava/lang/String;III)I"))
    private int heavyseas$inkString(DrawContext context, TextRenderer renderer, String text, int x, int y, int color,
                                    Operation<Integer> original) {
        if (!ChatSkinHooks.isChatField((TextFieldWidget) (Object) this)) {
            return original.call(context, renderer, text, x, y, color);
        }
        return context.drawText(renderer, text, x, y, ChatSkinHooks.onPaper(color), false) + 1;
    }

    @WrapOperation(method = "renderWidget", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;fill(Lnet/minecraft/client/render/RenderLayer;IIIII)V"))
    private void heavyseas$caret(DrawContext context, RenderLayer layer, int x1, int y1, int x2, int y2, int color,
                                 Operation<Void> original) {
        original.call(context, layer, x1, y1, x2, y2,
                ChatSkinHooks.isChatField((TextFieldWidget) (Object) this) ? ChatSkinHooks.caret() : color);
    }
}
