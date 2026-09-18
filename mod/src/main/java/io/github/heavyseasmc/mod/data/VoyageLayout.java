package io.github.heavyseasmc.mod.data;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.World;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 一份航程布局（ADR-0034 §5.5）：这一局在哪个维度、船停哪、朝哪、船体是哪份结构模板、岸从哪个方向来。
 *
 * <h2>代码只认布局，不认坐标</h2>
 * 此前 {@code MistSea} 里写死了船锚点、朝向、岸的方向与强加载走廊（ADR-0034 §1.5 逐处列过）。
 * 换一张非官方地图就要改代码 —— 用户的硬约束之一是「兜住换非官方地图」。现在这些量全从这里取；
 * 官方地图只是 {@code data/heavyseas/voyage/default.json} 这一份数据。
 *
 * <h2>方向约定</h2>
 * yaw 用 Minecraft 的：0 = +Z（南），90 = −X（西）。座位从船头沿 {@link #boatForward} 排开，
 * 乘客面朝 {@link #ridersFacing}（yaw + 180）；岸从 {@link #bearingVector} 那个方向来。
 * 官方布局 yaw 180、bearing 0：船头在 +Z 那一端，人面朝 +Z，岸也在 +Z —— 与 M4 的写死值逐项相同。
 *
 * @param id        自称的 id，形如 {@code heavyseas:default}，与资源路径 {@code voyage/default.json} 对应
 * @param dimension 承载对局的维度；开局时核对它已加载
 * @param boat      船头位置 · 朝向 · 座位间距
 * @param hull      船体结构模板；空表示地图作者自带船体（非官方地图）
 * @param arrival   岸从哪个方向来、布景从多远滑到多远、要不要强加载走廊
 * @param fog       用哪份雾表（{@code data/<ns>/fog/<name>.json}，ADR-0034 §5.1.4）
 * @param backdrops 靠岸时滑到船前的布景（§5.3）；可以为空（非官方地图不要岸）
 */
public record VoyageLayout(Identifier id, Identifier dimension, Boat boat, Optional<Hull> hull, Arrival arrival,
                           Identifier fog, List<Backdrop> backdrops) {

    public VoyageLayout {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(boat, "boat");
        Objects.requireNonNull(hull, "hull");
        Objects.requireNonNull(arrival, "arrival");
        Objects.requireNonNull(fog, "fog");
        backdrops = List.copyOf(backdrops);
    }

    /**
     * 一件布景（ADR-0034 §5.3）：一个 item_display 挂一个模型，靠岸时从 {@code slide_from} 滑到 {@code slide_to}。
     *
     * @param item      挂模型的物品 id（本模组注册的、拿不到的物品）
     * @param offset    相对岸锚点的局部偏移（格）：x 向右、y 向上、z 朝船
     * @param yaw       相对岸向再偏转多少度（模型的北面朝船是 0）
     * @param scale     display 的缩放：模型最多 3 格，靠它放大
     * @param viewRange display 的可见距离系数（× 64 格，再乘玩家的实体距离倍率）
     * @param boxWidth  剔除盒宽（格）：要罩住滑动全程，否则滑到一半被视锥剔除
     * @param boxHeight 剔除盒高（格）
     */
    public record Backdrop(Identifier item, Vec3d offset, float yaw, float scale, float viewRange,
                           float boxWidth, float boxHeight) {

        public Backdrop {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(offset, "offset");
        }
    }

    /**
     * @param bow         船头座位的位置（第 0 个座位的中心，格）
     * @param yaw         船身朝向（度）；座位沿 {@code forward(yaw)} 排开
     * @param seatSpacing 相邻座位的距离（格）
     */
    public record Boat(Vec3d bow, float yaw, double seatSpacing) {

        public Boat {
            Objects.requireNonNull(bow, "bow");
        }
    }

    /**
     * @param structure 结构模板 id（{@code data/<ns>/structure/<name>.nbt}）
     * @param anchor    模板里「船头座位那一格」的局部坐标：放置时它落在 {@code floor(bow)} 上
     * @param restore   对局结束时怎么清：{@link Restore#WATER} 把脚印里的方块清回海水与空气
     */
    public record Hull(Identifier structure, Vec3i anchor, Restore restore) {

        public Hull {
            Objects.requireNonNull(structure, "structure");
            Objects.requireNonNull(anchor, "anchor");
            Objects.requireNonNull(restore, "restore");
        }
    }

    /** 船体清除方式。{@code NONE} 给「船体是地图的一部分」的非官方地图。 */
    public enum Restore {
        WATER("water"),
        NONE("none");

        private final String id;

        Restore(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static Restore fromId(String id) {
            for (Restore value : values()) {
                if (value.id.equals(id)) {
                    return value;
                }
            }
            throw new IllegalArgumentException("没有这种清除方式: " + id);
        }
    }

    /**
     * @param bearing   岸的方向（度，与 yaw 同一约定）；海鸥齐飞也往这边
     * @param slideFrom 布景从船头外多少格开始滑
     * @param slideTo   布景停在船头外多少格
     * @param forceload 对局期间要不要强加载「船尾 → 船头 → 岸」这条走廊
     */
    public record Arrival(float bearing, int slideFrom, int slideTo, boolean forceload) {
    }

    /** 强加载走廊沿岸的方向再多算这么多格，免得布景起滑点正好卡在 chunk 边上。 */
    public static final int CORRIDOR_MARGIN = 16;

    /** 走廊按最多几个座位算：布局不知道这一局几个人，按上限 8 个算一定够。 */
    private static final int MAX_SEATS = 8;

    public RegistryKey<World> dimensionKey() {
        return RegistryKey.of(RegistryKeys.WORLD, dimension);
    }

    /** yaw 指的水平方向的单位向量。yaw=0 是 +Z（南），与 Minecraft 一致。 */
    public static Vec3d forward(float yaw) {
        double rad = Math.toRadians(yaw);
        return new Vec3d(-Math.sin(rad), 0, Math.cos(rad));
    }

    /** 船头 → 船尾的单位向量。 */
    public Vec3d boatForward() {
        return forward(boat.yaw());
    }

    /** 船 → 岸的单位向量。 */
    public Vec3d bearingVector() {
        return forward(arrival.bearing());
    }

    /** 第 {@code index} 个座位的中心（0 = 船头）。 */
    public Vec3d seatAt(int index) {
        return boat.bow().add(boatForward().multiply(index * boat.seatSpacing()));
    }

    /** 坐上去的人面朝的方向：船头那一边。 */
    public float ridersFacing() {
        return MathHelper.wrapDegrees(boat.yaw() + 180f);
    }

    /** 岸锚点：布景停靠时的基准点，船头外 {@code slide_to} 格。 */
    public Vec3d shoreAnchor() {
        return boat.bow().add(bearingVector().multiply(arrival.slideTo()));
    }

    /** 面朝岸时的右手方向。yaw 0 朝 +Z（南），右边是西（−X）= {@code forward(90)}。 */
    public Vec3d bearingRight() {
        return forward(arrival.bearing() + 90f);
    }

    /** 一件布景停靠的位置：锚点 + 局部偏移换算到世界（z 朝船 = 岸向的反方向）。 */
    public Vec3d backdropRest(Backdrop backdrop) {
        return shoreAnchor()
                .add(bearingRight().multiply(backdrop.offset().x))
                .add(0, backdrop.offset().y, 0)
                .add(bearingVector().multiply(-backdrop.offset().z));
    }

    /**
     * 对局期间要强加载的 chunk：船尾 → 船头 → 岸（{@code slide_from + 16} 格）这条折线经过的 chunk，各外扩一圈。
     *
     * <p>走廊随航向变，不再是写死的矩形（M4 写死的是 x −1..1 · z −1..5，只对朝 +Z 的官方布局成立）。
     */
    public Set<ChunkPos> corridor() {
        Set<ChunkPos> chunks = new LinkedHashSet<>();
        Vec3d stern = seatAt(MAX_SEATS - 1);
        Vec3d shore = boat.bow().add(bearingVector().multiply(arrival.slideFrom() + CORRIDOR_MARGIN));
        sample(chunks, stern, boat.bow());
        sample(chunks, boat.bow(), shore);
        Set<ChunkPos> expanded = new LinkedHashSet<>();
        for (ChunkPos chunk : chunks) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    expanded.add(new ChunkPos(chunk.x + dx, chunk.z + dz));
                }
            }
        }
        return expanded;
    }

    private static void sample(Set<ChunkPos> into, Vec3d from, Vec3d to) {
        double length = from.distanceTo(to);
        int steps = Math.max(1, (int) Math.ceil(length / 2.0));
        for (int i = 0; i <= steps; i++) {
            Vec3d at = from.lerp(to, (double) i / steps);
            into.add(new ChunkPos(MathHelper.floor(at.x) >> 4, MathHelper.floor(at.z) >> 4));
        }
    }
}
