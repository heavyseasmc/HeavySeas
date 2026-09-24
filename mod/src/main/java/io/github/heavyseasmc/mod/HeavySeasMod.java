package io.github.heavyseasmc.mod;

import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.mod.command.SeasCommand;
import io.github.heavyseasmc.mod.data.GameDataLoader;
import io.github.heavyseasmc.mod.data.SceneDataLoader;
import io.github.heavyseasmc.mod.game.ActionPhase;
import io.github.heavyseasmc.mod.game.ContestPhase;
import io.github.heavyseasmc.mod.game.DesignationPhase;
import io.github.heavyseasmc.mod.game.GameFlow;
import io.github.heavyseasmc.mod.game.NavigationPhase;
import io.github.heavyseasmc.mod.game.ProvisionPhase;
import io.github.heavyseasmc.mod.game.ThirstPhase;
import io.github.heavyseasmc.mod.net.ActionChoiceC2S;
import io.github.heavyseasmc.mod.net.ContestActionC2S;
import io.github.heavyseasmc.mod.net.HelmActionC2S;
import io.github.heavyseasmc.mod.net.HelmAutoPickS2C;
import io.github.heavyseasmc.mod.net.ProvisionActionC2S;
import io.github.heavyseasmc.mod.net.ProvisionAutoPickS2C;
import io.github.heavyseasmc.mod.net.ProvisionUpdateS2C;
import io.github.heavyseasmc.mod.net.RowDecisionC2S;
import io.github.heavyseasmc.mod.net.CatalogS2C;
import io.github.heavyseasmc.mod.net.RosterConfigS2C;
import io.github.heavyseasmc.mod.net.StartVoyageC2S;
import io.github.heavyseasmc.mod.net.ThirstActionC2S;
import io.github.heavyseasmc.mod.net.UseProvisionC2S;
import io.github.heavyseasmc.mod.net.WaterDonationC2S;
import io.github.heavyseasmc.mod.world.SeatEntity;
import io.github.heavyseasmc.mod.world.Nameplates;
import io.github.heavyseasmc.mod.world.MistSea;
import io.github.heavyseasmc.mod.world.GullEntity;
import io.github.heavyseasmc.mod.world.Gulls;
import io.github.heavyseasmc.mod.world.LobbyBoatBlock;
import io.github.heavyseasmc.mod.world.LobbyBoatBlockEntity;
import io.github.heavyseasmc.mod.world.LobbyBoatEntity;
import io.github.heavyseasmc.mod.world.LobbyBoat;
import io.github.heavyseasmc.mod.world.SceneItems;
import io.github.heavyseasmc.mod.world.Seats;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模组入口。
 *
 * <p>此刻只做一件事：在 Minecraft 运行时里真的调用一次规则引擎。
 *
 * <h2>这行日志是正向对照，不是装饰</h2>
 * 引擎以普通 jar 的形式嵌套在本模组的 jar 里。嵌套装配错了，表现是服务端启动时
 * {@code NoClassDefFoundError}；而<b>开发环境测不出这一点</b> —— 那里引擎直接在类路径上，
 * 装没装进 jar 都能跑。所以只有生产环境启动时出现这一行，才证明嵌套装配是对的。
 */
public final class HeavySeasMod implements ModInitializer {

