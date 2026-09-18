package io.github.heavyseasmc.mod.world;

import io.github.heavyseasmc.mod.HeavySeasMod;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.passive.ParrotEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** A server-driven sky bird. It reuses Minecraft's bird model but is a distinct heavyseas:gull entity. */
public final class GullEntity extends ParrotEntity {

    public static final Identifier ID = Identifier.of(HeavySeasMod.MOD_ID, "gull");
    public static final EntityType<GullEntity> TYPE = EntityType.Builder
            .create(GullEntity::new, SpawnGroup.CREATURE)
            .dimensions(0.5f, 0.9f)
            .maxTrackingRange(48)
            .trackingTickInterval(1)
            .build(ID.toString());

    private int slot;
    private Vec3d center = Vec3d.ZERO;
    private boolean departing;
    /** 齐飞的方向：布局里岸的方向（ADR-0034 §5.5），由 {@link #depart(Vec3d)} 给。 */
    private Vec3d departToward = new Vec3d(0, 0, 1);

    public GullEntity(EntityType<? extends ParrotEntity> type, World world) {
        super(type, world);
        setNoGravity(true);
        setInvulnerable(true);
        setAiDisabled(true);
        setPersistent();
        setVariant(Variant.GRAY);
    }

    public static void register() {
        Registry.register(Registries.ENTITY_TYPE, ID, TYPE);
        FabricDefaultAttributeRegistry.register(TYPE, ParrotEntity.createParrotAttributes());
    }

    public void configure(int slot, Vec3d center) {
        this.slot = slot;
        this.center = center;
        double angle = slot * Math.PI / 2.0;
        setPosition(center.add(Math.cos(angle) * 14.0, 5.0, Math.sin(angle) * 14.0));
    }

    public void depart(Vec3d toward) {
        departing = true;
        departToward = toward;
    }

    @Override
    public void tick() {
        super.tick();
        if (getWorld().isClient) {
            return;
        }
        if (departing) {
            Vec3d next = getPos().add(departToward.multiply(0.45)).add(0, 0.08, 0);
            setPosition(next);
            setYaw((float) Math.toDegrees(Math.atan2(-departToward.x, departToward.z)));
            return;
        }
        double t = (age * 0.045) + slot * (Math.PI * 2.0 / 4.0);
        double radius = 5.0 + slot * 0.55;
        Vec3d target = center.add(Math.cos(t) * radius, 4.4 + Math.sin(t * 2.0) * 0.6,
                Math.sin(t) * radius);
        // Ease in from the horizon instead of appearing at the orbit position in one frame.
        Vec3d next = getPos().lerp(target, age < 50 ? 0.09 : 0.35);
        setPosition(next);
        setYaw((float) Math.toDegrees(Math.atan2(-(target.x - getX()), target.z - getZ())));
    }

    @Override
    public boolean shouldSave() {
        return false; // the flock is a projection and is reconstructed from the rule state
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
    }
}
