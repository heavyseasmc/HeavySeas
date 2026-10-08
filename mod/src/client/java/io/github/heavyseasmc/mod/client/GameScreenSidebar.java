package io.github.heavyseasmc.mod.client;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import org.lwjgl.glfw.GLFW;

/** 把对局界面的主内容与右侧通知栏接到 Minecraft 正确的渲染层级上。 */
final class GameScreenSidebar {

    private GameScreenSidebar() {
    }

    static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof GameScreen gameScreen)) {
                return;
            }

            // 先于子类 keyPressed 拦截，避免 Enter 或数字键操作被提示盖住的牌。
            ScreenKeyboardEvents.allowKeyPress(screen).register((ignored, key, scan, modifiers) -> {
                if (!WindowNotice.required()) {
                    return true;
                }
                if (key == GLFW.GLFW_KEY_ESCAPE) {
                    // 照这一面自己的 Esc 走（审查 2026-10-07 K3 · Z6）：原先一律 close()，补给箱这种不许关的面一按就没了、
                    // 再也找不回来（它只在收到包时打开）。854×480（Minecraft 的默认窗口）就在这条线以下。
                    gameScreen.escapeInSmallWindow();
                } else if (HeavySeasClient.themeKey() != null && HeavySeasClient.themeKey().matchesKey(key, scan)) {
                    ClientPrefs.toggleTheme();
                }
                return false; // 全屏与截图由 Minecraft 在分发 Screen 按键之前处理。
            });
            ScreenMouseEvents.allowMouseClick(screen).register((ignored, x, y, button) -> !WindowNotice.required());
            ScreenMouseEvents.allowMouseRelease(screen).register((ignored, x, y, button) -> !WindowNotice.required());
            ScreenMouseEvents.allowMouseScroll(screen).register((ignored, x, y, horizontal, vertical) -> !WindowNotice.required());
            // HudRenderCallback 比 Screen 先画，放在那里会被 GameScreen.renderBackdrop 盖住。
            // afterRender 包住的是 renderWithTooltip；侧栏因此永远是这一面的最后一层。
            ScreenEvents.afterRender(screen).register((ignored, context, mouseX, mouseY, delta) -> {
                gameScreen.endFold(context);                // 纸板开合推过的矩阵先弹回：侧栏与悬停签不跟着压扁（ADR-0048）
                if (WindowNotice.required()) {
                    return;
                }
                GameHud.renderSidebar(context, client);
                gameScreen.renderDetails(context, mouseX, mouseY);
                ActionBarEcho.draw(context, gameScreen);    // 动作栏那一句：被纸板盖住的拒绝理由（2026-10-07）
                OverboardCue.drawFlash(context);           // 有人落海那一瞬：最上面一层（ADR-0048）
            });
        });
    }
}
