package io.github.heavyseasmc.mod;

import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.mod.command.SeasCommand;
import io.github.heavyseasmc.mod.data.GameDataLoader;
import io.github.heavyseasmc.mod.game.ActionPhase;
import io.github.heavyseasmc.mod.game.GameFlow;
import io.github.heavyseasmc.mod.game.NavigationPhase;
import io.github.heavyseasmc.mod.game.ThirstPhase;
import io.github.heavyseasmc.mod.game.ProvisionPhase;
import io.github.heavyseasmc.mod.net.ActionChoiceC2S;
import io.github.heavyseasmc.mod.net.HelmActionC2S;
import io.github.heavyseasmc.mod.net.HelmAutoPickS2C;
import io.github.heavyseasmc.mod.net.ProvisionActionC2S;
import io.github.heavyseasmc.mod.net.ProvisionAutoPickS2C;
import io.github.heavyseasmc.mod.net.ProvisionUpdateS2C;
import io.github.heavyseasmc.mod.net.RowDecisionC2S;
import io.github.heavyseasmc.mod.net.ThirstActionC2S;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
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
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> SeasCommand.register(dispatcher));

        // 包都要在**两端**注册类型，否则一端发得出、另一端认不得，
        // 表现是安静地丢包而不是报错。客户端那一半在 HeavySeasClient。
        PayloadTypeRegistry.playS2C().register(ProvisionUpdateS2C.ID, ProvisionUpdateS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(ProvisionAutoPickS2C.ID, ProvisionAutoPickS2C.CODEC);
        PayloadTypeRegistry.playC2S().register(ProvisionActionC2S.ID, ProvisionActionC2S.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ProvisionActionC2S.ID,
                (payload, context) -> context.player().server.execute(
                        () -> ProvisionPhase.onAction(context.player(), payload)));
        // 行动一面按下的那一下。这一面不计时，所以没有对应的 tick（见 ActionPhase）。
        PayloadTypeRegistry.playC2S().register(ActionChoiceC2S.ID, ActionChoiceC2S.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ActionChoiceC2S.ID,
                (payload, context) -> context.player().server.execute(
                        () -> ActionPhase.onChoice(context.player(), payload)));
        // 划船一面上定下的一张。同样不计时。
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

        // 倒计时的权威在服务端：客户端自己算超时的话，改过的客户端可以永远不超时。
        ServerTickEvents.END_SERVER_TICK.register(ProvisionPhase::tick);
        ServerTickEvents.END_SERVER_TICK.register(NavigationPhase::tick);
        ServerTickEvents.END_SERVER_TICK.register(ThirstPhase::tick);
        // 排程：替身的一步、航海结算后的停顿（ADR-0019）。
        ServerTickEvents.END_SERVER_TICK.register(GameFlow::tick);
    }
}
