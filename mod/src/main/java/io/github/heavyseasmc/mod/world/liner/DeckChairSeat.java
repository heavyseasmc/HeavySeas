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
 * 甲板躺椅的座位（C3 第二轮 · c3-deck，ADR-0086 §2 第 11 条）：右键躺椅 → 在坐垫上摆一个看不见的座位、人骑上去；起身就收掉。
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

    /** 这把躺椅脚那一格（{@link LinerProp.Part#FOOT}）：躺椅还在不在、人起身落在哪，都从这一格算。 */
    private BlockPos chair = BlockPos.ORIGIN;

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
     * 右键躺椅（{@link LinerProp} 的 {@code onUse}，{@link LinerProp.Use#SIT}）：这个人能坐（{@link LinerProp.Rules#maySit}）、这把没人坐，
     * 就在坐垫上摆一个座位、让他坐上去，身子朝躺椅的脚那一头。
     */
    static ActionResult sit(BlockState state, World world, BlockPos pos, PlayerEntity rawPlayer) {
        if (!(world instanceof ServerWorld sw) || !(rawPlayer instanceof ServerPlayerEntity player)) {
            return ActionResult.SUCCESS;                                           // 客户端：挥一下手，坐不坐由服务端定
        }
        if (!LinerProp.Rules.maySit(player.hasVehicle(), player.isSpectator())) {
            return ActionResult.PASS;
        }
        Direction facing = state.get(LinerProp.FACING);
        BlockPos foot = LinerProp.Rules.offset(pos, state.get(LinerProp.BED_PART), LinerProp.Part.FOOT, facing).toImmutable();
        BlockPos head = LinerProp.Rules.offset(foot, LinerProp.Part.FOOT, LinerProp.Part.HEAD, facing);
        boolean taken = !sw.getEntitiesByType(TYPE, new Box(foot).union(new Box(head)), seat -> seat.chair.equals(foot)).isEmpty();
        if (taken) {
            return ActionResult.FAIL;
        }
        double[] at = LinerProp.Rules.chairSeat(LinerProp.Part.FOOT, facing);
        double x = foot.getX() + at[0];
        double y = foot.getY() + at[1];
        double z = foot.getZ() + at[2];
        DeckChairSeat seat = new DeckChairSeat(TYPE, sw);
        seat.chair = foot;
        seat.refreshPositionAndAngles(x, y, z, facing.asRotation(), 0f);
        if (!sw.spawnEntity(seat)) {
            return ActionResult.FAIL;
        }
        player.teleport(sw, x, y, z, facing.asRotation(), player.getPitch());
        if (!player.startRiding(seat, true)) {
            seat.discard();
            return ActionResult.FAIL;
        }
        LOGGER.info("躺椅：{} 坐下（{}）", player.getGameProfile().getName(), foot.toShortString());
        return ActionResult.SUCCESS;
    }

    /** 这把躺椅还在（脚那一格还是躺椅的脚）。 */
    private boolean chairStands() {
        BlockState s = getWorld().getBlockState(chair);
        return s.getBlock() instanceof LinerProp p && p.spec().kind() == LinerProp.Kind.DECK_CHAIR
                && s.get(LinerProp.BED_PART) == LinerProp.Part.FOOT;
    }

    @Override
    public void tick() {
        super.tick();
        if (!getWorld().isClient && (!hasPassengers() || !chairStands())) {
            removeAllPassengers();
            discard();
        }
    }

    /** 起身落在躺椅边上：先两侧（头那一格、脚那一格的左右）、再脚前、再头后，第一个站得下的空当；都不行照游戏默认（座位顶上）。 */
    @Override
    public Vec3d updatePassengerForDismount(LivingEntity passenger) {
        BlockState s = getWorld().getBlockState(chair);
        if (!chairStands()) {
            return super.updatePassengerForDismount(passenger);
        }
        Direction facing = s.get(LinerProp.FACING);
        BlockPos head = LinerProp.Rules.offset(chair, LinerProp.Part.FOOT, LinerProp.Part.HEAD, facing);
        List<BlockPos> spots = new ArrayList<>();
        for (BlockPos cell : List.of(head, chair)) {
            spots.add(cell.offset(facing.rotateYClockwise()));
            spots.add(cell.offset(facing.rotateYCounterclockwise()));
        }
        spots.add(chair.offset(facing));
        spots.add(head.offset(facing.getOpposite()));
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
    }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
        nbt.putLong("Chair", chair.asLong());
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
