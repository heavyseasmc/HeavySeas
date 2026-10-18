package io.github.heavyseasmc.mod.world;

import io.github.heavyseasmc.mod.HeavySeasMod;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Arm;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import java.util.List;

/**
 * 替身的人形（用户 2026-10-07 定「座位上坐一个人形」）：坐在替身那个座位上、头顶写角色名，右键它就是指定它。
 *
 * <h2>为什么要它</h2>
 * ADR-0024 §7.6 当初定「替身不需要身体，那个座位就空着」—— 那时替身只用来凑人数。可指定模式（抢夺 · 换座位）
 * 要在世界里对着人右键（决策 ⑦），而空座位点不中（{@link SeatEntity#canHit} 为 false），
 * 于是演示局里<b>一个人都点不到</b>（用户 2026-10-07：「抢夺也不能抢人不知道为什么」）。顺带：船上看得出坐了几个人，
 * 换了座位也看得出谁挪了。
 *
 * <h2>它只是投影</h2>
 * 与座位、名牌同一条：谁是谁、坐第几位全由引擎说了算，{@link StandInBodies#refresh} 照名单把它摆过去。
 * 它<b>不进存档</b>（{@code disableSaving}）：对局本身不持久化，人形跨重启留下来只会是孤儿。
 *
 * <p>不受伤、推不动、不带东西。模样是 Minecraft 自带的那几款默认皮肤（按角色固定挑一款，客户端那一侧定），
 * 不画新贴图。
 */
public final class StandInEntity extends LivingEntity {

    public static final Identifier ID = Identifier.of(HeavySeasMod.MOD_ID, "stand_in");

    /** 它替的是哪个角色（引擎的角色 id）。客户端按它挑皮肤。 */
    private static final TrackedData<String> CHARACTER =
            DataTracker.registerData(StandInEntity.class, TrackedDataHandlerRegistry.STRING);
    private static final TrackedData<Boolean> DESIGNATING =
            DataTracker.registerData(StandInEntity.class, TrackedDataHandlerRegistry.BOOLEAN);

    public static final EntityType<StandInEntity> TYPE = EntityType.Builder
            .<StandInEntity>create(StandInEntity::new, SpawnGroup.MISC)
            // 与玩家同一副尺寸、同一个坐姿挂点：坐在座位上与真人一般高，右键也是同一个靶子
            .dimensions(PlayerEntity.STANDING_DIMENSIONS.width(), PlayerEntity.STANDING_DIMENSIONS.height())
            .eyeHeight(1.62f)
            .vehicleAttachment(PlayerEntity.VEHICLE_ATTACHMENT_POS)
            .maxTrackingRange(16)
            .trackingTickInterval(1)
            .disableSaving()
            .build(ID.toString());

    public StandInEntity(EntityType<? extends StandInEntity> type, World world) {
        super(type, world);
        setInvulnerable(true);
        setSilent(true);
        setCustomNameVisible(true);
    }

    public static void register() {
        Registry.register(Registries.ENTITY_TYPE, ID, TYPE);
        FabricDefaultAttributeRegistry.register(TYPE, LivingEntity.createLivingAttributes());
    }

    public String character() {
        return dataTracker.get(CHARACTER);
    }

    public void setCharacter(String id) {
        dataTracker.set(CHARACTER, id);
    }

    public boolean isDesignating() {
        return dataTracker.get(DESIGNATING);
    }

    public void setDesignating(boolean raised) {
        dataTracker.set(DESIGNATING, raised);
        setGlowing(raised);
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        super.initDataTracker(builder);
        builder.add(CHARACTER, "");
        builder.add(DESIGNATING, false);
    }

    @Override
    public void tick() {
        super.tick();
        // 脸朝座位朝的那一边（船头）：坐着的人看的是同一个方向，与真人一致
        if (getVehicle() != null) {
            float yaw = getVehicle().getYaw();
            setYaw(yaw);
            setBodyYaw(yaw);
            setHeadYaw(yaw);
        }
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        setCharacter(nbt.getString("Character"));
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        nbt.putString("Character", character());
    }

    @Override
    public boolean isInvulnerableTo(DamageSource source) {
        return true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean isCollidable() {
        return false;
    }

    @Override
    public Iterable<ItemStack> getArmorItems() {
        return List.of();
    }

    @Override
    public ItemStack getEquippedStack(EquipmentSlot slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public void equipStack(EquipmentSlot slot, ItemStack stack) {
    }

    @Override
    public Arm getMainArm() {
        return Arm.RIGHT;
    }
}
