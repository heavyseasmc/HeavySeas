package io.github.heavyseasmc.mod.world;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.state.GameComponent;
import io.github.heavyseasmc.mod.state.GameComponents;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 把「位次」摆进世界（ADR-0024）。船头到船尾一排座位，人真的坐在上面。
 *
 * <h2>世界里的位置是投影，不是事实源</h2>
 * 谁坐第几位永远由引擎的 {@code GameState#bySeat()} 说了算。所以这里没有「换座位」这个动作 ——
 * 只有 {@link #refresh}：<b>照现在的名单把每个人放到他该在的地方</b>。换座位、被海水带走、
 * 掉线重连，全都只是「名单变了，再摆一次」。
 *
 * <p>❗这与 HUD 和四面 GUI 是同一条：投影不是状态。专门写一个 swap 动作的话，
 * 就会多出一条「世界与引擎各自记了一份位次」的路，而两份迟早分家 ——
 * 分家的表现是屏幕上两个人换了、规则里没换，反过来也一样，而且都不报错。
 *
 * <h2>座位不跨重启</h2>
 * 对局本身就不持久化（存档时服务端会打一行「有一局进行到第 N 回合，不会被保存」），
 * 所以<b>起服时看到的每一个座位都是孤儿</b>，{@link #sweep} 一律清掉 —— 不必去分辨哪些还有用。
 */
public final class Seats {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    /** 船头到船尾，相邻两个座位隔多远（格）。一格：坐满八个人正好是一条小艇的长度。 */
    private static final double SPACING = 1.0;

    /** 座位比脚下的地面略高一点：贴地放的话，骑上去的人会有半个身子陷进方块里。 */
    private static final double LIFT = 0.35;

    private Seats() {
    }

    /**
     * 开局摆船：从 {@code origin} 起，沿 {@code yaw} 指的方向排开 {@code count} 个座位。
     *
     * <p>第 0 个是**船头**，也就是调用者脚下那一格；船往他面朝的方向延伸出去。
     * 坐上去的人一律面朝船头 —— 看的是同一个方向，谁在前谁在后一眼就知道。
     *
     * <p>❗摆之前先收摊：`/seas start` 连开两局时，上一局的座位还在世界里。
     */
    public static void place(ServerWorld world, GameComponent component, Vec3d origin, float yaw, int count) {
        clear(world, component);
        Vec3d forward = forward(yaw);
        float facing = MathHelper.wrapDegrees(yaw + 180f);   // 面朝船头
        // ❗**先把名单写进组件，再让实体进世界**。顺序反了的话，实体一进世界就触发
        //   ENTITY_LOAD，而那一刻组件里还没有它 —— 清孤儿的那一手会把刚摆好的座位当场清掉。
        List<SeatEntity> seats = new ArrayList<>();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Vec3d at = origin.add(forward.multiply(i * SPACING)).add(0, LIFT, 0);
            SeatEntity seat = new SeatEntity(SeatEntity.TYPE, world);
            seat.setIndex(i);
            seat.markOurs();
            seat.refreshPositionAndAngles(at.x, at.y, at.z, facing, 0f);
            seats.add(seat);
            ids.add(seat.getUuid());
        }
        component.setSeatIds(ids);
        ids = new ArrayList<>();
        for (SeatEntity seat : seats) {
            if (!world.spawnEntity(seat)) {
                // 正向对照：生不出来时下面的「坐上了第几位」会全部缺席，而对局照样能打完 ——
                // 不打这一行的话，差别只有「屏幕上没有船」，没人会知道是哪一步没成。
                LOGGER.warn("座位生成失败：第 {} 个", seat.index());
                continue;
            }
            ids.add(seat.getUuid());
        }
        component.setSeatIds(ids);
        // 与语言无关的一行：验收靠它判「船真的摆出来了」。
        LOGGER.info("座位已摆好：{} 个 · 船头 {} {} {} · 朝向 {}",
                ids.size(), fmt(origin.x), fmt(origin.y), fmt(origin.z), Math.round(facing));
        refresh(world, component);
    }

    /**
     * 照现在的名单，把每个真人放到他该坐的位置上。
     *
     * <p>每次投影更新都会走到这里（{@link GameComponents#sync}），所以它必须**便宜且幂等**：
     * 已经坐对了的人一个字节都不动。
     */
    public static void refresh(ServerWorld world, GameComponent component) {
        if (component.session().isEmpty() || component.seatIds().isEmpty()) {
            return;
        }
        List<CharacterId> order = component.requireSession().state().bySeat();
        for (int i = 0; i < order.size() && i < component.seatIds().size(); i++) {
            Optional<GameComponent.Occupant> who = component.occupantOf(order.get(i));
            if (who.isEmpty() || who.get().isDummy()) {
                continue;                     // 替身不需要身体（ADR-0024 §7.6）：那个座位就空着
            }
            ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(who.get().player());
            SeatEntity seat = seatAt(world, component, i);
            if (player == null || seat == null) {
                continue;                     // 人不在线 / 座位没了：下一次刷新再说，不在这里补救
            }
            if (player.getVehicle() == seat) {
                continue;                     // 已经坐对了 —— 这是绝大多数帧走到的分支
            }
            player.stopRiding();
            player.teleport(world, seat.getX(), seat.getY(), seat.getZ(), seat.getYaw(), 0f);
            if (player.startRiding(seat, true)) {
                LOGGER.info("座位：{} 坐上第 {} 位（{}）", player.getGameProfile().getName(),
                        i + 1, order.get(i).value());
            }
        }
    }

    /** 收摊：把这一局摆下的座位全部清掉，坐着的人自然落地。 */
    public static void clear(ServerWorld world, GameComponent component) {
        int gone = 0;
        for (UUID id : component.seatIds()) {
            Entity entity = world.getEntity(id);
            if (entity instanceof SeatEntity seat) {
                seat.removeAllPassengers();
                seat.discard();
                gone++;
            }
        }
        component.setSeatIds(List.of());
        if (gone > 0) {
            LOGGER.info("座位已收摊：清掉 {} 个", gone);
        }
    }

    /**
     * 一个座位刚进世界（新摆的，或者从存档里载出来的）。不属于当前这一局的，一律清掉。
     *
     * <p>对局不持久化，所以**存档里留下的每一个座位都是上一次没收干净的**。
     *
     * <h2>为什么认这个事件，而不是起服时扫一遍</h2>
     * ❗第一版就是起服扫：`SERVER_STARTED` 里 {@code iterateEntities()} 走一遍，打一行「清掉 0 个」。
     * <b>实测它永远是 0</b> —— 那一刻只有出生区的区块加载了，孤儿还躺在存档里没进世界。
     * 造了 8 个真孤儿重启，报的还是 0，而世界里明明有 7 个。
     * 实体加载事件是在它<b>真的进世界那一刻</b>触发的：起服、区块重载、玩家走过去，全都算。
     */
    public static void onSeatLoaded(Entity entity, ServerWorld world) {
        if (!(entity instanceof SeatEntity seat) || !seat.ours()) {
            return;
        }
        if (GameComponents.of(world).seatIds().contains(seat.getUuid())) {
            return;                           // 这一局自己摆的，留着
        }
        seat.removeAllPassengers();
        seat.discard();
        // 与语言无关的一行：验收靠它判「孤儿真的被清了」。一个一行 —— 孤儿本来就该是罕见的。
        LOGGER.info("座位：清掉一个孤儿（存档里留下的）");
    }

    private static SeatEntity seatAt(ServerWorld world, GameComponent component, int index) {
        List<UUID> ids = component.seatIds();
        if (index < 0 || index >= ids.size()) {
            return null;
        }
        return world.getEntity(ids.get(index)) instanceof SeatEntity seat ? seat : null;
    }

    /** yaw 指的水平方向的单位向量。yaw=0 是 +Z（南），与 Minecraft 一致。 */
    private static Vec3d forward(float yaw) {
        double rad = Math.toRadians(yaw);
        return new Vec3d(-Math.sin(rad), 0, Math.cos(rad));
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
