package io.github.heavyseasmc.mod.client;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;

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
            ScreenEvents.beforeRender(screen).register((ignored, context, mouseX, mouseY, delta) ->
                    gameScreen.reserveNotificationSidebar(context.getScaledWindowWidth()));

            // HudRenderCallback 比 Screen 先画，放在那里会被 GameScreen.renderBackdrop 盖住。
            // afterRender 包住的是 renderWithTooltip；侧栏因此永远是这一面的最后一层。
            ScreenEvents.afterRender(screen).register((ignored, context, mouseX, mouseY, delta) ->
                    GameHud.renderSidebar(context, client));
        });
    }
}
