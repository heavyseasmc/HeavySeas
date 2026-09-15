package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.state.GameState;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.net.ProvisionActionC2S;
import io.github.heavyseasmc.mod.net.ProvisionAutoPickS2C;
import io.github.heavyseasmc.mod.net.ProvisionUpdateS2C;
import io.github.heavyseasmc.mod.state.GameComponent;
import io.github.heavyseasmc.mod.state.GameComponents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * 物资阶段的服务端一侧：发牌、计时、超时代选、推进。
 *
 * <h2>规则不在这里</h2>
 * 抽几张、谁先拿、留下之后传给谁，全在 {@link Session} 里 —— 模拟器与这里跑的是同一份。
 * 本类只做三件事：**按收件人裁剪、计时、把结果播出去**。
 *
 * <h2>服务端是计时的权威</h2>
 * 倒计时写在服务端（{@code deadlineMs}），客户端只是显示。客户端自己算超时的话，
 * 改过的客户端可以永远不超时，整局就卡在那里。
 */
public final class ProvisionPhase {

    /** 决策 ⑨：倒计时按剩余牌数缩放，N 张 = N×2 秒。 */
    public static final long MILLIS_PER_CARD = 2000L;

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private ProvisionPhase() {
    }

    /** 开一轮传递。牌堆空了就直接返回 false，由调用方跳过本阶段。 */
    public static boolean begin(ServerWorld world, GameComponent component) {
        Session session = component.requireSession();
        List<String> offer = session.beginProvision();
        if (offer.isEmpty()) {
            // 抽完即止：之后每回合直接略过本阶段（规则明写，不洗回重用）。
            return false;
        }
        armDeadline(component, offer.size());
        broadcast(world, component);
        autoPlayIfDummy(world, component);
        return true;
    }

    /** 客户端来的一个动作：移高亮，或者留牌。 */
    public static void onAction(ServerPlayerEntity player, ProvisionActionC2S action) {
        ServerWorld world = player.getServerWorld();
        GameComponent component = GameComponents.of(world);
        if (component.session().isEmpty()) {
            return;
        }
        Session session = component.requireSession();
        Optional<CharacterId> holder = session.provisionHolder();
        if (holder.isEmpty()) {
            return;
        }
        // ❗只有持有者说了算。不校验的话，任何人都能替别人留牌。
        Optional<CharacterId> seat = component.seatOf(player.getUuid());
        if (seat.isEmpty() || !seat.get().equals(holder.get())) {
            return;
        }
        List<String> offer = session.provisionOffer();
        if (action.index() < 0 || action.index() >= offer.size()) {
            return;                       // 越界的下标一律忽略：可能是包与状态擦肩而过
        }
        component.setProvisionHighlight(action.index());
        if (action.commit()) {
            keep(world, component, action.index());
        }
    }

    /** 每 tick 检查超时。**服务端超时，客户端不参与判定。** */
    public static void tick(MinecraftServer server) {
        for (ServerWorld world : server.getWorlds()) {
            GameComponent component = GameComponents.of(world);
            if (component.session().isEmpty()) {
                continue;
            }
            Session session = component.requireSession();
            if (!session.provisionInProgress()) {
                continue;
            }
            long deadline = component.provisionDeadline();
            if (deadline > 0 && System.currentTimeMillis() >= deadline) {
                // 决策 ⑨：超时自动选**当前高亮**那张，不是随机。
                // 客户端一次都没上报过高亮（比如离线）时取第一张 —— 决策 ⑧ 要求局面不能卡住。
                int index = Math.min(component.provisionHighlight(),
                        Math.max(0, session.provisionOffer().size() - 1));
                // 与语言无关的一行（与「舵手超时」同一对）：验收要分得开「他自己按的」与「等超时」——
                // 两者在对局推进上完全一样，而界面没弹出来时也会走到这里。
                LOGGER.info("补给箱超时：替 {} 选了第 {} 张（当前高亮）",
                        session.provisionHolder().map(CharacterId::value).orElse("?"), index + 1);
                // ❗先告诉持有者「这张是替你选的」，再真的留牌。
                //   顺序不能反：keep 里紧接着就 broadcast，那一包会让客户端关掉界面 ——
                //   通知落在它后面，就没有界面来播这一下「顿」了。
                notifyAutoPick(world, component, index);
                keep(world, component, index);
            }
        }
    }

