package io.github.heavyseasmc.mod.world;

import io.github.heavyseasmc.mod.HeavySeasMod;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.passive.ParrotEntity;
import net.minecraft.item.Item;
import net.minecraft.item.SpawnEggItem;
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

    /**
     * 刷怪蛋（ADR-0058 §8 Q6，进「物件与功能」那一页）：纸色底、墨色斑。孵出来的是一只「野」海鸥 ——
     * 不归对局的鸥群管，就在孵出来的地方上空盘旋，存档时跟着存（鸥群里的不存，按规则状态重建）。
     */
    public static final SpawnEggItem SPAWN_EGG = new SpawnEggItem(TYPE, 0xECE5D4, 0x433D37, new Item.Settings());

    private int slot;
    private Vec3d center = Vec3d.ZERO;
    private boolean departing;
    /** 对局的鸥群给过位置（{@link #configure}）；否则是刷怪蛋孵的野海鸥。 */
    private boolean flock;
    /** 野海鸥的盘旋中心定下来没有：孵出来的第一刻定在脚下往下 4.4 格，于是就在孵出来的高度上绕圈。 */
    private boolean anchored;
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
        Registry.register(Registries.ITEM, Identifier.of(HeavySeasMod.MOD_ID, "gull_spawn_egg"), SPAWN_EGG);
    }

    public void configure(int slot, Vec3d center) {
        this.slot = slot;
        this.center = center;
        this.flock = true;
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
        if (!flock && !anchored) {
            center = getPos().subtract(0, 4.4, 0);   // ❗不定的话中心是原点：野海鸥会一路飞向世界原点
            anchored = true;
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
        return !flock; // the flock is a projection and is reconstructed from the rule state; egg-born gulls persist
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        if (!flock && anchored) {
            nbt.putDouble("HeavySeasCenterX", center.x);
            nbt.putDouble("HeavySeasCenterY", center.y);
            nbt.putDouble("HeavySeasCenterZ", center.z);
        }
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.contains("HeavySeasCenterY")) {
            center = new Vec3d(nbt.getDouble("HeavySeasCenterX"), nbt.getDouble("HeavySeasCenterY"),
                    nbt.getDouble("HeavySeasCenterZ"));
            anchored = true;
        }
    }
}
