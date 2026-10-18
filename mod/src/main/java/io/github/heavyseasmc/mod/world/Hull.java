package io.github.heavyseasmc.mod.world;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.data.VoyageLayout;
import io.github.heavyseasmc.mod.state.GameComponent;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructurePlacementData;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3i;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 船体：一份结构模板，开局按布局放进世界，结束时清回海水（ADR-0034 §5.2，用户 2026-09-19 改为「用方块搭」）。
 *
 * <h2>为什么是真方块</h2>
 * 船整局不动（§5.2 C）之后，船体不再需要是实体：真方块能用游戏自带的全部装饰（桶 · 灯笼 · 钟 · 栅栏），
 * 玩家也能站上去。代价是它会进存档 —— 所以脚印记进组件 NBT，三条退出路径与起服都清（ADR-0024 那一课）。
 *
 * <h2>模板的坐标约定</h2>
 * 模板按官方朝向（yaw 180）作画：船尾在 z=0、船头在 z 最大那一端，也就是<b>船尾 → 船头 = +Z</b>。
 * 布局的 {@code hull.anchor} 是模板里船头座位那一格；放置时它落在 {@code floor(boat.bow)} 上，
 * 其余方块按 {@link #rotationFor} 绕模板原点旋转。旋转公式与 {@code StructureTemplate.transformAround} 是同一个，
 * 单测 {@code HullRotationTest} 对着 {@link VoyageLayout#forward} 核过四个朝向。
 */
public final class Hull {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    /** 船锚点向下最多找这么多格水；再深就不是「停在水面上」。 */
    public static final int WATER_SEARCH_DEPTH = 2;

    private Hull() {
    }

    /**
     * 放好船体。布局没有船体（非官方地图自带）时什么也不做。
     *
     * @return 要记进组件的脚印；对局结束时按它清
     * @throws IllegalStateException 模板不存在 · 锚点下方没有水 · 模板放不下
     */
    public static Optional<GameComponent.HullFootprint> place(ServerWorld world, VoyageLayout layout) {
        VoyageLayout.Hull hull = layout.hull().orElse(null);
        if (hull == null) {
            return Optional.empty();
        }
        StructureTemplate template = world.getStructureTemplateManager().getTemplate(hull.structure())
                .orElseThrow(() -> new IllegalStateException(
                        "布局 %s 的 hull.structure：结构模板 %s 不存在（data/%s/structure/%s.nbt）".formatted(
                                layout.id(), hull.structure(), hull.structure().getNamespace(), hull.structure().getPath())));
        BlockPos bow = BlockPos.ofFloored(layout.boat().bow());
        int waterTop = waterTopUnder(world, layout, bow);
        BlockRotation rotation = rotationFor(layout.boat().yaw());
        BlockPos origin = bow.subtract(rotate(hull.anchor(), rotation));
        StructurePlacementData placement = new StructurePlacementData()
                .setRotation(rotation)
                .setMirror(BlockMirror.NONE)
                .setPosition(BlockPos.ORIGIN)
                .setIgnoreEntities(true);
        BlockBox box = template.calculateBoundingBox(placement, origin);
        if (!template.place(world, origin, origin, placement, world.getRandom(), Block.NOTIFY_LISTENERS)) {
            throw new IllegalStateException("船体放置失败：结构模板 %s 是空的".formatted(hull.structure()));
        }
        // 与语言无关的一行：验收靠它判「船体真的放出来了」。
        LOGGER.info("船体已放好：{} · 旋转 {} · 范围 ({} {} {})..({} {} {}) · 水面 y={} · 布局 {}",
                hull.structure(), rotation, box.getMinX(), box.getMinY(), box.getMinZ(),
                box.getMaxX(), box.getMaxY(), box.getMaxZ(), waterTop, layout.id());
        return Optional.of(new GameComponent.HullFootprint(box, waterTop,
                hull.restore() == VoyageLayout.Restore.WATER));
    }

    /**
     * 把脚印清回海水与空气。{@code restoreWater} 为假（地图自带船体）时什么也不动。
     *
     * <p>❗<b>从上往下清。</b>钟与灯笼靠脚下那格支撑：先把甲板清成空气，它们就按「失去支撑」自己碎掉，
     * 而那条连锁走的是 {@code breakBlock(pos, drop=true)} —— 每局结束往海里丢 1 口钟 + 4 盏灯笼，
     * 船头那位还会顺手把钟捡进背包（2026-09-19 换地图演练实测：两个维度里躺着 29 个掉落物）。
     * 上层先成空气，再清下层，就没有任何方块需要「失去支撑」。
     */
    public static void restore(ServerWorld world, GameComponent.HullFootprint footprint) {
        if (!footprint.restoreWater()) {
            return;
        }
        BlockBox box = footprint.box();
        int cleared = 0;
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int y = box.getMaxY(); y >= box.getMinY(); y--) {
            for (int z = box.getMinZ(); z <= box.getMaxZ(); z++) {
                for (int x = box.getMinX(); x <= box.getMaxX(); x++) {
                    world.setBlockState(pos.set(x, y, z), y <= footprint.waterTop()
                            ? Blocks.WATER.getDefaultState() : Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
                    cleared++;
                }
            }
        }
        LOGGER.info("船体已清回海水：{} 格", cleared);
    }

    /**
     * 船朝向 → 模板旋转。模板的「船尾 → 船头」是 +Z；yaw 180 时世界里的船尾 → 船头也是 +Z（{@code -forward(180)}），所以不转。
     * 其余三个正方向各转一次；不是 90 的倍数的朝向在加载期就被拒绝了。
     */
    public static BlockRotation rotationFor(float yaw) {
        int normalized = Math.floorMod(Math.round(yaw), 360);
        return switch (normalized) {
            case 180 -> BlockRotation.NONE;
            case 90 -> BlockRotation.COUNTERCLOCKWISE_90;
            case 0 -> BlockRotation.CLOCKWISE_180;
            case 270 -> BlockRotation.CLOCKWISE_90;
            default -> throw new IllegalArgumentException("船体只能朝正东南西北，yaw=" + yaw);
        };
    }

    /** 绕模板原点旋转一个局部坐标。与 {@code StructureTemplate.transformAround(pos, NONE, rotation, ORIGIN)} 同一公式。 */
    public static BlockPos rotate(Vec3i local, BlockRotation rotation) {
        int x = local.getX();
        int y = local.getY();
        int z = local.getZ();
        return switch (rotation) {
            case COUNTERCLOCKWISE_90 -> new BlockPos(z, y, -x);
            case CLOCKWISE_90 -> new BlockPos(-z, y, x);
            case CLOCKWISE_180 -> new BlockPos(-x, y, -z);
            default -> new BlockPos(x, y, z);
        };
    }

    /** 船锚点脚下的水面高度：锚点那一格起向下 {@value #WATER_SEARCH_DEPTH} 格内第一格水的 y。 */
    static int waterTopUnder(ServerWorld world, VoyageLayout layout, BlockPos bow) {
        for (int dy = 0; dy <= WATER_SEARCH_DEPTH; dy++) {
            BlockPos at = bow.down(dy);
            if (world.getFluidState(at).isIn(FluidTags.WATER)) {
                return at.getY();
            }
        }
        throw new IllegalStateException("布局 %s 的 boat.bow：锚点 (%s, %s, %s) 下方 %d 格内没有水 —— 船不在水面上".formatted(
                layout.id(), fmt(layout.boat().bow().x), fmt(layout.boat().bow().y), fmt(layout.boat().bow().z),
                WATER_SEARCH_DEPTH));
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", MathHelper.floor(v * 10) / 10.0);
    }
}