    /**
     * 告诉持有者：这一张是替你选的。
     *
     * <h2>ADR-0018 §6 的一致性清单第 4 条</h2>
     * 「系统替你做的决定与你自己做的长得一样，是最伤的一种不诚实」。超时与手动在屏幕上
     * 必须看得出区别 —— 手动是点完就关，超时是先<b>顿</b>一下再关。
     *
     * <p>只发给持有者本人，而且只在他<b>在线且是真人</b>时发：替身根本走不到超时
     * （{@link #autoPlayIfDummy} 已经替它选了），离线的人也没有界面可播。
     */
    private static void notifyAutoPick(ServerWorld world, GameComponent component, int index) {
        component.requireSession().provisionHolder()
                .flatMap(component::occupantOf)
                .map(GameComponent.Occupant::player)
                .map(uuid -> world.getServer().getPlayerManager().getPlayer(uuid))
                .ifPresent(player -> ServerPlayNetworking.send(player, new ProvisionAutoPickS2C(index)));
    }

    private static void keep(ServerWorld world, GameComponent component, int index) {
        Session session = component.requireSession();
        List<String> offer = session.provisionOffer();
        if (offer.isEmpty()) {
            return;
        }
        CharacterId holder = session.provisionHolder().orElseThrow();
        String card = offer.get(Math.min(index, offer.size() - 1));
        session.provisionKeep(card);
        // ❗牌一进手里就推一次投影。漏推的表现是「留了牌，手牌界面里没有」——
        //   看起来像手牌那一面坏了，其实是这里少了一行。
        GameComponents.sync(world);

        if (session.provisionInProgress()) {
            armDeadline(component, session.provisionOffer().size());
            broadcast(world, component);
            autoPlayIfDummy(world, component);
            return;
        }

        component.clearProvision();
        broadcastFinished(world);
        LOGGER.info("物资阶段结束：{} 位各留 1 张，牌堆还剩 {} 张，全船手牌 {} 张",
                session.state().consciousBySeat().size(), session.table().provisionsLeft(),
                handTotal(session));
        GameFlow.afterProvision(world, component);
    }

    /**
     * 全船手上一共几张牌。
     *
     * <h2>这个数是正向对照，不是统计</h2>
     * 「牌堆还剩 41 张」出自 {@code Table}，这个数出自 {@code SurvivorState} —— <b>两个来源</b>。
     * 牌离开牌堆却没进任何人手里（比如哪天回合收尾把手牌一起清了），
     * 前一个数照样正确递减，只有这个数会掉回去。跨回合看它涨不涨，就能分出
     * 「发过牌」与「发了但没留下」—— 这两件事在对局推进上完全看不出区别。
     */
    private static int handTotal(Session session) {
        GameState g = session.state();
        return g.bySeat().stream().mapToInt(id -> g.stateOf(id).hand().size()).sum();
    }

    /**
     * 替身直接代选。
     *
     * <p>替身是测试夹具（决策 ②），不该让验收脚本干等 —— 8 个座位各等满 N×2 秒，
     * 一回合就是 70 秒，一局十几回合。真人那一侧照常走倒计时。
     */
    private static void autoPlayIfDummy(ServerWorld world, GameComponent component) {
        Session session = component.requireSession();
        while (session.provisionInProgress()) {
            CharacterId holder = session.provisionHolder().orElseThrow();
            GameComponent.Occupant who = component.occupantOf(holder).orElse(null);
            if (who == null || !who.isDummy()) {
                return;                   // 轮到真人了，交回给倒计时
            }
            keep(world, component, 0);
            if (component.session().isEmpty()) {
                return;                   // keep 里已经走完并清了状态
            }
            session = component.requireSession();
        }
    }

    private static void armDeadline(GameComponent component, int cards) {
        component.setProvisionDeadline(System.currentTimeMillis() + cards * MILLIS_PER_CARD);
        component.setProvisionHighlight(0);
    }

    /** 按收件人裁剪后逐个发。**offer 只进持有者那一包。** */
    private static void broadcast(ServerWorld world, GameComponent component) {
        Session session = component.requireSession();
        Optional<CharacterId> holder = session.provisionHolder();
        if (holder.isEmpty()) {
            broadcastFinished(world);
            return;
        }
        List<String> chain = session.provisionChainIds();
        int at = session.provisionIndex();
        int remaining = session.provisionOffer().size();
        long deadline = component.provisionDeadline();

        for (ServerPlayerEntity player : world.getServer().getPlayerManager().getPlayerList()) {
            boolean isHolder = component.seatOf(player.getUuid())
                    .map(id -> id.equals(holder.get())).orElse(false);
            ServerPlayNetworking.send(player, new ProvisionUpdateS2C(
                    chain, at, remaining, deadline,
                    isHolder ? session.provisionOffer() : List.of()));
        }
    }

    private static void broadcastFinished(ServerWorld world) {
        for (ServerPlayerEntity player : world.getServer().getPlayerManager().getPlayerList()) {
            ServerPlayNetworking.send(player, ProvisionUpdateS2C.finished());
        }
    }
}
