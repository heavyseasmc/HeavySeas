package io.github.heavyseasmc.mod.client;

import net.minecraft.client.MinecraftClient;

/**
 * 旧稿子的 GUI 长度先按窗口换成物理像素，再换成 Minecraft 的绘制坐标。
 * 「窗口」是对局界面那一块（{@link PaneWindow}）：日志钉住、界面给它让出右栏时，字号与动效跟着骨架一起收。
 */
final class GuiMetrics {

    private GuiMetrics() {
    }

    static double pixels(double designUnits) {
        return PaneWindow.current().hud().designGuiPixels(designUnits);
    }

    static float units(double designUnits) {
        return (float) (pixels(designUnits) / MinecraftClient.getInstance().getWindow().getScaleFactor());
    }
}
