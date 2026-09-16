package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.play.Contest;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.state.GameComponent;
import io.github.heavyseasmc.mod.state.GameComponents;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 指定模式（ADR-0025 · 决策 ⑦）：按下「换座位」或「抢夺」之后回到世界里，看着那个人右键。
 *
 * <h2>为什么目标要走物理</h2>
 * 它创造了一个**预告窗口**：全船都看见「他站起来了，他要动手，但不知道对谁」，
 * 受害者因此有时间临场谈判 ——「等等，你要抢我？我给你一张水，你去抢小孩」。
 * <b>这个窗口本身就是谈判游戏的内容</b>（决策 ⑦）。GUI 里选目标是瞬发的，那一幕就没有了。
 *
 * <h2>小孩没有预告</h2>
 * 他<b>只能偷手牌</b>，而预告窗口里受害者把手牌全亮出来就完全免疫了 ——
 * 那等于凭空给所有人一个规则里不存在的免疫手段。所以他不发光、不播报，点完即锁（决策 ⑦）。
 *
 * <h2>规则不在这里</h2>
 * 谁能对谁发起、发起之后怎么走，全在 {@link ContestPhase} 与引擎的状态机里（ADR-0023）。
 * 这一处只回答三件事：<b>谁在举着拳头 · 他点到了谁 · 到点了没有</b>。
 */
public final class DesignationPhase {

    /** 举着拳头找人的时间。到点算 Pass（ADR-0025 §7.6 说明了与决策 ⑦ 的出入）。 */
    public static final long WINDOW_MILLIS = 15_000L;

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private DesignationPhase() {
    }

    /**
     * 行动一面上按下了「换座位」或「抢夺」。
     *
     * <p>小孩的抢夺不进指定模式吗？—— <b>照样进</b>。他省掉的是<b>预告</b>（不发光、不播报），
     * 不是「选人」那一步：规则里他也要指一个人。
     */
    public static void begin(ServerWorld world, GameComponent component, CharacterId actor, Contest.Kind kind) {
        boolean quiet = quiet(component, actor);
        component.beginDesignation(actor, kind, WINDOW_MILLIS);
        ServerPlayerEntity player = playerOf(world, component, actor);
        if (player != null && !quiet) {
            player.setGlowing(true);          // 全船看得见的那一下（ADR-0025 §7.3：头顶图标的替身）
        }
        // 与语言无关的一行：验收靠它判「指定模式真的开起来了」。
        LOGGER.info("指定模式：{} 要{}（{} 秒{}）", actor.value(), kind == Contest.Kind.STEAL ? "抢夺" : "换座位",
                WINDOW_MILLIS / 1000, quiet ? " · 无预告" : "");
        if (!quiet) {
            GameFlow.broadcast(world, Text.translatable(kind == Contest.Kind.STEAL
                            ? "heavyseas.designate.announce_steal" : "heavyseas.designate.announce_swap",
                    GameFlow.characterName(actor), WINDOW_MILLIS / 1000).formatted(Formatting.RED));
        }
        GameComponents.sync(world);
    }

