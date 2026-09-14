package io.github.heavyseasmc.mod.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

/**
 * 客户端入口。
 *
 * <h2>为什么这些类必须待在 src/client</h2>
 * {@code MinecraftClient} 这类只在客户端存在。写进 {@code main} 的话，专用服务端一加载就崩，
 * 而<b>开发环境与单人游戏都测不出来</b>（那里两边的类都在）。源码集拆开之后，
 * 这种引用在**编译期**就失败 —— 拆之前实测过，main 里能直接编译过。见 ADR-0013。
 */
public final class HeavySeasClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        HudRenderCallback.EVENT.register(GameHud::render);
    }
}
