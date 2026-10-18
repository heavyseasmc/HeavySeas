package io.github.heavyseasmc.mod.client;

import java.util.List;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.gui.hud.ChatHudLine;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

/**
 * 三个聊天 mixin（{@code ChatHudMixin} · {@code ChatScreenMixin} · {@code TextFieldWidgetMixin}）进 {@link ChatSkin} 的门。
 *
 * <p>❗方法名故意不与 mixin 的目标同名（render · addMessage · clear · scroll · fill……）：{@code checkMixinRemapped}
 * 拿目标名去生产类的常量池里找残留，自家方法叫同一个名字就会被当成「没翻过去的目标」。
 */
public final class ChatSkinHooks {

    private ChatSkinHooks() {
    }

    public static boolean skinned() {
        return ChatSkin.on();
    }

    public static void paint(DrawContext context, List<ChatHudLine> lines, int tick, boolean focused, int scrolled, ChatHud hud) {
        ChatSkin.draw(context, lines, tick, focused, scrolled, hud);
    }

    public static void received(Text message) {
        ChatSkin.arrived(message);
    }

    public static void wiped() {
        ChatSkin.forget();
    }

    public static Style pick(double x, double y) {
        return ChatSkin.styleUnder(x, y);
    }

    public static int clampRows(int wanted) {
        return ChatSkin.clampScroll(wanted);
    }

    /** 聊天界面建好了输入框：收进页里的输入槽、字改成墨；同时算「看过了」。 */
    public static void fieldReady(TextFieldWidget field) {
        ChatSkin.opened(field);
        if (ChatSkin.on()) {
            int[] span = ChatSkin.fieldSpan();
            field.setX(span[0]);
            field.setWidth(span[1]);
        }
    }

    public static void slot(DrawContext context) {
        ChatSkin.drawInputSlot(context);
    }

    public static boolean isChatField(TextFieldWidget field) {
        return ChatSkin.ownsField(field);
    }

    public static OrderedText onPaper(OrderedText text) {
        return ChatSkin.inkOnPaper(text);
    }

    /** 输入框里写死的颜色（补全的灰字）换成纸上的；正文那一色（已经是墨）原样。 */
    public static int onPaper(int color) {
        if ((color & 0xFFFFFF) == (GuiLanguage.Chat.PAGE_INK & 0xFFFFFF)) {
            return color;
        }
        return (color & 0xFF000000) | (GuiLanguage.onTag(GuiLanguage.semantic(color & 0xFFFFFF)) & 0xFFFFFF);
    }

    public static int caret() {
        return GuiLanguage.Chat.CARET;
    }
}
