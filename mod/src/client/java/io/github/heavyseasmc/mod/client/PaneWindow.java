package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.ui.HudLayout;
import io.github.heavyseasmc.mod.ui.PaneBudget;
import net.minecraft.client.MinecraftClient;

/**
 * 这一帧对局界面排在多宽里（{@link PaneBudget}）的唯一入口：骨架（{@code GameScreen#sheet}）、旧稿子长度的换算
 * （{@link GuiMetrics}）、计分与设置两面的版面、日志那一栏画不画，都问这里 —— 只有一处决定「让不让」，
 * 界面那一块与日志那一栏就不会各算各的、一边让了一边没让。
 */
final class PaneWindow {

    private PaneWindow() {
    }

    /** 此刻的预算：屏幕上是对局界面（含聊天叠在它上面）才让；别的时候（世界里 · 世界里开的聊天 · 规则书）整窗。 */
    static PaneBudget current() {
        MinecraftClient client = MinecraftClient.getInstance();
        var window = client.getWindow();
        int w = window.getFramebufferWidth();
        int h = window.getFramebufferHeight();
        return PaneBudget.of(w, h, GameScreen.shownFace(client) != null && logWanted(client),
                logTextPx(w, h, ClientPrefs.largeReading()));
    }

    /**
     * 日志正文的字号（物理像素）：与 {@code GameHud#drawLog} 同一个算法 —— 整窗 {@link HudLayout} 的 {@code LOG_TEXT}
     * 按阅读字号梯子挑一级。栏宽由它算（{@link PaneBudget#logWidth}），所以大字时那一栏跟着变宽。
     */
    static int logTextPx(int framebufferWidth, int framebufferHeight, boolean large) {
        return GuiText.readingSizePx(HudLayout.of(framebufferWidth, framebufferHeight).len(HudLayout.LOG_TEXT), false, large);
    }

    /** 对局界面里要不要日志那一栏：钉住（L）· 对局进行中 · 没按 F1。 */
    static boolean logWanted(MinecraftClient client) {
        return SidebarReveal.pinned() && client.world != null && client.player != null && !client.options.hudHidden
                && GameComponents.of(client.world).hudView().active();
    }

    /** 钉住了、窗口却窄到收起（导航那一排给一枚小签）。 */
    static boolean folded() {
        MinecraftClient client = MinecraftClient.getInstance();
        var window = client.getWindow();
        return GameScreen.shownFace(client) != null
                && PaneBudget.folded(window.getFramebufferWidth(), window.getFramebufferHeight(), logWanted(client),
                logTextPx(window.getFramebufferWidth(), window.getFramebufferHeight(), ClientPrefs.largeReading()));
    }
}
