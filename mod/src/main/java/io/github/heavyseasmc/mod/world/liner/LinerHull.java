package io.github.heavyseasmc.mod.world.liner;

import io.github.heavyseasmc.mod.HeavySeasMod;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static io.github.heavyseasmc.mod.world.liner.LinerLooks.tex;

/**
 * 北辰号的船壳板与舷窗（ADR-0069 §2 第 ① 批的舷窗 · 第 ④ 批的船壳）。模板与贴图由 {@code docs/tools/scene/liner_hull.py --write}
 * 画好入库（{@code template/hull/} · {@code textures/block/liner/hull/}）。
 *
 * <ul>
 *   <li><b>船壳板</b>（黑 · 白 · 红 · 水线那一道 · 黄褐；烟囱与桅杆另有不画竖缝的烟囱板 {@link FunnelPlate}）：整块、不透明、没有朝向 —— 六个面都是船壳，船头船尾斜着收、甲板边转角都不用分件。
 *       板列<b>按世界坐标</b>算（同地毯的花纹格）：横缝每 2 格一道、竖缝每 4 / 6 格一道、上下两列错开；放下时与邻居变了时都按位置重算
 *       {@link #CELL}（规则在 {@link Rules}，单测 {@code LinerHullRulesTest}；{@code liner_hull.py} 有同一套规则的 Python 抄本）。</li>
 *   <li><b>舷窗</b>（黑 · 白船壳各一款）：一块船壳板带一个圆窗 —— 黄铜窗圈前出、玻璃后退 3 像素（前 1 像素黄铜唇、后 2 像素灰黑孔套）；正面朝外（{@link #FACING}），
 *       正面的板缝跟着 {@link #CELL} 走，与左右的船壳板接得上。<b>夜里亮</b>靠的是光：舷窗只从屋里那一面进光（{@link Porthole}），
 *       玻璃与窗洞内壁取这一格自己的光 —— 客舱的灯亮着，舷窗就亮；灯关了就暗；船壳外面不会被照出一圈光晕。</li>
 *   <li><b>舷窗里面那一面</b>（室内白漆墙板 · 白漆外墙两款）：挂在船壳舷窗正里面那一格，黄铜圈 + 玻璃后退 2.5 像素（黄铜唇 1 + 孔套 1.5），与船壳上那一块对齐。</li>
 *   <li><b>拼大</b>（ADR-0093 B12 · 第 5 批 5b）：同一种舷窗、同一个朝向摆成完整的 2 × 2 · 3 × 3 · 4 × 4，自动拼成一个大圆窗（窗洞直径
 *       占整块的八分之五：20 · 30 · 40 像素，黄铜圈宽 2）—— 每格记着自己是哪一组的第几片（{@link #MERGE}），模型是整块画好再按格切的。
 *       放下、拆掉、装修锤潜行右键锁住 / 解锁时整片重拼（{@link #retile}）；游戏放结构时原样写进去、不重拼。</li>
 * </ul>
 *
 * <p>模板一律<b>正面朝南</b>作画（与 {@link LinerBlock} 同一个约定：y 旋转 南 0 · 西 90 · 北 180 · 东 270）。通用件、只在创造模式里拿：
 * 挖不动、不掉东西、活塞推不动（与大邮轮那一族相同）。
 */
public final class LinerHull {

    /** 板列：这一格在第几种「横缝 · 竖缝」组合里（0–17，{@link Rules#cell}）。 */
    public static final IntProperty CELL = IntProperty.of("cell", 0, Rules.CELLS - 1);
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    /** 烟囱板：一列板里的下面那一格（0）还是上面那一格（1），按 y 算（{@link Rules#row}）。 */
    public static final IntProperty ROW = IntProperty.of("row", 0, 1);
    /** 舷窗拼大：单格 · 锁成单格 · 拼大那一片（{@link Merge}）。 */
    public static final EnumProperty<Merge> MERGE = EnumProperty.of("merge", Merge.class);
    /** 一次重拼最多认这么多格（一整面墙的舷窗也够；再多的那一截照旧，不至于一次拖住服务端）。 */
    static final int MAX_REGION = 256;

    private static final Map<String, Block> BLOCKS = new LinkedHashMap<>();

    // ---------------------------------------------------------------- 船壳板：水线以下红 · 水线那一道 · 黑 · B 甲板往上白
    public static final HullPlate HULL_BLACK = hull("liner_hull_black", new HullPlate(steel(MapColor.BLACK), "black", "black"));
    public static final HullPlate HULL_WHITE = hull("liner_hull_white", new HullPlate(steel(MapColor.OFF_WHITE), "white", "white"));
    public static final HullPlate HULL_RED = hull("liner_hull_red", new HullPlate(steel(MapColor.DARK_RED), "red", "red"));
    /** 水线那一道：红板的上沿一条窄白带（顶 / 底两面照红板）。 */
    public static final HullPlate HULL_WATERLINE = hull("liner_hull_waterline", new HullPlate(steel(MapColor.DARK_RED), "waterline", "red"));
    /** 黄褐（1912 年大邮轮烟囱与桅杆的那种赭黄）：同一套板列与铆钉、同一个 cell 规则。平的地方用（桅屋、通风筒座……）。 */
    public static final HullPlate HULL_BUFF = hull("liner_hull_buff", new HullPlate(steel(MapColor.TERRACOTTA_YELLOW), "buff", "buff"));

