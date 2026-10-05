package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.ui.HudLayout;
import net.minecraft.client.MinecraftClient;

/** 旧稿子的 GUI 长度先按窗口换成物理像素，再换成 Minecraft 的绘制坐标。 */
final class GuiMetrics {

    private GuiMetrics() {
    }

    static double pixels(double designUnits) {
        var window = MinecraftClient.getInstance().getWindow();
        return HudLayout.of(window.getFramebufferWidth(), window.getFramebufferHeight()).designGuiPixels(designUnits);
    }

    static float units(double designUnits) {
        return (float) (pixels(designUnits) / MinecraftClient.getInstance().getWindow().getScaleFactor());
    }
}
