package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.net.ProvisionUpdateS2C;
import io.github.heavyseasmc.mod.state.GameComponents;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端入口。
 *
 * <h2>为什么这些类必须待在 src/client</h2>
 * {@code MinecraftClient} 这类只在客户端存在。写进 {@code main} 的话，专用服务端一加载就崩，
 * 而<b>开发环境与单人游戏都测不出来</b>（那里两边的类都在）。源码集拆开之后，
 * 这种引用在**编译期**就失败 —— 拆之前实测过，main 里能直接编译过。见 ADR-0013。
 */
public final class HeavySeasClient implements ClientModInitializer {

    /**
     * 打开手牌。
     *
     * <h2>为什么是键位不是指令</h2>
     * ADR-0017 定了 GUI 第一位、指令退出玩家路径。手牌是玩家整局最常回头看的东西
     * （「我还有没有水」决定敢不敢答应别人），让它挂在一条要打字的指令上等于没做。
     *
     * <p>❗默认给 H，但界面里<b>不写死 H</b>：显示与关界面都走
     * {@link KeyBinding#getBoundKeyLocalizedText()} 与 {@link KeyBinding#matchesKey}，
     * 否则玩家改了键位，提示就开始说谎。
     */
    private static KeyBinding handKey;

    /** 给 HUD 与手牌界面用：它们要显示「按哪个键」，也要认这个键收起界面。 */
    public static KeyBinding handKey() {
        return handKey;
    }

    @Override
    public void onInitializeClient() {
        HudRenderCallback.EVENT.register(GameHud::render);

        handKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.heavyseas.hand", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H,
                "key.categories.heavyseas"));
        ClientTickEvents.END_CLIENT_TICK.register(HeavySeasClient::pollHandKey);

        // 进服就把卡面载好：补给箱第一次打开时现场载，「发」的动画会在那一帧卡掉一截。
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                client.execute(() -> CardTexture.preloadProvisions(client)));

        // ❗必须在客户端也注册接收器：类型只在一端注册的话，包会被安静地丢掉，不报错。
        ClientPlayNetworking.registerGlobalReceiver(ProvisionUpdateS2C.ID, (payload, context) ->
                context.client().execute(() -> onProvisionUpdate(context.client(), payload)));
    }

    /**
     * 按键抬起后才算一次。
     *
     * <p>{@code wasPressed()} 每次只消一次按下，所以用 while 排空 —— 一 tick 内按两下也认两下。
     * 手上没牌时什么都不做：开一个空界面比不开更让人以为坏了。
     */
    private static void pollHandKey(MinecraftClient client) {
        boolean pressed = false;
        while (handKey.wasPressed()) {
            pressed = true;
        }
        if (!pressed || client.world == null || client.currentScreen != null) {
            return;                           // 已经有界面开着（比如补给箱）时不抢
        }
        if (GameComponents.of(client.world).hudView().hasHand()) {
            client.setScreen(new HandScreen());
        }
    }

    /**
     * 补给箱的包到了。
     *
     * <p>只有轮到自己时 {@code offer} 才有内容（服务端按收件人裁剪过），
     * 所以「该不该开界面」直接看它空不空，客户端不需要也不应该自己判断轮到谁。
     */
    private static void onProvisionUpdate(MinecraftClient client, ProvisionUpdateS2C payload) {
        boolean mine = payload.active() && !payload.offer().isEmpty();
        if (client.currentScreen instanceof ProvisionScreen screen) {
            if (mine) {
                screen.apply(payload);
            } else {
                client.setScreen(null);      // 传走了就关掉，别让界面挂在那儿
            }
            return;
        }
        if (mine) {
            // ❗补给箱是有倒计时的决策，优先级高于正开着的手牌界面：直接顶掉。
            client.setScreen(new ProvisionScreen(payload));
        }
    }
}
