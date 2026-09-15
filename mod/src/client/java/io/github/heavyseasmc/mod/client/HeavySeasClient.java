package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.net.HelmAutoPickS2C;
import io.github.heavyseasmc.mod.net.ProvisionAutoPickS2C;
import io.github.heavyseasmc.mod.net.ProvisionUpdateS2C;
import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.state.HudView;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端入口。
 *
 * <h2>为什么这些类必须待在 src/client</h2>
 * {@code MinecraftClient} 这类只在客户端存在。写进 {@code main} 的话，专用服务端一加载就崩，
 * 而<b>开发环境与单人游戏都测不出来</b>（那里两边的类都在）。源码集拆开之后，
 * 这种引用在**编译期**就失败 —— 拆之前实测过，main 里能直接编译过。见 ADR-0013。
 */
public final class HeavySeasClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

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

    /**
     * 打开行动一面（决策 ⑦）；划船抽到的牌还没定完时，打开的是划船一面。默认 G；
     * 显示与关界面同样走绑定的那个键，理由同 {@link #handKey}。
     */
    private static KeyBinding actKey;

    /**
     * 轮到你了、但行动一面还没弹出来。
     *
     * <p>轮到你的那一刻可能正开着别的界面（补给箱的「顿」还没播完、聊天打到一半）。那时不抢，
     * 记一笔，等它关了再弹 —— 只弹这一次：Esc 收起行动一面之后，它不会再自己冒出来。
     */
    private static boolean actionPending;
    private static boolean wasMyTurn;

    /** 划船抽到的牌到了、划船一面还没弹出来。与 {@link #actionPending} 同一个道理：只弹一次。 */
    private static boolean rowPending;
    private static boolean wasRowing;

    /** 已经为哪一个挑牌窗口弹过舵手一面（以窗口的超时时刻认）。 */
    private static long helmWindowShown;

    public static KeyBinding actKey() {
        return actKey;
    }

    @Override
    public void onInitializeClient() {
        HudRenderCallback.EVENT.register(GameHud::render);

        handKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.heavyseas.hand", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_H,
                "key.categories.heavyseas"));
        ClientTickEvents.END_CLIENT_TICK.register(HeavySeasClient::pollHandKey);
        actKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.heavyseas.act", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G,
                "key.categories.heavyseas"));
        ClientTickEvents.END_CLIENT_TICK.register(HeavySeasClient::pollTurn);

        // 进服就把卡面载好：补给箱第一次打开时现场载，「发」的动画会在那一帧卡掉一截。
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                client.execute(() -> CardTexture.preloadProvisions(client)));

        // ❗必须在客户端也注册接收器：类型只在一端注册的话，包会被安静地丢掉，不报错。
        ClientPlayNetworking.registerGlobalReceiver(ProvisionUpdateS2C.ID, (payload, context) ->
                context.client().execute(() -> onProvisionUpdate(context.client(), payload)));
        // 「这张是替你选的」。服务端先发它、后发留牌那一包，两者都排进同一条主线程队列，
        // 所以到这里时次序是有保证的 —— 先立住「顿」，再由下面那一包决定什么时候关。
        ClientPlayNetworking.registerGlobalReceiver(ProvisionAutoPickS2C.ID, (payload, context) ->
                context.client().execute(() -> {
                    if (context.client().currentScreen instanceof ProvisionScreen screen) {
                        screen.autoPicked(payload.index());
                    }
                }));
        // 「这张是替你挑的」（舵手超时）。次序的道理与上面相同：先到，结算后的那一次投影后到。
        ClientPlayNetworking.registerGlobalReceiver(HelmAutoPickS2C.ID, (payload, context) ->
                context.client().execute(() -> {
                    if (context.client().currentScreen instanceof HelmScreen screen) {
                        screen.autoPicked(payload.index());
                    }
                }));
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
     * 轮到你的几面：行动、划船、舵手挑牌。
     *
     * <p>❗行动与划船只在「刚轮到」时弹一次，之后按行动键再开：这两面不计时，玩家收起来是为了回世界里谈判 ——
     * 每 tick 都弹的话，Esc 就收不起来了。
     *
     * <p>舵手一面有倒计时，与补给箱同一个优先级：窗口一开就弹，顶掉不计时的界面；被什么顶掉了，窗口还开着就回来。
     */
    private static void pollTurn(MinecraftClient client) {
        boolean pressed = false;
        while (actKey.wasPressed()) {
            pressed = true;
        }
        HudView view = client.world == null ? HudView.IDLE : GameComponents.of(client.world).hudView();

        boolean mine = view.myTurnToAct();
        if (mine && !wasMyTurn) {
            actionPending = true;
        }
        if (!mine) {
            actionPending = false;
        }
        wasMyTurn = mine;

        boolean rowing = view.myRowPending();
        if (rowing && !wasRowing) {
            rowPending = true;
            // 与语言无关的一行：GUI 回归靠它判「抽到的牌进了我这一包」。
            LOGGER.info("划船：收到抽到的 {} 张", view.sea().rowing().size());
        }
        if (!rowing) {
            rowPending = false;
        }
        wasRowing = rowing;

        if (view.myHelmPick()) {
            long window = view.sea().helmDeadlineMs();
            boolean fresh = window != helmWindowShown;
            if (fresh) {
                helmWindowShown = window;
                // ❗这一行是「划船堆的牌只进舵手那一包」在接收端的证据：不是舵手的客户端上永远不该出现。
                LOGGER.info("舵手：收到划船堆 {} 张", view.sea().helmOffer().size());
            }
            if (client.currentScreen instanceof HelmScreen || client.currentScreen instanceof ProvisionScreen) {
                return;
            }
            if (client.currentScreen instanceof RowScreen row && row.flying()) {
                return;                       // 划船的最后一张还在飞：飞完再弹，不截断
            }
            if (fresh || client.currentScreen == null) {
                client.setScreen(new HelmScreen(view));
            }
            return;
        }

        if (client.currentScreen != null) {
            return;                           // 已经有界面开着（补给箱、聊天……）时不抢
        }
        if (rowing && (rowPending || pressed)) {
            rowPending = false;
            client.setScreen(new RowScreen());
            return;
        }
        if (mine && (actionPending || pressed)) {
            actionPending = false;
            client.setScreen(new ActionScreen());
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
            } else if (screen.snapping()) {
                // ❗替你选的那一下还没播完。这里要是照常关掉，界面在「顿」的第一帧就消失了 ——
                //   和手动点完就关**长得一模一样**，等于这一条又回到 ADR-0018 §6 清单第 4 条的反面。
                screen.closeAfterSnap();
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