    // ---------------------------------------------------------------- 烟囱板：只有一圈圈横缝与铆钉箍，不画竖缝（{@link FunnelPlate}）
    public static final FunnelPlate FUNNEL_BUFF = hull("liner_funnel_buff", new FunnelPlate(steel(MapColor.TERRACOTTA_YELLOW), "buff"));
    public static final FunnelPlate FUNNEL_BLACK = hull("liner_funnel_black", new FunnelPlate(steel(MapColor.BLACK), "black"));

    // ---------------------------------------------------------------- 舷窗：船壳上那一块 · 屋里那一块
    public static final Porthole PORTHOLE_BLACK = hull("liner_porthole_black", new Porthole(steel(MapColor.BLACK), "black"));
    public static final Porthole PORTHOLE_WHITE = hull("liner_porthole_white", new Porthole(steel(MapColor.OFF_WHITE), "white"));
    /** 屋里那一面 · 室内白漆墙板（正面同 {@code liner_wall}）：护墙板墙的屋里用。 */
    public static final InnerPorthole PORTHOLE_INNER = hull("liner_porthole_inner",
            new InnerPorthole(steel(MapColor.OFF_WHITE).nonOpaque(), "wall"));
    /** 屋里那一面 · 白漆外墙（正面同 {@code liner_wall_white}）：客房、办公室这种白墙小屋用。 */
    public static final InnerPorthole PORTHOLE_INNER_PLAIN = hull("liner_porthole_inner_plain",
            new InnerPorthole(steel(MapColor.OFF_WHITE).nonOpaque(), "wall_white"));

    private LinerHull() {
    }

    /** 方块与物品一起登记；物品只在创造模式里拿得到。 */
    public static void register() {
        BLOCKS.forEach((name, block) -> {
            Identifier id = Identifier.of(HeavySeasMod.MOD_ID, name);
            Registry.register(Registries.BLOCK, id, block);
            Registry.register(Registries.ITEM, id, new BlockItem(block, new Item.Settings()));
        });
    }

    /** 全部，按登记顺序（批量生成工具、物品栏、客户端渲染层与判据用）。 */
    public static Map<String, Block> all() {
        return Collections.unmodifiableMap(BLOCKS);
    }

    private static <B extends Block> B hull(String name, B block) {
        BLOCKS.put(name, block);
        return block;
    }

    /** 挖不动、不掉东西、活塞推不动；钢板的声音。 */
    private static AbstractBlock.Settings steel(MapColor color) {
        return AbstractBlock.Settings.create().mapColor(color).strength(-1.0f, 3_600_000.0f).dropsNothing()
                .pistonBehavior(PistonBehavior.BLOCK).sounds(BlockSoundGroup.METAL);
    }

    // ================================================================ 船壳板

    /** 一种颜色的船壳板：整块、不透明，六个面都画板（侧面按板列取图，顶 / 底是素板）。 */
    public static final class HullPlate extends Block implements LinerLooks.Styled {

        private final String color;
        private final String topColor;

        HullPlate(AbstractBlock.Settings settings, String color, String topColor) {
            super(settings);
            this.color = color;
            this.topColor = topColor;
            setDefaultState(getStateManager().getDefaultState().with(CELL, 0));
        }

        @Override
        protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
            builder.add(CELL);
        }

        @Override
        public BlockState getPlacementState(ItemPlacementContext ctx) {
            return getDefaultState().with(CELL, Rules.cell(ctx.getBlockPos()));
        }

