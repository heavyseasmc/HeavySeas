package io.github.heavyseasmc.mod.world.liner;

import io.github.heavyseasmc.mod.HeavySeasMod;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Dismounting;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 家具上的座位（C3 第二轮 · c3-deck 起于甲板躺椅，ADR-0086 §2 第 11 条；ADR-0109 ① 推广到椅子、沙发、凳、床沿……）：
 * 右键一件有座位的家具（{@link LinerProp.Rules#seats}）→ 挑离点中那一点最近、没人坐的那个座位，摆一个看不见的座位实体、人骑上去；起身就收掉。
 * 类名与注册名沿用躺椅那一版（注册名改了，已经存盘的座位就成了未知实体）。
 *
 * <h2>为什么不借对局的座位（{@link io.github.heavyseasmc.mod.world.SeatEntity}）</h2>
 * 那一种服务两件事：对局里的位次（{@code Seats}：组件里登记的座位，名单之外的一律当孤儿清）与北辰号演习艇的报名
 * （{@code DrillSkiff}：「坐在大厅座位上、锚点是演习艇」= 报了名，按 {@code SeatEntity.TYPE} 数人）。躺椅若也是那一种实体，
 * 两边的判法都得多认一种例外 —— 漏一处，坐躺椅就成了报名，或者躺椅的座位被当孤儿清掉。所以这里是另一种实体：
 * 那两边按类型找座位，永远看不见它（{@code DeckPiecesTest} 守住这一条）。
 *
 * <p>做法与那一种相同：没有 AI、不受重力、不会被推、打不着，客户端一个空渲染器。<b>尺寸也相同（0.3 格）</b>：坐上去的人的姿势
 * （乘客挂在座位顶上、玩家的乘坐挂点 0.6 格）是 docs 的 liner_props_opendeck 按这个数量过的（判据 check_java_seat 读 {@link #SIZE}）。
 *
 * <h2>谁也不被拉下来</h2>
 * 正骑着任何东西的人（对局座位、演习艇、船）右键躺椅没有反应（{@link LinerProp.Rules#maySit}）：躺椅从不替人起身 ——
 * 替人起身就是悄悄退了报名、离了位次。
 *
 * <h2>收摊</h2>
 * 每 tick 看一眼：没人坐了、或者躺椅不在了（被拆、被结构换掉）就收掉自己。下线时它跟着人存进玩家的数据（游戏自带的骑乘存法），
 * 上线照样坐着；存进区块里的空座位，载入后第一 tick 就收掉。
 */
public final class DeckChairSeat extends Entity {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    /** 注册名。改它等于让已经存盘的座位变成未知实体 —— 不要改。 */
    public static final Identifier ID = Identifier.of(HeavySeasMod.MOD_ID, "deck_chair_seat");
    /** 座位实体的边长（格）：与对局座位同一个数（docs 的 liner_props_opendeck.SEAT_BOX_H 按它算骑乘姿势，判据读这一行）。 */
    static final float SIZE = 0.3f;
    /** 人坐着时身子朝前、头最多往两边转多少度（同游戏自带的船）：身子一转，两腿就从扶手里穿出去。 */
    private static final float LOOK_AROUND = 105.0f;

    public static final EntityType<DeckChairSeat> TYPE = EntityType.Builder
            .<DeckChairSeat>create(DeckChairSeat::new, SpawnGroup.MISC)
            .dimensions(SIZE, SIZE)
            .maxTrackingRange(10)
            .disableSummon()
            .build(ID.toString());

    /** 座位落在的那一格（座位那一块，{@link LinerProp.Rules.Seat#cell}）：家具还在不在、人起身落在哪，都从这一格算。 */
    private BlockPos chair = BlockPos.ORIGIN;
    /** 这件家具的第几个座位（{@link LinerProp.Rules#seats} 里的下标）：同一格上可以有几个座位（床沿两边）。 */
    private int seatIndex;

    public DeckChairSeat(EntityType<? extends DeckChairSeat> type, World world) {
        super(type, world);
        this.noClip = true;
        this.setNoGravity(true);
        this.setSilent(true);
    }

    /** 注册实体类型。两端都要跑到 —— 客户端那一半只给它一个空渲染器（HeavySeasClient）。 */
    public static void register() {
        Registry.register(Registries.ENTITY_TYPE, ID, TYPE);
    }

    /**
     * 右键一件有座位的家具（{@link LinerProp} 的 {@code onUse}，{@link LinerProp.Use#SIT}）：这个人能坐（{@link LinerProp.Rules#maySit}），
     * 就在这件的座位里挑离点中那一点（{@code hit}）最近、没人坐的那一个，摆一个座位、让他坐上去；都有人坐了就不坐。
     */
    static ActionResult sit(LinerProp block, BlockState state, World world, BlockPos pos, PlayerEntity rawPlayer, Vec3d hit) {
        if (!(world instanceof ServerWorld sw) || !(rawPlayer instanceof ServerPlayerEntity player)) {
            return ActionResult.SUCCESS;                                           // 客户端：挥一下手，坐不坐由服务端定
        }
        if (!LinerProp.Rules.maySit(player.hasVehicle(), player.isSpectator())) {
            return ActionResult.PASS;
        }
        Direction facing = state.get(LinerProp.FACING);
        LinerProp.Part here = block.partOf(state);
        List<LinerProp.Rules.Seat> seats = LinerProp.Rules.seats(block.spec().kind());
        int best = -1;
        double bestD = Double.MAX_VALUE;
        double[] bestAt = null;
        BlockPos bestCell = null;
        for (int i = 0; i < seats.size(); i++) {
            LinerProp.Rules.Seat s = seats.get(i);
            BlockPos cell = (here == null ? pos : LinerProp.Rules.offset(pos, here, s.cell(), facing)).toImmutable();
            int index = i;
            boolean taken = !sw.getEntitiesByType(TYPE, new Box(cell).expand(1.0),
                    seat -> seat.chair.equals(cell) && seat.seatIndex == index).isEmpty();
            if (taken) {
                continue;
            }
            double[] at = LinerProp.Rules.seatAt(pos, here, s, facing);
            double d = hit.squaredDistanceTo(at[0], at[1], at[2]);
            if (d < bestD) {
                best = i;
                bestD = d;
                bestAt = at;
                bestCell = cell;
            }
        }
        if (best < 0) {
            return ActionResult.FAIL;
        }
        float yaw = LinerProp.Rules.seatFacing(seats.get(best), facing).asRotation();
        DeckChairSeat seat = new DeckChairSeat(TYPE, sw);
        seat.chair = bestCell;
        seat.seatIndex = best;
        seat.refreshPositionAndAngles(bestAt[0], bestAt[1], bestAt[2], yaw, 0f);
        if (!sw.spawnEntity(seat)) {
            return ActionResult.FAIL;
        }
        player.teleport(sw, bestAt[0], bestAt[1], bestAt[2], yaw, player.getPitch());
        if (!player.startRiding(seat, true)) {
            seat.discard();
            return ActionResult.FAIL;
        }
        LOGGER.info("座位：{} 坐下（{} 第 {} 个座位，{}）", player.getGameProfile().getName(), block.spec().kind(), best,
                bestCell.toShortString());
        return ActionResult.SUCCESS;
    }

    /** 这件家具还在：座位那一格还是同一种有座位的件、还是座位那一块。 */
    private boolean chairStands() {
        BlockState s = getWorld().getBlockState(chair);
        if (!(s.getBlock() instanceof LinerProp p)) {
            return false;
        }
        List<LinerProp.Rules.Seat> seats = LinerProp.Rules.seats(p.spec().kind());
        if (seatIndex < 0 || seatIndex >= seats.size()) {
            return false;
        }
        LinerProp.Part here = p.partOf(s);
        return here == null || here == seats.get(seatIndex).cell();
    }

    @Override
    public void tick() {
        super.tick();
        if (!getWorld().isClient && (!hasPassengers() || !chairStands())) {
            removeAllPassengers();
            discard();
        }
    }

    /**
     * 起身落在家具边上：坐着的人面前那一格、左右两格、面前斜着的两格、身后，第一个站得下、又不是这件家具自己的空当
     * （躺椅面前是自己的脚那一格，于是照旧先落到两侧）；都不行照游戏默认（座位顶上）。
     */
    @Override
    public Vec3d updatePassengerForDismount(LivingEntity passenger) {
        BlockState s = getWorld().getBlockState(chair);
        if (!chairStands()) {
            return super.updatePassengerForDismount(passenger);
        }
        LinerProp block = (LinerProp) s.getBlock();
        Direction front = LinerProp.Rules.seatFacing(LinerProp.Rules.seats(block.spec().kind()).get(seatIndex), s.get(LinerProp.FACING));
        Direction left = front.rotateYCounterclockwise();
        Direction right = front.rotateYClockwise();
        List<BlockPos> spots = new ArrayList<>(List.of(chair.offset(front), chair.offset(left), chair.offset(right),
                chair.offset(front).offset(left), chair.offset(front).offset(right), chair.offset(front.getOpposite())));
        spots.removeIf(p -> getWorld().getBlockState(p).isOf(block));
        for (BlockPos p : spots) {
            double h = getWorld().getDismountHeight(p);
            if (!Dismounting.canDismountInBlock(h)) {
                continue;
            }
            Vec3d v = new Vec3d(p.getX() + 0.5, p.getY() + h, p.getZ() + 0.5);
            if (Dismounting.canPlaceEntityAt(getWorld(), v, passenger, EntityPose.STANDING)) {
                return v;
            }
        }
        return super.updatePassengerForDismount(passenger);
    }

    /** 坐着的人身子朝躺椅的脚那一头，头最多往两边转 {@link #LOOK_AROUND} 度（同游戏自带的船）。 */
    private void clampPassengerYaw(Entity passenger) {
        passenger.setBodyYaw(getYaw());
        float f = MathHelper.wrapDegrees(passenger.getYaw() - getYaw());
        float g = MathHelper.clamp(f, -LOOK_AROUND, LOOK_AROUND);
        passenger.prevYaw += g - f;
        passenger.setYaw(passenger.getYaw() + g - f);
        passenger.setHeadYaw(passenger.getYaw());
    }

    @Override
    public void onPassengerLookAround(Entity passenger) {
        clampPassengerYaw(passenger);
    }

    @Override
    protected void updatePassengerPosition(Entity passenger, PositionUpdater positionUpdater) {
        super.updatePassengerPosition(passenger, positionUpdater);
        clampPassengerYaw(passenger);
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
    }

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
        chair = BlockPos.fromLong(nbt.getLong("Chair"));
        seatIndex = nbt.getInt("Seat");               // 躺椅那一版没写这一项 → 0；那一版的 Chair 是脚那一格，载入后对不上座位那一块，第一 tick 收掉
    }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
        nbt.putLong("Chair", chair.asLong());
        nbt.putInt("Seat", seatIndex);
    }

    /** 不受伤、不被推、射线打不着（打得着坐着的人）—— 同对局座位。 */
    @Override
    public boolean isInvulnerable() {
        return true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canHit() {
        return false;
    }
}
