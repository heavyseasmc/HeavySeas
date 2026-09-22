package io.github.heavyseasmc.mod.world;

import io.github.heavyseasmc.mod.HeavySeasMod;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** Selectable, non-colliding hull. Vanilla reach checks use this box, not the distant anchor center. */
public final class LobbyBoatEntity extends Entity {

    public static final Identifier ID = Identifier.of(HeavySeasMod.MOD_ID, "lobby_boat");
    public static final EntityType<LobbyBoatEntity> TYPE = EntityType.Builder
            .<LobbyBoatEntity>create(LobbyBoatEntity::new, SpawnGroup.MISC)
            .dimensions((float) LobbyBoatGeometry.LENGTH, (float) LobbyBoatGeometry.HEIGHT)
            .maxTrackingRange(12)
            .trackingTickInterval(1)
            .disableSaving()
            .build(ID.toString());

    public LobbyBoatEntity(EntityType<? extends LobbyBoatEntity> type, World world) {
        super(type, world);
        noClip = true;
        setNoGravity(true);
        setSilent(true);
    }

    public static void register() {
        Registry.register(Registries.ENTITY_TYPE, ID, TYPE);
    }

    public BlockPos anchor() {
        return getBlockPos();
    }

    public void place(BlockPos anchor, Direction facing) {
        refreshPositionAndAngles(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5,
                facing.asRotation(), 0f);
        setBoundingBox(calculateBoundingBox());
    }

    @Override
    protected Box calculateBoundingBox() {
        return LobbyBoatGeometry.bounds(BlockPos.ofFloored(getX(), getY(), getZ()),
                Direction.fromRotation(getYaw()));
    }

    @Override
    public void tick() {
        super.tick();
        BlockState state = getWorld().getBlockState(anchor());
        if (!getWorld().isClient && !state.isOf(LobbyBoatBlock.BLOCK)) {
            discard();
            return;
        }
        setBoundingBox(calculateBoundingBox());
    }

    @Override
    public ActionResult interact(PlayerEntity player, Hand hand) {
        if (getWorld().isClient) {
            return ActionResult.SUCCESS;
        }
        BlockState state = getWorld().getBlockState(anchor());
        return state.isOf(LobbyBoatBlock.BLOCK)
                ? LobbyBoat.use(getWorld(), anchor(), state, player) : ActionResult.PASS;
    }

    @Override
    public ActionResult interactAt(PlayerEntity player, Vec3d hitPos, Hand hand) {
        return interact(player, hand);
    }

    @Override
    public boolean handleAttack(Entity attacker) {
        if (attacker instanceof ServerPlayerEntity player && !player.isSpectator()
                && player.canModifyAt(getWorld(), anchor())
                && getWorld().getBlockState(anchor()).isOf(LobbyBoatBlock.BLOCK)) {
            // The regular block-breaking path preserves survival drops and adventure restrictions.
            player.interactionManager.tryBreakBlock(anchor());
        }
        return true;
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
    }

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
    }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
    }

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
        return isAlive();
    }
}