        /** 放结构、转结构之后按新位置重算（游戏放结构时对每一格都问一遍这里）。 */
        @Override
        protected BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                       WorldAccess world, BlockPos pos, BlockPos neighborPos) {
            return state.with(CELL, Rules.cell(pos));
        }

        @Override
        public LinerLooks.Look look(BlockState state) {
            int cell = state.get(CELL);
            String top = "hull/" + topColor + "_top";
            return LinerLooks.look("hull/plate", tex(
                    "north", Rules.plateTexture(color, cell, Direction.NORTH), "south", Rules.plateTexture(color, cell, Direction.SOUTH),
                    "east", Rules.plateTexture(color, cell, Direction.EAST), "west", Rules.plateTexture(color, cell, Direction.WEST),
                    "up", top, "down", top));
        }
    }

    // ================================================================ 烟囱板

    /**
     * 烟囱与桅杆的钢板：与船壳板同一套贴图，但<b>四个侧面都不画竖缝</b>，只有每 2 格一道横缝与缝上那一排铆钉（一圈圈「箍」）。
     * 烟囱是按格子拼出来的圆筒（截面 9 × 7 的圆角长方）：台阶的棱已经是一道道竖线，再按世界坐标加竖缝，竖线就乱了。
     * 只认 y（{@link #ROW}），放下与邻居变了时按位置重算 —— 横缝与船壳同一个相位，黄褐与黑顶的分界落在横缝上。
     */
    public static final class FunnelPlate extends Block implements LinerLooks.Styled {

        private final String color;

        FunnelPlate(AbstractBlock.Settings settings, String color) {
            super(settings);
            this.color = color;
            setDefaultState(getStateManager().getDefaultState().with(ROW, 0));
        }

        @Override
        protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
            builder.add(ROW);
        }

        @Override
        public BlockState getPlacementState(ItemPlacementContext ctx) {
            return getDefaultState().with(ROW, Rules.row(ctx.getBlockPos().getY()));
        }

        @Override
        protected BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                       WorldAccess world, BlockPos pos, BlockPos neighborPos) {
            return state.with(ROW, Rules.row(pos.getY()));
        }

        @Override
        public LinerLooks.Look look(BlockState state) {
            String side = Rules.funnelTexture(color, state.get(ROW));
            String top = "hull/" + color + "_top";
            return LinerLooks.look("hull/plate", tex("north", side, "south", side, "east", side, "west", side, "up", top, "down", top));
        }
    }

    // ================================================================ 舷窗（船壳上那一块）

    /**
     * 船壳上的舷窗。几何（{@code liner_hull.py} 的 {@code porthole()}）：窗洞直径 8 像素（逐行的阶梯圆），黄铜窗圈前出 0.75、
     * 玻璃后退 3 像素：最前 1 像素是黄铜唇（上侧暗一档、下侧窗台最前一像素亮），往里 2 像素是灰黑孔套（Codex 二评 k6：孔深要比金圈醒目），玻璃往里衬白漆（透过玻璃看到的是舱里）；
     * 不画背面的窗圈（里面那一格另有一块）。
     *
     * <p><b>光</b>（查过 1.21.1 的 {@code BlockModelRenderer} 与 {@code ChunkLightProvider}）：
     * <ul>
     *   <li>模型关 AO，走平光：贴着方块边的面取前面那一格的光，<b>不贴边的面</b>（后退的玻璃、窗洞内壁）取<b>这一格自己</b>的光 ——
     *       前提是这一格「不是整块」：游戏用碰撞箱判整块，所以碰撞箱挖掉窗洞那一截（{@link #getCollisionShape}）；</li>
     *   <li>这一格自己的光：方块按「侧面透光」算光（{@link #hasSidedTransparency}），遮光的形状只是贴着外面那一层薄板
     *       （{@link #getCullingShape}）—— 屋里的灯光从里面那一面进来，外面的天光、月光进不来，屋里的光也漏不到船壳外面。</li>
     * </ul>
     * 于是：客舱的灯亮着时玻璃与窗洞里是暖的，灯关了就暗；白天从外面看玻璃比船壳暗（真的窗也这样）；屋里不从舷窗进天光。
     */
    public static final class Porthole extends Block implements LinerLooks.Styled {

        private final String color;
        private final Map<Direction, VoxelShape> collision = new EnumMap<>(Direction.class);
        private final Map<Direction, VoxelShape> culling = new EnumMap<>(Direction.class);

        Porthole(AbstractBlock.Settings settings, String color) {
            super(settings);
            this.color = color;
            setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.SOUTH).with(CELL, 0).with(MERGE, Merge.SINGLE));
            for (Direction d : Direction.Type.HORIZONTAL) {
                collision.put(d, turned(Rules.holeCollision(), d));
                culling.put(d, turned(Rules.outerSkin(), d));
            }
        }

        @Override
        protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
            builder.add(FACING, CELL, MERGE);
        }

        /** 正面朝着摆它的人（人站在船外摆）；板列照位置。 */
        @Override
        public BlockState getPlacementState(ItemPlacementContext ctx) {
            return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite()).with(CELL, Rules.cell(ctx.getBlockPos()));
        }

        /** 人摆下的（游戏放结构不走这里）：连着的同一种舷窗整片重拼。 */
        @Override
        public void onPlaced(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
            super.onPlaced(world, pos, state, placer, stack);
            if (!world.isClient) {
                retile(world, pos);
            }
        }

        @Override
        protected void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
            super.onStateReplaced(state, world, pos, newState, moved);
            if (!world.isClient && !newState.isOf(this)) {
                retileAround(world, pos, state);
            }
        }

        @Override
        protected BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                       WorldAccess world, BlockPos pos, BlockPos neighborPos) {
            return state.with(CELL, Rules.cell(pos));
        }

        @Override
        public LinerLooks.Look look(BlockState state) {
            Direction f = state.get(FACING);
            Merge m = state.get(MERGE);
            Map<String, String> t = tex("front", Rules.plateTexture(color, state.get(CELL), f),
                    "plate", "hull/" + color + "_top", "inside", "wall_white", "ring", Rules.ringTexture(m), "brass", "hull/brass",
                    "glass", Rules.glassTexture(m));
            if (t.get("ring") == null) {
                t.remove("ring");
            }
            return LinerLooks.look(m.merged() ? "hull/porthole_" + m.piece() : "hull/porthole", t, Rules.yaw(f));
        }

        /** 这一格所在那一组的全部格子（单格就是它自己）：客户端判「整扇亮不亮」用（{@code GlassGlow}）。 */
        public List<BlockPos> cellsOf(BlockPos pos, BlockState state) {
            return groupCells(pos, state);
        }

        @Override
        protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
            Merge m = state.get(MERGE);
            return m.merged() ? mergedCollision(m, state.get(FACING), false) : collision.get(state.get(FACING));
        }

        /** 遮光与剔面只认外面那一层薄板（见类注释）。 */
        @Override
        protected VoxelShape getCullingShape(BlockState state, BlockView world, BlockPos pos) {
            return culling.get(state.get(FACING));
        }

        @Override
        protected boolean hasSidedTransparency(BlockState state) {
            return true;
        }

        @Override
        protected BlockState rotate(BlockState state, BlockRotation rotation) {
            return state.with(FACING, rotation.rotate(state.get(FACING)));
        }

        /** 镜像：朝向照转；拼大那一片左右对调（镜像之后看的人的左右反了 —— 朝向不变时格子换了边，朝向翻过来时看的方向换了）。 */
        @Override
        protected BlockState mirror(BlockState state, BlockMirror mirror) {
            BlockState s = state.rotate(mirror.getRotation(state.get(FACING)));
            return mirror == BlockMirror.NONE ? s : s.with(MERGE, state.get(MERGE).mirrored());
        }
    }

    // ================================================================ 舷窗里面那一面

    /**
     * 船壳舷窗正里面那一格的墙板：正面（朝屋里）是白漆墙板带黄铜圈，玻璃后退 2.5 像素（黄铜唇 1 + 孔套 1.5）；背面是白漆外墙。
     * 不挡光（屋里的灯经它照进船壳那一块，见 {@link Porthole}）；碰撞箱同样挖掉窗洞，玻璃才取这一格自己的光。
     */
    public static final class InnerPorthole extends Block implements LinerLooks.Styled {

        private final String face;
        private final Map<Direction, VoxelShape> collision = new EnumMap<>(Direction.class);

        InnerPorthole(AbstractBlock.Settings settings, String face) {
            super(settings);
            this.face = face;
            setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.SOUTH).with(MERGE, Merge.SINGLE));
            for (Direction d : Direction.Type.HORIZONTAL) {
                collision.put(d, turned(Rules.holeCollision(), d));
            }
        }

        @Override
        protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
            builder.add(FACING, MERGE);
        }

        /** 正面朝着摆它的人（人站在屋里摆）。 */
        @Override
        public BlockState getPlacementState(ItemPlacementContext ctx) {
            return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
        }

        /** 与船壳上那一块同一套拼法（按世界坐标轴排，两边朝向相反照样拼得一样，窗洞对得上）。 */
        @Override
        public void onPlaced(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
            super.onPlaced(world, pos, state, placer, stack);
            if (!world.isClient) {
                retile(world, pos);
            }
        }

        @Override
        protected void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
            super.onStateReplaced(state, world, pos, newState, moved);
            if (!world.isClient && !newState.isOf(this)) {
                retileAround(world, pos, state);
            }
        }

        @Override
        public LinerLooks.Look look(BlockState state) {
            Merge m = state.get(MERGE);
            Map<String, String> t = tex("front", face, "back", "wall_white", "ring", Rules.ringTexture(m),
                    "brass", "hull/brass", "glass", Rules.glassTexture(m));
            if (t.get("ring") == null) {
                t.remove("ring");
            }
            return LinerLooks.look(m.merged() ? "hull/porthole_inner_" + m.piece() : "hull/porthole_inner", t, Rules.yaw(state.get(FACING)));
        }

        @Override
        protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
            Merge m = state.get(MERGE);
            return m.merged() ? mergedCollision(m, state.get(FACING), true) : collision.get(state.get(FACING));
        }

        @Override
        protected BlockState rotate(BlockState state, BlockRotation rotation) {
            return state.with(FACING, rotation.rotate(state.get(FACING)));
        }

        /** 镜像：朝向照转；拼大那一片左右对调（镜像之后看的人的左右反了 —— 朝向不变时格子换了边，朝向翻过来时看的方向换了）。 */
        @Override
        protected BlockState mirror(BlockState state, BlockMirror mirror) {
            BlockState s = state.rotate(mirror.getRotation(state.get(FACING)));
            return mirror == BlockMirror.NONE ? s : s.with(MERGE, state.get(MERGE).mirrored());
        }
    }

    /** 模板坐标（正面朝南）的盒子转到朝向 f：与方块状态的 y 旋转同一个方向（{@link LinerLooks#rotateY}）。 */
    static VoxelShape turned(double[][] boxes, Direction f) {
        VoxelShape shape = VoxelShapes.empty();
        for (double[] b : boxes) {
            double[] lo = LinerLooks.rotateY(b[0], b[2], Rules.yaw(f));
            double[] hi = LinerLooks.rotateY(b[3], b[5], Rules.yaw(f));
            shape = VoxelShapes.union(shape, VoxelShapes.cuboid(new Box(
                    Math.min(lo[0], hi[0]) / 16, b[1] / 16, Math.min(lo[1], hi[1]) / 16,
                    Math.max(lo[0], hi[0]) / 16, b[4] / 16, Math.max(lo[1], hi[1]) / 16)));
        }
        return shape.simplify();
    }

    // ================================================================ 舷窗拼大（ADR-0093 B12）

    /**
     * 拼大那一片：{@code g<n>_<列><行>}（列从站在正面看的左手数、行从下往上；名字与 {@code liner_hull.py} 的 MERGE_VALUES 逐个相同）。
     * 单格 {@link #SINGLE} 会自动拼；{@link #LOCKED}（装修锤潜行右键）是锁成单格，不拼、也挡着别人拼过去。
     */
    public enum Merge implements StringIdentifiable {
        SINGLE, LOCKED,
        G2_00, G2_01, G2_10, G2_11,
        G3_00, G3_01, G3_02, G3_10, G3_11, G3_12, G3_20, G3_21, G3_22,
        G4_00, G4_01, G4_02, G4_03, G4_10, G4_11, G4_12, G4_13, G4_20, G4_21, G4_22, G4_23, G4_30, G4_31, G4_32, G4_33;

        /** 一组几格见方（单格 1）· 第几列 · 第几行。 */
        public final int n;
        public final int col;
        public final int row;

        Merge() {
            String s = name();
            boolean g = s.charAt(0) == 'G';
            n = g ? s.charAt(1) - '0' : 1;
            col = g ? s.charAt(3) - '0' : 0;
            row = g ? s.charAt(4) - '0' : 0;
        }

        @Override
        public String asString() {
            return name().toLowerCase(Locale.ROOT);
        }

        public boolean merged() {
            return n > 1;
        }

        /** 模板与窗圈贴图名的后缀：{@code <n>_<列><行>}。 */
        public String piece() {
            return n + "_" + col + row;
        }

        public static Merge of(int n, int col, int row) {
            return n == 1 ? SINGLE : valueOf("G" + n + "_" + col + row);
        }

        /** 左右对调（镜像）。 */
        public Merge mirrored() {
            return merged() ? of(n, n - 1 - col, row) : this;
        }
    }

    /** 船壳上那一块或屋里那一块。 */
    public static boolean isPorthole(BlockState state) {
        return state.getBlock() instanceof Porthole || state.getBlock() instanceof InnerPorthole;
    }

    /** 这一格所在那一组的全部格子（按状态里记的那一片往回推左下角；单格、锁住的就是它自己）。 */
    static List<BlockPos> groupCells(BlockPos pos, BlockState state) {
        Merge m = state.get(MERGE);
        if (!m.merged()) {
            return List.of(pos);
        }
        Direction right = LinerConnect.viewerRight(state.get(FACING));
        BlockPos origin = pos.offset(right, -m.col).down(m.row);
        List<BlockPos> out = new ArrayList<>(m.n * m.n);
        for (int c = 0; c < m.n; c++) {
            for (int r = 0; r < m.n; r++) {
                out.add(origin.offset(right, c).up(r));
            }
        }
        return out;
    }

    /** 墙面里那一条水平轴（朝南北的舷窗是 x，朝东西的是 z）。 */
    private static Direction.Axis along(Direction facing) {
        return facing.rotateYClockwise().getAxis();
    }

    /**
     * 从 start 起把连着的同一种、同一个朝向、没锁的舷窗整片重拼（{@link Rules#tile}），只改变了的那几格。
     * 写格「通知客户端、不做邻居的形状更新」：重拼不该引起别的连锁（舷窗自己的邻居更新只重算板列）。
     */
    static void retile(World world, BlockPos start) {
        BlockState s0 = world.getBlockState(start);
        if (!isPorthole(s0) || s0.get(MERGE) == Merge.LOCKED) {
            return;
        }
        Block block = s0.getBlock();
        Direction facing = s0.get(FACING);
        Direction.Axis axis = along(facing);
        Set<BlockPos> region = new LinkedHashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>(List.of(start));
        while (!queue.isEmpty() && region.size() < MAX_REGION) {
            BlockPos p = queue.poll();
            if (region.contains(p) || !world.getChunkManager().isChunkLoaded(p.getX() >> 4, p.getZ() >> 4)) {
                continue;
            }
            BlockState s = world.getBlockState(p);
            if (!s.isOf(block) || s.get(FACING) != facing || s.get(MERGE) == Merge.LOCKED) {
                continue;
            }
            region.add(p);
            queue.add(p.up());
            queue.add(p.down());
            queue.add(p.offset(axis, 1));
            queue.add(p.offset(axis, -1));
        }
        Map<Long, BlockPos> byKey = new HashMap<>();
        for (BlockPos p : region) {
            byKey.put(Rules.key(p.getComponentAlongAxis(axis), p.getY()), p);
        }
        boolean rightPositive = LinerConnect.viewerRight(facing).getDirection() == Direction.AxisDirection.POSITIVE;
        Rules.tile(byKey.keySet()).forEach((key, t) -> {
            BlockPos p = byKey.get(key);
            int n = t[0];
            Merge m = Merge.of(n, rightPositive ? t[1] : n - 1 - t[1], t[2]);
            BlockState s = world.getBlockState(p);
            if (s.get(MERGE) != m) {
                world.setBlockState(p, s.with(MERGE, m), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
            }
        });
    }

    /** 拆掉 / 锁住了 pos（原来是 old）之后：墙面上挨着它的四格各自那一片重拼（拆开的几块各拼各的）。 */
    static void retileAround(World world, BlockPos pos, BlockState old) {
        Direction.Axis axis = along(old.get(FACING));
        for (BlockPos p : List.of(pos.up(), pos.down(), pos.offset(axis, 1), pos.offset(axis, -1))) {
            BlockState s = world.getBlockState(p);
            if (s.isOf(old.getBlock()) && s.get(FACING) == old.get(FACING)) {
                retile(world, p);
            }
        }
    }

    /**
     * 装修锤潜行右键：锁成单格 ↔ 解锁（自动拼）。船壳那一块与正里面那一块（屋里那一面，朝向相反）一起换 —— 只锁一边，
     * 两边拼出来的不一样，窗洞就对不上了。→ 锁上了没有。
     */
    public static boolean toggleLock(World world, BlockPos pos) {
        BlockState s = world.getBlockState(pos);
        boolean lock = s.get(MERGE) != Merge.LOCKED;
        Direction facing = s.get(FACING);
        List<BlockPos> cells = new ArrayList<>(List.of(pos));
        BlockPos partner = pos.offset(facing.getOpposite());
        BlockState ps = world.getBlockState(partner);
        if (isPorthole(ps) && ps.getBlock() != s.getBlock() && ps.get(FACING) == facing.getOpposite()) {
            cells.add(partner);
        }
        for (BlockPos p : cells) {
            BlockState old = world.getBlockState(p);
            world.setBlockState(p, old.with(MERGE, lock ? Merge.LOCKED : Merge.SINGLE), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
            if (lock) {
                retileAround(world, p, old);
            } else {
                retile(world, p);
            }
        }
        return lock;
    }

    private static final Map<String, VoxelShape> MERGED_SHAPES = new ConcurrentHashMap<>();

    /** 拼大那一片的碰撞箱：按这一格里的窗洞逐行挖空（{@link Rules#mergedCollision}），转到朝向；一种一份。 */
    static VoxelShape mergedCollision(Merge m, Direction facing, boolean inner) {
        return MERGED_SHAPES.computeIfAbsent(m.asString() + facing.asString() + inner,
                k -> turned(Rules.mergedCollision(m, inner ? 16 - 2.5 : 16 - 3), facing));
    }

    // ================================================================ 纯规则

    /** 不碰世界的那一半：单测直接测这里（{@code LinerHullRulesTest}）；{@code liner_hull.py} 里有同一套的 Python 抄本。 */
    public static final class Rules {

        /** {@link #CELL} 的取值个数：横缝里的上下两格 × x 向竖缝（无 · 在低边 · 在高边）× z 向竖缝。 */
        public static final int CELLS = 18;
        /**
         * 横缝的相位：一列板 2 格高，第 {@code floorDiv(y − 1, 2)} 列。取 1 是让北辰号的水线（y 13）与每一层甲板的地板（F 15 · E 19 · D 23 ·
         * C 31 / 29 · B 35 / 33 · A 39 / 35，层高 8 与 6 两版都是单数）都正好落在两列板的接缝上 —— 换颜色的地方就是一道横缝。
         */
        public static final int PHASE = 1;
        /** 竖缝：每 10 格一个周期，板长 4 与 6 交替；单数列整体挪 8 格（= 往回 2），上下两列的竖缝至少差 2 格。 */
        public static final int PERIOD = 10;
        public static final int ODD_SHIFT = 8;
        private static final int[] BUTTS = {0, 4};

        private Rules() {
        }

        /** 这一格在第几列板（往上数；负数照样）。 */
        public static int strake(int y) {
            return Math.floorDiv(y - PHASE, 2);
        }

        /** 0 = 一列板的下面那一格（下沿是横缝），1 = 上面那一格。 */
        public static int row(int y) {
            return Math.floorMod(y - PHASE, 2);
        }

        /** 第 strake 列里，h 与 h − 1 之间是不是一道竖缝。❗用 floorMod：负坐标上 % 是负数，竖缝会在 0 那条线两边错位。 */
        public static boolean buttBefore(int h, int strake) {
            int k = Math.floorMod(h + ODD_SHIFT * Math.floorMod(strake, 2), PERIOD);
            for (int b : BUTTS) {
                if (k == b) {
                    return true;
                }
            }
            return false;
        }

        /** 沿一个水平轴：0 = 这一格两边都没有竖缝 · 1 = 竖缝在坐标小的那条边 · 2 = 在坐标大的那条边。 */
        public static int edge(int h, int strake) {
            return buttBefore(h, strake) ? 1 : buttBefore(h + 1, strake) ? 2 : 0;
        }

        /** 世界坐标 → {@link #CELL}：{@code row + 2 · x 向竖缝 + 6 · z 向竖缝}。 */
        public static int cell(int x, int y, int z) {
            int s = strake(y);
            return row(y) + 2 * edge(x, s) + 6 * edge(z, s);
        }

        public static int cell(BlockPos pos) {
            return cell(pos.getX(), pos.getY(), pos.getZ());
        }

        /**
         * 这一格朝 face 那一面的竖缝画在贴图的哪一边（{@code ""} · {@code "l"} · {@code "r"}）。贴图的左 = 站在那一面前看的人的左手：
         * 南面看的人左手是西（x 小）、北面是东、西面是北（z 小）、东面是南。竖缝线由「缝在自己左手边」的那一格画（{@code l}），
         * 右边那一格只画铆钉（{@code r}）—— 一道缝只有一条线。
         */
        public static String seam(int cell, Direction face) {
            int e = switch (face) {
                case NORTH, SOUTH -> (cell / 2) % 3;
                case EAST, WEST -> cell / 6;
                default -> 0;
            };
            if (e == 0) {
                return "";
            }
            boolean lowIsLeft = face == Direction.SOUTH || face == Direction.WEST;
            return (e == 1) == lowIsLeft ? "l" : "r";
        }

        /** 烟囱板四个侧面的贴图名：{@code hull/<颜色>_<上下格>}（不带竖缝的那一张）。 */
        public static String funnelTexture(String color, int row) {
            return "hull/" + color + "_" + row;
        }

        /** 船壳板那一面的贴图名：{@code hull/<颜色>_<上下格><竖缝>}。烟囱板只用没有竖缝的那一张（{@link #funnelTexture}）。 */
        public static String plateTexture(String color, int cell, Direction face) {
            return "hull/" + color + "_" + (cell % 2) + seam(cell, face);
        }

        /** 朝向 → y 旋转：模板正面朝南（同 {@link LinerBlock#yawOf}）。 */
        public static int yaw(Direction facing) {
            return LinerBlock.yawOf(facing);
        }

        /** 碰撞箱（模板坐标，正面朝南）：整块挖掉窗洞那一截（8 × 8 的方孔贯穿）—— 让游戏判「不是整块」，玻璃才取这一格自己的光。 */
        public static double[][] holeCollision() {
            return new double[][]{{0, 0, 0, 16, 4, 16}, {0, 12, 0, 16, 16, 16}, {0, 4, 0, 4, 12, 16}, {12, 4, 0, 16, 12, 16}};
        }

        /** 遮光的形状（模板坐标）：贴着外面（南面）那一层 1 像素的薄板。 */
        public static double[][] outerSkin() {
            return new double[][]{{0, 0, 15, 16, 16, 16}};
        }

        // ---------------------------------------------------------------- 舷窗拼大

        /** 拼大的黄铜圈宽（像素）：用户 10-07 挑的样张。 */
        public static final int MERGE_RING = 2;

        /** n 格见方那一组的窗洞半径：单格 4（直径 8），拼大的占整块的八分之五（直径 10n）。 */
        public static double holeRadius(int n) {
            return n == 1 ? 4 : 5.0 * n;
        }

        public static double ringRadius(int n) {
            return n == 1 ? 5 : 5.0 * n + MERGE_RING;
        }

        /**
         * 阶梯圆（画布 16n、圆心在正中）第 y 行的半宽，0.5 像素取整；不在圆里是 0 —— {@code liner_hull.disc_rows} 的抄本。
         * Python 的 round 是「四舍六入五成双」、这里是五入，但这几种半径下 2·半宽 从来不正好落在 .5 上（半径是整数、行心在半格上，
         * 4·(R² − d²) 是整数而 (k + ½)² 不是），两边答案一样。
         */
        public static double rowHalf(double r, int n, int y) {
            double d = Math.abs(y + 0.5 - 8 * n);
            if (r <= d) {
                return 0;
            }
            return Math.round(Math.sqrt(r * r - d * d) * 2) / 2.0;
        }

        /** 拼大那一格里有没有窗圈（没有就不给窗圈贴图 —— 生成器也不出那一张）。取样与 {@code liner_hull.ring_piece} 同一套。 */
        public static boolean hasRing(Merge m) {
            double c = 8 * m.n;
            double hole = holeRadius(m.n);
            double ring = ringRadius(m.n);
            for (int y = 16 * m.row; y < 16 * m.row + 16; y++) {
                double ho = rowHalf(ring, m.n, y);
                double hh = rowHalf(hole, m.n, y);
                for (int u = 0; u < 16; u++) {
                    double dx = Math.abs(16 * m.col + u + 0.5 - c);
                    if (dx < ho && !(dx < hh)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** 玻璃贴图：单格 {@code hull/glass}，拼大那一片 {@code hull/glass_<n>_<列><行>}（按原来的像素大小画、按格切；亮着的那一张 {@code GlassGlow} 换）。 */
        public static String glassTexture(Merge m) {
            return m.merged() ? "hull/glass_" + m.piece() : "hull/glass";
        }

        /** 窗圈贴图：单格 {@code hull/brass_ring}，拼大那一片 {@code hull/brass_ring_<n>_<列><行>}；那一片里没有窗圈是 null。 */
        @Nullable
        public static String ringTexture(Merge m) {
            if (!m.merged()) {
                return "hull/brass_ring";
            }
            return hasRing(m) ? "hull/brass_ring_" + m.piece() : null;
        }

        /**
         * 拼大那一片的碰撞箱（模板坐标，正面朝南）：逐行把这一格里的窗洞挖空、贯穿；窗洞那一截在玻璃那一层（zGlass − 0.5 … zGlass）补一片
         * —— 大窗洞人钻得过去，玻璃得挡人。照样「不是整块」，后退的玻璃取这一格自己的光（类注释 {@link Porthole}）。
         */
        public static double[][] mergedCollision(Merge m, double zGlass) {
            List<double[]> out = new ArrayList<>();
            double c = 8 * m.n;
            double hole = holeRadius(m.n);
            for (int y = 0; y < 16; y++) {
                double hw = rowHalf(hole, m.n, 16 * m.row + y);
                double lo = Math.max(0, Math.min(16, c - hw - 16 * m.col));
                double hi = Math.max(0, Math.min(16, c + hw - 16 * m.col));
                if (hw <= 0 || hi <= lo) {
                    out.add(new double[]{0, y, 0, 16, y + 1, 16});
                    continue;
                }
                if (lo > 0) {
                    out.add(new double[]{0, y, 0, lo, y + 1, 16});
                }
                if (hi < 16) {
                    out.add(new double[]{hi, y, 0, 16, y + 1, 16});
                }
                out.add(new double[]{lo, y, zGlass - 0.5, hi, y + 1, zGlass});
            }
            return out.toArray(new double[0][]);
        }

        /** 墙面上一格的键：(u, v) = (沿世界坐标轴, y)。 */
        public static long key(int u, int v) {
            return ((long) u << 32) | (v & 0xffffffffL);
        }

        public static int u(long key) {
            return (int) (key >> 32);
        }

        public static int v(long key) {
            return (int) key;
        }

        /**
         * 一片墙上的舷窗怎么拼：从最下一行起、每行从 u 小的那一头起，逐格以它为左下角试 4 × 4 · 3 × 3 · 2 × 2（整块都在、而且还没拼进别的），
         * 都不成就是单格。→ 键 → {n, 这一格在那一组里的第几列（沿 u）, 第几行}。
         * 按世界坐标轴排、不按看的人的左右：船壳那一块与屋里那一块朝向相反，按世界轴排两边才拼得一样，窗洞对得上。
         */
        public static Map<Long, int[]> tile(Set<Long> cells) {
            List<Long> order = new ArrayList<>(cells);
            order.sort(Comparator.comparingInt(Rules::v).thenComparingInt(Rules::u));
            Map<Long, int[]> out = new HashMap<>();
            for (long k : order) {
                if (out.containsKey(k)) {
                    continue;
                }
                int u0 = u(k);
                int v0 = v(k);
                int n = 4;
                for (; n > 1; n--) {
                    if (fits(cells, out, u0, v0, n)) {
                        break;
                    }
                }
                for (int du = 0; du < n; du++) {
                    for (int dv = 0; dv < n; dv++) {
                        out.put(key(u0 + du, v0 + dv), new int[]{n, du, dv});
                    }
                }
            }
            return out;
        }

        private static boolean fits(Set<Long> cells, Map<Long, int[]> taken, int u0, int v0, int n) {
            for (int du = 0; du < n; du++) {
                for (int dv = 0; dv < n; dv++) {
                    long k = key(u0 + du, v0 + dv);
                    if (!cells.contains(k) || taken.containsKey(k)) {
                        return false;
                    }
                }
            }
            return true;
        }
    }
}
