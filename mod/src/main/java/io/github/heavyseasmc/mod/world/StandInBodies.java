package io.github.heavyseasmc.mod.world;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.game.GameFlow;
import io.github.heavyseasmc.mod.state.GameComponent;
import net.minecraft.entity.Entity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把替身的人形（{@link StandInEntity}）照名单摆到座位上。与 {@link Seats#refresh} 同一条：<b>投影，不是状态</b> ——
 * 只有「照现在的名单把每个替身放到他该在的地方」，没有「换座位」这个动作。每次刷新都走一遍，便宜且幂等。
 *
 * <ul>
 *   <li>坐在第几位由引擎的 {@code bySeat} 定；换了座位，下一次刷新就挪过去。</li>
 *   <li>落海那几秒（{@link GameComponent#bodyInWater}）人形跟真人一样在水里（{@link #toWater}），回来之后再坐回去。</li>
 *   <li>移出游戏（{@code isRemoved}）的替身收走人形；死了没移出的留在座位上 —— 规则里尸体还在船上。</li>
 * </ul>
 *
 * <p>人形的 UUID 记在这里（按世界分），不进组件：组件要同步、要存，而人形本来就不存档，跨重启只会留下孤儿 id。
 */
public final class StandInBodies {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    /** 世界 → （替身角色 → 它那具人形）。 */
    private static final Map<RegistryKey<World>, Map<CharacterId, UUID>> BODIES = new ConcurrentHashMap<>();

    private StandInBodies() {
    }

    /** 照现在的名单摆一遍。由 {@link Seats#refresh} 在摆完真人之后调。 */
    static void refresh(ServerWorld world, GameComponent component) {
        if (component.session().isEmpty() || component.seatIds().isEmpty()) {
            return;
        }
        var state = component.requireSession().state();
        List<CharacterId> order = state.bySeat();
        Map<CharacterId, UUID> bodies = BODIES.computeIfAbsent(world.getRegistryKey(), k -> new HashMap<>());
        for (int i = 0; i < order.size() && i < component.seatIds().size(); i++) {
            CharacterId id = order.get(i);
            if (!component.occupantOf(id).map(GameComponent.Occupant::isDummy).orElse(false)) {
                continue;
            }
            if (state.isRemoved(id)) {
                discard(world, bodies.remove(id));
                continue;
            }
            if (component.bodyInWater(id)) {
                continue;                     // 在水里那几秒：toWater 已经把它放下去了，回来之后下面那一支再坐回
            }
            SeatEntity seat = Seats.seatAt(world, component, i);
            if (seat == null) {
                continue;                     // 座位没了：下一次刷新再说
            }
            StandInEntity body = bodyOf(world, bodies, id);
            if (body == null) {
                body = new StandInEntity(StandInEntity.TYPE, world);
                body.setCharacter(id.value());
                body.setCustomName(GameFlow.characterName(id));
                body.refreshPositionAndAngles(seat.getX(), seat.getY(), seat.getZ(), seat.getYaw(), 0f);
                if (!world.spawnEntity(body)) {
                    // 正向对照：生不出来时船上就是空座位，与改之前一模一样 —— 不打这一行没人分得出来
                    LOGGER.warn("替身人形生成失败：{}", id.value());
                    continue;
                }
                bodies.put(id, body.getUuid());
                LOGGER.info("替身人形：{} 坐上第 {} 位", id.value(), i + 1);
            }
            if (body.getVehicle() == seat) {
                continue;                     // 已经坐对了 —— 绝大多数帧走到的分支
            }
            body.stopRiding();
            body.refreshPositionAndAngles(seat.getX(), seat.getY(), seat.getZ(), seat.getYaw(), 0f);
            body.startRiding(seat, true);
        }
    }

    /**
     * 有人落海：替身的人形与真人一样被放到船边的水里，几秒之后 {@link #refresh} 让它坐回去。
     * 不放下去的话，全船只有真人在水里扑腾，替身稳坐不动 —— 落海这一幕就读不出来是谁。
     */
    static void toWater(ServerWorld world, CharacterId id, net.minecraft.util.math.Vec3d at, float yaw) {
        Map<CharacterId, UUID> bodies = BODIES.get(world.getRegistryKey());
        StandInEntity body = bodies == null ? null : bodyOf(world, bodies, id);
        if (body != null) {
            body.stopRiding();
            body.refreshPositionAndAngles(at.x, at.y, at.z, yaw, 0f);
        }
    }

    /** 这具实体是不是这一局某个替身的人形；是的话替的是谁。指定模式右键时问它（{@code DesignationPhase}）。 */
    public static Optional<CharacterId> characterOf(ServerWorld world, Entity entity) {
        if (!(entity instanceof StandInEntity)) {
            return Optional.empty();
        }
        Map<CharacterId, UUID> bodies = BODIES.get(world.getRegistryKey());
        if (bodies == null) {
            return Optional.empty();
        }
        return bodies.entrySet().stream().filter(e -> e.getValue().equals(entity.getUuid()))
                .map(Map.Entry::getKey).findFirst();
    }

    /** 收摊：这一局的人形全部清掉（{@link Seats#clear} 调）。 */
    static void clear(ServerWorld world) {
        Map<CharacterId, UUID> bodies = BODIES.remove(world.getRegistryKey());
        if (bodies == null || bodies.isEmpty()) {
            return;
        }
        bodies.values().forEach(uuid -> discard(world, uuid));
        LOGGER.info("替身人形已收摊：{} 具", bodies.size());
    }

    private static StandInEntity bodyOf(ServerWorld world, Map<CharacterId, UUID> bodies, CharacterId id) {
        UUID uuid = bodies.get(id);
        if (uuid == null) {
            return null;
        }
        if (world.getEntity(uuid) instanceof StandInEntity body && body.isAlive()) {
            return body;
        }
        bodies.remove(id);                    // 区块卸载、被清掉了：下一次刷新重新生一具
        return null;
    }

    private static void discard(ServerWorld world, UUID uuid) {
        if (uuid != null && world.getEntity(uuid) instanceof StandInEntity body) {
            body.stopRiding();
            body.discard();
        }
    }
}
