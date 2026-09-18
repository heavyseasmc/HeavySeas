package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.net.ActionChoiceC2S;
import io.github.heavyseasmc.mod.net.HelmAutoPickS2C;
import io.github.heavyseasmc.mod.net.ProvisionAutoPickS2C;
import io.github.heavyseasmc.mod.net.ProvisionUpdateS2C;
import io.github.heavyseasmc.mod.net.RosterConfigS2C;
import io.github.heavyseasmc.mod.state.ContestView;
import io.github.heavyseasmc.mod.state.EndgameProgress;
import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.state.HudView;
import io.github.heavyseasmc.mod.world.SeatEntity;
import io.github.heavyseasmc.mod.world.GullEntity;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.render.entity.ParrotEntityRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
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
     * <p>❗默认给 R，但界面里<b>不写死 R</b>：显示与关界面都走
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

    /** 上一次弹出口渴一面的那个窗口。同一个窗口只弹一次。 */
    private static long thirstWindowShown;

    /**
     * 换座位 / 抢夺那一场里，已经为哪一个窗口弹过界面（以窗口的超时时刻认，与口渴、舵手同一个写法）。
     *
     * <p>❗窗口会在一段中途<b>重置</b>：有人加入把站队那 15 秒重置成 8 秒、有人押牌把 10 秒重置成 6 秒。
     * 重置就是一个新窗口 —— 局面变了（多了一个人、多了一张暗牌），该再问一次。
     */
    private static long contestWindowShown;

    /**
     * 终局的哪一个阶段已经弹过（恨 · 爱 · 计分）。每个阶段只自己弹一次：Esc 收起之后按行动键再开。
     * <b>真的弹出来了才记</b> —— 阶段开始时聊天正开着，关掉聊天之后照样要弹。
     */
    private static EndgameProgress.Stage endgameStageShown;

    public static KeyBinding actKey() {
        return actKey;
    }

    @Override
    public void onInitializeClient() {
        HudRenderCallback.EVENT.register(GameHud::render);
        GameScreenSidebar.register();

        // ❗注册了实体类型却没给渲染器，客户端第一次看见座位时会崩 —— 而专用服务端测不出来。
        EntityRendererRegistry.register(SeatEntity.TYPE, SeatEntityRenderer::new);
        EntityRendererRegistry.register(GullEntity.TYPE, ParrotEntityRenderer::new);

        handKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.heavyseas.hand", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_R,
                "key.categories.heavyseas"));
        ClientLifecycleEvents.CLIENT_STARTED.register(HeavySeasClient::migrateLegacyHandKey);
        ClientTickEvents.END_CLIENT_TICK.register(HeavySeasClient::pollHandKey);
        actKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.heavyseas.act", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G,
                "key.categories.heavyseas"));
        ClientTickEvents.END_CLIENT_TICK.register(HeavySeasClient::pollTurn);

        // 进服就把卡面载好：补给箱第一次打开时现场载，「发」的动画会在那一帧卡掉一截。
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
                client.execute(() -> CardTexture.preload(client)));

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
        ClientPlayNetworking.registerGlobalReceiver(RosterConfigS2C.ID, (payload, context) ->
                context.client().execute(() -> context.client().setScreen(new RosterScreen(payload))));
        // 「这张是替你挑的」（舵手超时）。次序的道理与上面相同：先到，结算后的那一次投影后到。
        ClientPlayNetworking.registerGlobalReceiver(HelmAutoPickS2C.ID, (payload, context) ->
                context.client().execute(() -> {
                    if (context.client().currentScreen instanceof HelmScreen screen) {
                        screen.autoPicked(payload.index());
                    }
                }));
    }

    /** Moves the former H default only when another action still occupies H. */
    private static void migrateLegacyHandKey(MinecraftClient client) {
        if (!handKey.getBoundKeyTranslationKey().equals("key.keyboard.h")) {
            return;                           // 玩家已经改过，尊重他的设置
        }
        KeyBinding conflict = null;
        for (KeyBinding binding : client.options.allKeys) {
            if (binding != handKey && binding.getBoundKeyTranslationKey().equals("key.keyboard.h")) {
                conflict = binding;
                break;
            }
        }
        if (conflict == null) {
            return;                           // 没装占用 H 的模组时，旧绑定仍可继续用
        }
        client.options.setKeyCode(handKey, InputUtil.Type.KEYSYM.createFromCode(GLFW.GLFW_KEY_R));
        KeyBinding.updateKeysByCode();
        client.options.write();
        LOGGER.info("键位：手牌旧默认 H 与 {} 冲突，已迁移到 R", conflict.getTranslationKey());
    }

    /**
     * 按键抬起后才算一次。
     *
     * <p>{@code wasPressed()} 每次只消一次按下，所以用 while 排空 —— 一 tick 内按两下也认两下。
     * 手上没牌、也还没发爱恨时什么都不做：开一个空界面比不开更让人以为坏了。
     * 爱恨发下来之后空手也开 —— 自己爱谁恨谁就写在那一面里（ADR-0022）。
     */
    private static void pollHandKey(MinecraftClient client) {
        boolean pressed = false;
        while (handKey.wasPressed()) {
            pressed = true;
        }
        if (!pressed || client.world == null || client.currentScreen != null) {
            return;                           // 已经有界面开着（比如补给箱）时不抢
        }
        HudView view = GameComponents.of(client.world).hudView();
        if (view.hasHand() || (view.active() && view.seated() && !view.love().isEmpty())) {
            client.setScreen(new HandScreen());
        }
    }

    /**
     * 轮到你的几面：行动、划船、舵手挑牌；对局结束之后是终局的翻牌与计分（ADR-0022）。
     *
     * <p>❗行动与划船只在「刚轮到」时弹一次，之后按行动键再开。服务端倒计时不会因 Esc 暂停；
     * 每 tick 都弹的话，玩家仍然收不起来回世界谈判。
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

        // 终局排在所有决策面前面：对局已经结束，决策面开着也没有东西可选了。
        if (view.myEndgame()) {
            EndgameProgress.Stage stage = view.endgame().stage();
            if (stage == EndgameProgress.Stage.ARRIVAL) {
                if (client.currentScreen instanceof GameScreen && !(client.currentScreen instanceof HandScreen)) {
                    client.setScreen(null); // the first act belongs to the world, not an overlay
                }
                endgameStageShown = stage;
                return;
            }
            boolean scores = stage == EndgameProgress.Stage.SCORES;
            Screen current = client.currentScreen;
            if (scores ? current instanceof ScoreScreen : current instanceof RevealScreen) {
                endgameStageShown = stage;
                return;
            }
            boolean fresh = endgameStageShown != stage;
            // 决策面一律顶掉；手牌是玩家自己按开的，只在换阶段时顶掉 —— 不然终局里就再也看不了手牌。
            // ❗聊天等别的界面开着时不抢：终局正是全船在聊天里喊「最后一张是谁」的时候。
            boolean stale = current instanceof GameScreen && !(current instanceof HandScreen);
            if (stale || (current instanceof HandScreen && fresh) || (current == null && (fresh || pressed))) {
                endgameStageShown = stage;
                client.setScreen(scores ? new ScoreScreen() : new RevealScreen());
            }
            return;
        }
        endgameStageShown = null;

        // 医疗箱的目标一面：它由手牌一面的 U 触发，服务端确认效果与持牌后才会出现在投影里。
        if (view.myProvisionTarget()) {
            if (!(client.currentScreen instanceof ProvisionTargetScreen)) {
                client.setScreen(new ProvisionTargetScreen(view));
            }
            return;
        }

        // 举着拳头找人时（ADR-0025）：行动一面不该弹（投影里 yourTurn 已经排掉了），
        // 按行动键是**取消**，退回行动一面 —— 决策 ⑦ 的「退回 GUI 重选」由它提供。
        if (view.myDesignating()) {
            if (pressed) {
                ClientPlayNetworking.send(ActionChoiceC2S.of(ActionChoiceC2S.Kind.CANCEL));
                LOGGER.info("指定模式：按了取消");
            }
            return;
        }

        // 换座位 / 抢夺的四面（ADR-0023）。排在这里是因为它属于行动阶段，而下面那两面属于航海与口渴 ——
        // 同一帧里不会两者都为真，排序只是让「轮到我表态」不被后面任何一条 return 截在半路。
        if (pollContest(client, view, pressed)) {
            return;
        }

        // ❗口渴排在舵手前面判：这两面都在航海阶段，但口渴是舵手挑完之后的事 ——
        //   同一帧里两者不会同时为真，排序只是让「后来的那一面」不被前面那条 return 截在半路。
        if (view.myThirstChoice()) {
            long window = view.thirstPrompt().deadlineMs();
            boolean fresh = window != thirstWindowShown;
            if (fresh) {
                thirstWindowShown = window;
                // 与语言无关的一行：GUI 回归靠它判「口渴一面真的问到了我」。
                LOGGER.info("口渴：轮到我决定，还需化解 {} 次 · 手上 {} 张水",
                        view.thirstPrompt().remaining(), view.myWaters());
            }
            if (client.currentScreen instanceof ThirstScreen) {
                return;
            }
            if (fresh || client.currentScreen == null) {
                client.setScreen(new ThirstScreen(view));
            }
            return;
        }

        // 不是我口渴，但我能替他打水。第一次自动弹；Esc 收起后不纠缠，按行动键还能再打开。
        if (view.myWaterDonation()) {
            long window = view.thirstPrompt().deadlineMs();
            boolean fresh = window != thirstWindowShown;
            if (fresh) {
                thirstWindowShown = window;
                LOGGER.info("口渴：我能替 {} 打水 · 可用 {} 张",
                        view.thirstPrompt().who(), view.myWaters() - view.myDonatedWater());
            }
            if (client.currentScreen instanceof WaterDonationScreen) {
                return;
            }
            if (fresh || (pressed && client.currentScreen == null)) {
                client.setScreen(new WaterDonationScreen(view));
            }
            return;
        }

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
     * 换座位 / 抢夺那一场里轮到我的四面（ADR-0023 · 决策 ④）。返回 {@code true} 表示这一帧归它，别的界面别再抢。
     *
     * <h2>一场只停在一段上，而每一段只问一种人</h2>
     * 所以下面四条至多一条为真 —— 判据全在 {@link HudView} 那四个谓词里，这里只回答「什么时候弹」。
     *
     * <h2>两种优先级，分界线是「不答会怎样」</h2>
     * <b>表态与挑牌不答也有后果</b>（超时按同意算 · 超时替你从他手牌里抽一张），所以它们与口渴、舵手同一条：
     * 被什么顶掉了，只要窗口还开着就回来。
     *
     * <p><b>站队与挂武器不答就是不参与</b> —— 那本身就是一个正当答案（规则里没有「宣布中立」这种状态）。
     * 所以它们与行动一面同一条：窗口刚开（或被重置）时弹一次，收起来之后不再自己冒出来，按行动键随时能再开。
     * 收不起来的话，站队那十几秒会被一个界面整个占掉 —— 而在世界里谈判正是这一段的全部意义。
     */
    private static boolean pollContest(MinecraftClient client, HudView view, boolean pressed) {
        ContestView contest = view.contest();
        if (!contest.waiting()) {
            return false;                     // 没有这一场，或这一段没人要等（全是替身时由排程一步一步推）
        }
        long window = contest.deadlineMs();
        boolean fresh = window != contestWindowShown;
        Screen open = client.currentScreen;

        if (view.myConsent()) {
            if (fresh) {
                contestWindowShown = window;
                // 与语言无关的一行：GUI 回归靠它判「表态这一面真的问到了我」。
                LOGGER.info("表态：{} 对我发起了 {}", contest.attacker(), contest.kind());
            }
            if (open instanceof ConsentScreen) {
                return true;                  // 已经开着：窗口没重置过，这里不会走到，写着是为了不重开
            }
            if (fresh || pressed || open == null) {
                client.setScreen(new ConsentScreen(view));
            }
            return true;
        }

        if (view.myStance()) {
            if (fresh) {
                contestWindowShown = window;
                LOGGER.info("站队：打起来了 · 进攻 {} 人（体型和 {}） · 防守 {} 人（体型和 {}）",
                        contest.attackSide().size(), contest.attackPower(),
                        contest.defendSide().size(), contest.defendPower());
            }
            if (open instanceof StanceScreen) {
                return true;                  // 有人加入把窗口重置了：界面照旧开着，它自己会读到新的投影
            }
            if (fresh || pressed) {
                client.setScreen(new StanceScreen(view));
            }
            return true;
        }

        if (view.myWeaponChoice()) {
            if (fresh) {
                contestWindowShown = window;
                // ❗只写张数，不写是哪几张 —— 暗牌（决策 ④），而开服的人往往也是玩家。
                LOGGER.info("挂武器：轮到我 · 还押得出 {} 张 · 已押 {} 张",
                        contest.myWeapons().size(), contest.myCommitted());
            }
            if (open instanceof WeaponScreen) {
                return true;                  // 自己押下一张也会重置窗口：不重开，那会把「抬」打断
            }
            if (fresh || pressed) {
                client.setScreen(new WeaponScreen(view));
            }
            return true;
        }

        if (view.myPick()) {
            if (fresh) {
                contestWindowShown = window;
                LOGGER.info("挑牌：轮到我 · 他面前 {} 张 · 手上 {} 张",
                        contest.victimFront().size(), contest.victimHand());
            }
            if (open instanceof PickScreen) {
                return true;
            }
            if (fresh || pressed || open == null) {
                client.setScreen(new PickScreen(view));
            }
            return true;
        }
        return false;                         // 这一场在等的是别人：HUD 上看得见，但什么也不弹
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