    public static final String MOD_ID = "heavyseas";

    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("规则引擎已接入：一回合 {} 个阶段", Phase.values().length);
        GameDataLoader.register();
        // 场景数据（航程布局，ADR-0034 §5.5）：与配平走同一条数据包重载路径，各读各的。
        SceneDataLoader.register();
        // 只用来挂模型的物品（布景 · 补给箱）：要在场景数据校验它们之前注册好。
        SceneItems.register();
        LobbyBoatBlock.register();
        LobbyBoatEntity.register();
        ServerChunkEvents.CHUNK_LOAD.register(LobbyBoatBlockEntity::restoreLegacyAnchors);
        // 座位实体（ADR-0024）：位次从此是世界里的空间关系。客户端那一半只给它一个空渲染器。
        SeatEntity.register();
        GullEntity.register();
        // ❗孤儿座位：加载事件只登记，tick 末尾才清。加载回调仍在实体管理器的遍历里，
        //   当场 discard 会让存档检查点抛 ConcurrentModificationException。
        ServerEntityEvents.ENTITY_LOAD.register(Seats::onSeatLoaded);
        // 队伍与座位同一个形状：它进 scoreboard.dat，上次没收干净的会原样留到下一次起服。
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            server.getWorlds().forEach(Nameplates::clear);
            // 船体是真方块、走廊是强加载票，都进存档：崩在对局中时这里清（ADR-0034 §5.2）。
            MistSea.resetScene(server);
        });
        // M4 crash recovery: the match itself is intentionally ephemeral, but escrowed real inventories are not.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                server.execute(() -> {
                    MistSea.recover(handler.player);
                    // 牌的目录（类别 · 张数 · 角标上的数）：这些是牌自己的属性，数据包说了算 ——
                    // 客户端的提示签与牌面角标要写它们，而在这个包之前根本拿不到数据。
                    // ❗发失败不是致命的：提示签少两栏、角标空着，牌照样能玩。所以只记一句，不打断进服。
                    try {
                        var data = GameDataLoader.require();
                        ServerPlayNetworking.send(handler.player,
                                CatalogS2C.of(data.provisions().all(), data.roster().characters(), data.weather()));
                    } catch (RuntimeException e) {
                        LOGGER.warn("牌目录没发出去（提示签上会少「类别 · 共几张」、角标空着）：{}", e.toString());
                    }
                }));
        // 指定模式（ADR-0025）：世界里右键一个人就是「我要对他动手」。
        // ❗只在指定模式里才作数，其余一律放行 —— 吃掉别人的右键会让人觉得「右键偶尔失灵」。
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) ->
                DesignationPhase.onUseEntity(player, world, entity));
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> SeasCommand.register(dispatcher));

        // 包都要在**两端**注册类型，否则一端发得出、另一端认不得，
        // 表现是安静地丢包而不是报错。客户端那一半在 HeavySeasClient。
        PayloadTypeRegistry.playS2C().register(ProvisionUpdateS2C.ID, ProvisionUpdateS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(ProvisionAutoPickS2C.ID, ProvisionAutoPickS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(RosterConfigS2C.ID, RosterConfigS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(CatalogS2C.ID, CatalogS2C.CODEC);
        PayloadTypeRegistry.playC2S().register(StartVoyageC2S.ID, StartVoyageC2S.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(StartVoyageC2S.ID,
                (payload, context) -> context.player().server.execute(
                        () -> LobbyBoat.launch(context.player(), payload)));
        PayloadTypeRegistry.playC2S().register(ProvisionActionC2S.ID, ProvisionActionC2S.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ProvisionActionC2S.ID,
                (payload, context) -> context.player().server.execute(
                        () -> ProvisionPhase.onAction(context.player(), payload)));
        // 行动一面按下的那一下；掉线保底的服务端倒计时在下面的 ActionPhase.tick。
        PayloadTypeRegistry.playC2S().register(ActionChoiceC2S.ID, ActionChoiceC2S.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ActionChoiceC2S.ID,
                (payload, context) -> context.player().server.execute(
                        () -> ActionPhase.onChoice(context.player(), payload)));
        // 手牌里打出特殊物资；医疗箱会用同一个包走第二步挑目标。
        PayloadTypeRegistry.playC2S().register(UseProvisionC2S.ID, UseProvisionC2S.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(UseProvisionC2S.ID,
                (payload, context) -> context.player().server.execute(
                        () -> ActionPhase.onUseProvision(context.player(), payload)));
        // 划船一面上定下的一张。
        PayloadTypeRegistry.playC2S().register(RowDecisionC2S.ID, RowDecisionC2S.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(RowDecisionC2S.ID,
                (payload, context) -> context.player().server.execute(
                        () -> ActionPhase.onRowDecision(context.player(), payload)));
        // 舵手挑牌：移高亮 / 执行，以及「这张是替你挑的」。
        PayloadTypeRegistry.playC2S().register(HelmActionC2S.ID, HelmActionC2S.CODEC);
        PayloadTypeRegistry.playS2C().register(HelmAutoPickS2C.ID, HelmAutoPickS2C.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(HelmActionC2S.ID,
                (payload, context) -> context.player().server.execute(
                        () -> NavigationPhase.onAction(context.player(), payload)));
        // 口渴：喝几张（ADR-0021）。同样是「移高亮 / 就按这个数」两用。
        PayloadTypeRegistry.playC2S().register(ThirstActionC2S.ID, ThirstActionC2S.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ThirstActionC2S.ID,
                (payload, context) -> context.player().server.execute(
                        () -> ThirstPhase.onAction(context.player(), payload)));
        // 旁人在同一个口渴窗口里替当前角色打一张水。
        PayloadTypeRegistry.playC2S().register(WaterDonationC2S.ID, WaterDonationC2S.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(WaterDonationC2S.ID,
                (payload, context) -> context.player().server.execute(
                        () -> ThirstPhase.onDonation(context.player(), payload)));

        // 这一场进行中的四面：表态 · 站队 · 挂武器 · 挑牌（ADR-0023）。四面一个包，按 Kind 分路。
        PayloadTypeRegistry.playC2S().register(ContestActionC2S.ID, ContestActionC2S.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ContestActionC2S.ID,
                (payload, context) -> context.player().server.execute(
                        () -> ContestPhase.onAction(context.player(), payload)));

        // 倒计时的权威在服务端：客户端自己算超时的话，改过的客户端可以永远不超时。
        ServerTickEvents.END_SERVER_TICK.register(Seats::tick);
        ServerTickEvents.END_SERVER_TICK.register(ProvisionPhase::tick);
        ServerTickEvents.END_SERVER_TICK.register(ActionPhase::tick);
        ServerTickEvents.END_SERVER_TICK.register(NavigationPhase::tick);
        ServerTickEvents.END_SERVER_TICK.register(ThirstPhase::tick);
        ServerTickEvents.END_SERVER_TICK.register(ContestPhase::tick);
        ServerTickEvents.END_SERVER_TICK.register(DesignationPhase::tick);
        // 排程：替身的一步、航海结算后的停顿（ADR-0019）。
        ServerTickEvents.END_SERVER_TICK.register(GameFlow::tick);
        ServerTickEvents.END_SERVER_TICK.register(MistSea::tick);
        ServerTickEvents.END_SERVER_TICK.register(Gulls::tick);
    }
}