    /**
     * 世界里右键了一个实体。**只在指定模式里才作数**，其余一律放行给 Minecraft 自己处理。
     *
     * <p>❗返回 {@code PASS} 表示「这一下不归我管」。返回 {@code SUCCESS} 会吃掉这一下右键 ——
     * 只有真的被我们用掉时才那么返回，否则玩家会觉得右键"偶尔失灵"。
     */
    public static ActionResult onUseEntity(net.minecraft.entity.player.PlayerEntity clicker,
                                           net.minecraft.world.World rawWorld, Entity target) {
        if (!(rawWorld instanceof ServerWorld world) || !(clicker instanceof ServerPlayerEntity player)) {
            return ActionResult.PASS;         // 客户端那一侧不判：判定只在服务端（改过的客户端发得出任何东西）
        }
        GameComponent component = GameComponents.of(world);
        Optional<CharacterId> who = component.designating();
        if (who.isEmpty() || component.session().isEmpty()) {
            return ActionResult.PASS;
        }
        Optional<CharacterId> me = component.seatOf(player.getUuid());
        if (me.isEmpty() || !me.get().equals(who.get())) {
            return ActionResult.PASS;         // 举着拳头的不是他：这一下与我们无关
        }
        Optional<CharacterId> picked = component.seatOf(target.getUuid());
        if (picked.isEmpty()) {
            // 点到了船外的东西（路过的牛、旁观者）。吃掉这一下并说一句 ——
            // 不说的话，玩家会以为自己点中了，然后干等 15 秒。
            player.sendMessage(Text.translatable("heavyseas.designate.not_aboard").formatted(Formatting.GRAY), true);
            return ActionResult.SUCCESS;
        }
        if (picked.get().equals(me.get())) {
            player.sendMessage(Text.translatable("heavyseas.designate.not_self").formatted(Formatting.GRAY), true);
            return ActionResult.SUCCESS;
        }
        Contest.Kind kind = component.designationKind().orElse(Contest.Kind.SWAP);
        Session session = component.requireSession();
        if (session.state().isRemoved(picked.get())) {
            player.sendMessage(Text.translatable("heavyseas.command.removed",
                    GameFlow.characterName(picked.get())).formatted(Formatting.GRAY), true);
            return ActionResult.SUCCESS;
        }
        LOGGER.info("指定模式：{} 点了 {}", me.get().value(), picked.get().value());
        finish(world, component, me.get());
        ContestPhase.declare(world, component, me.get(), kind, picked.get());
        return ActionResult.SUCCESS;
    }

    /** 主动取消：退回行动一面，这一回合还没用掉。ADR-0025 §7.6 用它代替决策 ⑦ 的「退回 GUI 重选」。 */
    public static void cancel(ServerWorld world, GameComponent component, CharacterId actor) {
        LOGGER.info("指定模式：{} 取消了", actor.value());
        finish(world, component, actor);
        GameComponents.sync(world);
    }

    /**
     * 每 tick 查超时。**服务端超时，客户端不参与判定**（与另外几个窗口同一条）。
     *
     * <p>到点算 Pass：那是「什么也不做」，不会凭空给谁制造伤害。
     */
    public static void tick(MinecraftServer server) {
        for (ServerWorld world : server.getWorlds()) {
            GameComponent component = GameComponents.of(world);
            long deadline = component.designationDeadline();
            if (component.session().isEmpty() || deadline <= 0 || System.currentTimeMillis() < deadline) {
                continue;
            }
            Optional<CharacterId> actor = component.designating();
            finish(world, component, actor.orElse(null));
            if (actor.isEmpty()) {
                continue;
            }
            LOGGER.info("指定超时：{} 没点人，算什么也不做", actor.get().value());
            GameFlow.broadcast(world, Text.translatable("heavyseas.command.passed",
                    GameFlow.characterName(actor.get())));
            GameFlow.finishAction(world, component, actor.get());
        }
    }

    /**
     * 收摊：熄灯 + 清状态。
     *
     * <p>❗每一条退出路径都要走到这里（点中了 · 超时 · 主动取消 · 对局结束 · 掉线）——
     * <b>发光是写在实体上的状态，忘了关它不会报错</b>，只会留下一个永远发光的人。
     */
    public static void finish(ServerWorld world, GameComponent component, CharacterId actor) {
        if (actor != null) {
            ServerPlayerEntity player = playerOf(world, component, actor);
            if (player != null) {
                player.setGlowing(false);
            }
        }
        component.clearDesignation();
    }

    /** 对局结束时把可能还亮着的那一位熄掉。 */
    public static void clear(ServerWorld world, GameComponent component) {
        component.designating().ifPresent(actor -> finish(world, component, actor));
    }

    /** 小孩的抢夺没有预告（决策 ⑦）：不发光、不播报。 */
    private static boolean quiet(GameComponent component, CharacterId actor) {
        return component.requireSession().stealsUncontested(actor);
    }

    private static ServerPlayerEntity playerOf(ServerWorld world, GameComponent component, CharacterId who) {
        return component.occupantOf(who)
                .filter(o -> !o.isDummy())
                .map(o -> world.getServer().getPlayerManager().getPlayer(o.player()))
                .orElse(null);
    }
}
