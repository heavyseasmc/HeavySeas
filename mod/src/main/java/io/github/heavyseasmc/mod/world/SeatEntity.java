package io.github.heavyseasmc.mod.world;

import io.github.heavyseasmc.mod.HeavySeasMod;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * 一个座位（ADR-0024）。玩家骑在它上面，于是「位次」第一次成了世界里的空间关系（决策 ①）。
 *
 * <h2>它几乎什么都不做，这是故意的</h2>
 * 骑乘是 Minecraft 自带的「人被固定在某处」：位置、朝向、下船、跨维度全归它管。
 * 这里只要一个有坐标、能载人、不动的东西 —— 所以没有 AI、没有物理、不受重力、不会被推。
 *
 * <h2>它不渲染</h2>
 * M4 的船体由一组可移动的 block display 绘制；座位仍只是乘坐和位次投影，
 * 不另画一个重叠模型（客户端那一半在 {@code SeatEntityRenderer}）。
 *
 * <h2>位次的事实源不是它</h2>
 * ❗谁坐第几位由引擎的 {@code GameState#bySeat()} 说了算；世界里的位置是**投影**。
 * 两者不一致时以引擎为准、重新摆一次 —— 与 HUD、四面 GUI 同一条（投影不是状态）。
 *
 * <h2>它会进存档</h2>
 * 实体是要存盘的。对局结束、玩家掉线、服务端重启都得收摊，否则下一局会在一堆旧座位中间开 ——
 * 而「没清干净」与「清干净了」在一局之内长得一模一样（见 ADR-0024 §6）。
 * 所以这里写进 NBT 一个 {@link #GAME_TAG}：起服时照它扫一遍孤儿。
 */
public final class SeatEntity extends Entity {

    /** 注册名。改它等于让已经存盘的座位变成未知实体 —— 不要改。 */
    public static final Identifier ID = Identifier.of(HeavySeasMod.MOD_ID, "seat");

    /**
     * 存盘时给自己盖的戳。起服扫孤儿时认它 —— 不认实体类型，因为**别的模组也可能留下同名实体**，
     * 而且我们只想清自己这一局摆下的那些。
     */
    private static final String GAME_TAG = "heavyseas_seat";

    /** 这个座位是船头数过来第几个（0 起）。只用于日志与排错，规则一侧不读它。 */
    private int index;
    private boolean lobby;
    private BlockPos lobbyAnchor = BlockPos.ORIGIN;

    public static final EntityType<SeatEntity> TYPE = EntityType.Builder
            .<SeatEntity>create(SeatEntity::new, SpawnGroup.MISC)
            // 小一点，但不能是 0：碰撞箱为零的实体在有些路径上会被当成不存在。
            .dimensions(0.3f, 0.3f)
            // ❗必须让客户端一直看得见：默认追踪距离对一个不动的小东西来说够，
            //   但坐在上面的玩家一旦超出范围，客户端那边会以为自己没坐着。
            .maxTrackingRange(16)
            // M4's shore approach moves the entire occupied boat; riders must receive every interpolation step.
            .trackingTickInterval(1)
            .build(ID.toString());

    public SeatEntity(EntityType<? extends SeatEntity> type, World world) {
        super(type, world);
        this.noClip = true;
        this.setNoGravity(true);
        this.setSilent(true);
    }

    /** 注册实体类型。必须在两端都跑到 —— 客户端那一半只是给它一个空渲染器。 */
    public static void register() {
        Registry.register(Registries.ENTITY_TYPE, ID, TYPE);
    }

    public int index() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    /** 这是不是我们自己摆下的座位（起服扫孤儿时问这一句）。 */
    public boolean ours() {
        return getCommandTags().contains(GAME_TAG);
    }

    public void markOurs() {
        addCommandTag(GAME_TAG);
    }

    public boolean lobby() {
        return lobby;
    }

    public BlockPos lobbyAnchor() {
        return lobbyAnchor;
    }

    public void markLobby(BlockPos anchor) {
        markOurs();
        lobby = true;
        lobbyAnchor = anchor.toImmutable();
    }

    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
        // 没有要同步的字段：座位本身看不见，客户端只需要知道它在哪、自己骑没骑着。
    }

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
        index = nbt.getInt("Index");
        lobby = nbt.getBoolean("Lobby");
        if (lobby) {
            lobbyAnchor = BlockPos.fromLong(nbt.getLong("LobbyAnchor"));
        }
    }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
        nbt.putInt("Index", index);
        nbt.putBoolean("Lobby", lobby);
        if (lobby) {
            nbt.putLong("LobbyAnchor", lobbyAnchor.asLong());
        }
    }

    /**
     * ❗不受任何伤害、不被任何东西推动。
     *
     * <p>位次是规则的一部分：一个能被箭射掉、被水推走的座位会让「谁在船头」变成物理事故。
     */
    @Override
    public boolean isInvulnerable() {
        return true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    /** 不参与碰撞：玩家走过来时不该被一个看不见的方块绊住。射线仍然打得到坐在上面的人。 */
    @Override
    public boolean canHit() {
        return false;
    }
}
