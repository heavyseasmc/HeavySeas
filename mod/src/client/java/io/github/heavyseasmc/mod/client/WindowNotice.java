package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.ui.HudLayout;
import io.github.heavyseasmc.mod.ui.HudPart;
import io.github.heavyseasmc.mod.state.GameComponents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;

/** 低于支持尺寸时只画一张可读的提示；正常内容与隐藏按钮不再参与绘制和点击。 */
final class WindowNotice {

    private WindowNotice() {
    }

    static boolean required() {
        var window = MinecraftClient.getInstance().getWindow();
        return !HudLayout.supportsWindow(window.getFramebufferWidth(), window.getFramebufferHeight());
    }

    static void render(DrawContext context) {
        var window = MinecraftClient.getInstance().getWindow();
        int width = window.getFramebufferWidth();
        int height = window.getFramebufferHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        GuiMaterial.dimWorld(context);
        context.getMatrices().push();
        context.getMatrices().scale((float) (1 / window.getScaleFactor()), (float) (1 / window.getScaleFactor()), 1f);
        int w = Math.max(1, Math.min(580, width - 32));
        int h = Math.max(1, Math.min(280, height - 16));
        int x = (width - w) / 2;
        int y = (height - h) / 2;
        GuiMaterial.hudPart(context, HudPart.SHEET, x, y, w, h, 1.0);
        int tx = x + 32;
        int textW = Math.max(1, w - 64);
        int ink = GuiLanguage.ink();
        GuiText.drawPx(context, Text.translatable("heavyseas.window.too_small").getString(), tx, y + 28,
                textW, 28, false, ink, GuiText.Align.CENTER, 0);
        GuiText.paragraphPx(context, Text.translatable("heavyseas.window.minimum", HudLayout.MIN_WINDOW_W,
                        HudLayout.MIN_WINDOW_H).getString(), tx, y + 80, textW, 18, false, ink, 2, 26);
        GuiText.paragraphPx(context, Text.translatable("heavyseas.window.fullscreen", Text.keybind("key.fullscreen")).getString(),
                tx, y + 136, textW, 18, false, ink, 2, 26);
        GuiText.drawPx(context, Text.translatable("heavyseas.window.current", width, height).getString(),
                tx, y + 192, textW, 16, false, GuiLanguage.muted(), GuiText.Align.CENTER, 0);
        var client = MinecraftClient.getInstance();
        if (client.world != null && GameComponents.of(client.world).hudView().active()) {
            GuiText.drawPx(context, Text.translatable("heavyseas.window.live").getString(),
                    tx, y + 228, textW, 14, false, GuiLanguage.muted(), GuiText.Align.CENTER, 0);
        }
        context.getMatrices().pop();
    }
}
