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

            // GameScreen.init 已在子类创建控件前改过一次；这里仍要重申，因为 AFTER_INIT 也在
            // Screen.resize 后触发，而 Minecraft 的 resize 不再调用 protected init。每帧再算一次，让
            // 天候/通知在界面开着时出现或消失也能即时重排。
            gameScreen.reserveNotificationSidebar(scaledWidth);
            // 先于子类 keyPressed 拦截，避免 Enter 或数字键操作被提示盖住的牌。
            ScreenKeyboardEvents.allowKeyPress(screen).register((ignored, key, scan, modifiers) -> {
                if (!WindowNotice.required()) {
                    return true;
                }
                if (key == GLFW.GLFW_KEY_ESCAPE) {
                    gameScreen.close();
                } else if (HeavySeasClient.themeKey() != null && HeavySeasClient.themeKey().matchesKey(key, scan)) {
                    ClientPrefs.toggleTheme();
                }
                return false; // 全屏与截图由 Minecraft 在分发 Screen 按键之前处理。
            });
            ScreenMouseEvents.allowMouseClick(screen).register((ignored, x, y, button) -> !WindowNotice.required());
            ScreenMouseEvents.allowMouseRelease(screen).register((ignored, x, y, button) -> !WindowNotice.required());
            ScreenMouseEvents.allowMouseScroll(screen).register((ignored, x, y, horizontal, vertical) -> !WindowNotice.required());
            ScreenEvents.beforeRender(screen).register((ignored, context, mouseX, mouseY, delta) ->
                    gameScreen.reserveNotificationSidebar(context.getScaledWindowWidth()));

            // HudRenderCallback 比 Screen 先画，放在那里会被 GameScreen.renderBackdrop 盖住。
            // afterRender 包住的是 renderWithTooltip；侧栏因此永远是这一面的最后一层。
            ScreenEvents.afterRender(screen).register((ignored, context, mouseX, mouseY, delta) -> {
                gameScreen.endFold(context);                // 纸板开合推过的矩阵先弹回：侧栏与悬停签不跟着压扁（ADR-0048）
                if (WindowNotice.required()) {
                    return;
                }
                GameHud.renderSidebar(context, client);
                gameScreen.renderDetails(context, mouseX, mouseY);
                OverboardCue.drawFlash(context);            // 有人落海那一瞬：最上面一层（ADR-0048）
            });
        });
    }
}
