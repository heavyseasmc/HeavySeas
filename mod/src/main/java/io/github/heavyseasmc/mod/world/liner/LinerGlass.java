package io.github.heavyseasmc.mod.world.liner;

import io.github.heavyseasmc.mod.HeavySeasMod;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.MapColor;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.TransparentBlock;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.IntProperty;
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
import net.minecraft.world.WorldEvents;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.FACING;
import static io.github.heavyseasmc.mod.world.liner.LinerLooks.tex;

/**
 * 大邮轮的玻璃一批（ADR-0069 §5 已定：窗 W1 白漆细框 · 穹顶 D1 乳白磨砂 + 白漆主肋；做法见 ADR-0074）：
 * 整扇窗 · 穹顶玻璃 · 穹顶的肋。模板与贴图由 {@code docs/tools/scene/liner_glass.py --write} 画好入库（{@code glass/} 子目录）。
 *
 * <ul>
 *   <li><b>整扇窗</b>（ADR-0091 起；之前是接邻居的「无缝窗」）：一件就是一扇宽 × 高格的完整窗，嵌在一格厚的墙里，
 *       正面（屋里那一面）朝着摆它的人；框只在整扇外沿（与大框同一条规则 {@link LinerConnect.Rules#frameMask}）。</li>
 *   <li><b>穹顶玻璃</b>：整块，同种之间不画面（与游戏自带的玻璃同一个做法：继承 {@link TransparentBlock}）。</li>
 *   <li><b>穹顶的肋</b>：挂在穹顶玻璃下面那一格；{@link RibShape} 不看邻居，由生成器按穹顶的形状写好，玩家摆的时候按面朝方向给 X / Z。</li>
 * </ul>
 * 与大邮轮那一族相同：只在创造模式里拿，挖不动、不掉东西、没有配方、活塞推不动。玻璃不挡光、不闷人、摄像机穿得过。
 */
public final class LinerGlass {

    /** 肋的形状：模板（按「北」画）+ 方块状态的 y 旋转；方向是台阶立面 / 横肋 / 檐口在这一格的哪一边。 */
    public enum RibShape implements StringIdentifiable {
        X("rib", 90, Kind.MAIN), Z("rib", 0, Kind.MAIN),
        STEP_N("rib_step", 0, Kind.STEP, Direction.NORTH), STEP_E("rib_step", 90, Kind.STEP, Direction.EAST),
        STEP_S("rib_step", 180, Kind.STEP, Direction.SOUTH), STEP_W("rib_step", 270, Kind.STEP, Direction.WEST),
        RING_N("rib_ring", 0, Kind.RING, Direction.NORTH), RING_E("rib_ring", 90, Kind.RING, Direction.EAST),
        RING_S("rib_ring", 180, Kind.RING, Direction.SOUTH), RING_W("rib_ring", 270, Kind.RING, Direction.WEST),
        RING_NW("rib_ring_corner", 0, Kind.RING, Direction.NORTH, Direction.WEST),
        RING_NE("rib_ring_corner", 90, Kind.RING, Direction.NORTH, Direction.EAST),
        RING_SE("rib_ring_corner", 180, Kind.RING, Direction.SOUTH, Direction.EAST),
        RING_SW("rib_ring_corner", 270, Kind.RING, Direction.SOUTH, Direction.WEST),
        CURB_N("rib_curb", 0, Kind.CURB, Direction.NORTH), CURB_E("rib_curb", 90, Kind.CURB, Direction.EAST),
        CURB_S("rib_curb", 180, Kind.CURB, Direction.SOUTH), CURB_W("rib_curb", 270, Kind.CURB, Direction.WEST),
        CURB_NW("rib_curb_corner", 0, Kind.CURB, Direction.NORTH, Direction.WEST),
        CURB_NE("rib_curb_corner", 90, Kind.CURB, Direction.NORTH, Direction.EAST),
        CURB_SE("rib_curb_corner", 180, Kind.CURB, Direction.SOUTH, Direction.EAST),
        CURB_SW("rib_curb_corner", 270, Kind.CURB, Direction.SOUTH, Direction.WEST),
        CURB_RIB_N("rib_curb_rib", 0, Kind.CURB_RIB, Direction.NORTH), CURB_RIB_E("rib_curb_rib", 90, Kind.CURB_RIB, Direction.EAST),
        CURB_RIB_S("rib_curb_rib", 180, Kind.CURB_RIB, Direction.SOUTH), CURB_RIB_W("rib_curb_rib", 270, Kind.CURB_RIB, Direction.WEST),
        SILL_N("rib_sill", 0, Kind.SILL, Direction.NORTH), SILL_E("rib_sill", 90, Kind.SILL, Direction.EAST),
        SILL_S("rib_sill", 180, Kind.SILL, Direction.SOUTH), SILL_W("rib_sill", 270, Kind.SILL, Direction.WEST),
        SILL_NW("rib_sill_corner", 0, Kind.SILL, Direction.NORTH, Direction.WEST),
        SILL_NE("rib_sill_corner", 90, Kind.SILL, Direction.NORTH, Direction.EAST),
        SILL_SE("rib_sill_corner", 180, Kind.SILL, Direction.SOUTH, Direction.EAST),
        SILL_SW("rib_sill_corner", 270, Kind.SILL, Direction.SOUTH, Direction.WEST);

        /** 主肋 · 台阶 · 横肋 · 承托檐口 · 檐口起头的主肋 · 屋顶上的围座（穹顶在哪一边 / 哪个斜对角）。 */
        enum Kind { MAIN, STEP, RING, CURB, CURB_RIB, SILL }

        final String template;
        final int yaw;
        final Kind kind;
        final Set<Direction> sides;

        RibShape(String template, int yaw, Kind kind, Direction... sides) {
            this.template = template;
            this.yaw = yaw;
            this.kind = kind;
            this.sides = sides.length == 0 ? EnumSet.noneOf(Direction.class) : EnumSet.of(sides[0], sides);
        }

        /** 转 / 镜像之后的那一种：方向照转，主肋顺 x 与顺 z 在转 90° 时对调。 */
        RibShape map(Function<Direction, Direction> f) {
            if (kind == Kind.MAIN) {
                Direction along = f.apply(this == X ? Direction.EAST : Direction.SOUTH);
                return along.getAxis() == Direction.Axis.X ? X : Z;
            }
            Set<Direction> moved = EnumSet.noneOf(Direction.class);
            sides.forEach(d -> moved.add(f.apply(d)));
            for (RibShape s : values()) {
                if (s.kind == kind && s.sides.equals(moved)) {
                    return s;
                }
            }
            throw new IllegalStateException(this + " 转过去没有对应的形状：" + moved);
        }

        @Override
        public String asString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static final EnumProperty<RibShape> RIB_SHAPE = EnumProperty.of("shape", RibShape.class);

    private static final Map<String, Block> BLOCKS = new LinkedHashMap<>();

    private static final List<Window> WINDOWS = new ArrayList<>();
    // 整扇窗（ADR-0091，用户 2026-10-06 定「整扇定尺寸窗」）：宽 × 高（站在屋里看）1–3 × 1–3 九种 ——
    //   船上用到其中六种；九种一起，十六种 mask 的模板与玻璃每一张都有尺寸在用
    public static final Window WINDOW_1X1 = window("liner_window_1x1", 1, 1);
    public static final Window WINDOW_1X2 = window("liner_window_1x2", 1, 2);
    public static final Window WINDOW_1X3 = window("liner_window_1x3", 1, 3);
    public static final Window WINDOW_2X1 = window("liner_window_2x1", 2, 1);
    public static final Window WINDOW_2X2 = window("liner_window_2x2", 2, 2);
    public static final Window WINDOW_2X3 = window("liner_window_2x3", 2, 3);
    public static final Window WINDOW_3X1 = window("liner_window_3x1", 3, 1);
    public static final Window WINDOW_3X2 = window("liner_window_3x2", 3, 2);
    public static final Window WINDOW_3X3 = window("liner_window_3x3", 3, 3);
    public static final DomeGlass DOME_GLASS = glass("liner_dome_glass", new DomeGlass(settings(MapColor.WHITE)));
    public static final DomeRib DOME_RIB = glass("liner_dome_rib", new DomeRib(settings(MapColor.OFF_WHITE).sounds(BlockSoundGroup.WOOD)));

    private LinerGlass() {
    }

    /** 方块与物品一起登记；物品只在创造模式里拿得到。 */
    public static void register() {
        BLOCKS.forEach((name, block) -> {
            Identifier id = Identifier.of(HeavySeasMod.MOD_ID, name);
            Registry.register(Registries.BLOCK, id, block);
            Registry.register(Registries.ITEM, id, new BlockItem(block, new Item.Settings()));
        });
    }

    /** 全部玻璃一批，按登记顺序（批量生成工具、物品栏、客户端的半透明渲染层与判据用）。 */
    public static Map<String, Block> all() {
        return Collections.unmodifiableMap(BLOCKS);
    }

    /** 全部整扇窗，从小到大（客户端的半透明渲染层与判据用）。 */
    public static List<Window> windows() {
        return Collections.unmodifiableList(WINDOWS);
    }

    /** 名字照写全（构建期 checkLinerBlocks 从源码里读登记的名字）。 */
    private static Window window(String name, int width, int height) {
        if (!name.equals("liner_window_" + width + "x" + height)) {
            throw new IllegalArgumentException(name + " 与尺寸 " + width + " × " + height + " 对不上");
        }
        Window w = glass(name, Window.create(settings(MapColor.OFF_WHITE), width, height));
        WINDOWS.add(w);
        return w;
    }

    private static <T extends Block> T glass(String name, T block) {
        BLOCKS.put(name, block);
        return block;
    }

    /** 与大邮轮那一族相同：挖不动、不掉东西、活塞推不动；玻璃照游戏自带的玻璃不闷人、不挡视线、不让怪生成。 */
    private static AbstractBlock.Settings settings(MapColor color) {
        return AbstractBlock.Settings.create().mapColor(color).strength(-1.0f, 3_600_000.0f).dropsNothing()
                .pistonBehavior(PistonBehavior.BLOCK).sounds(BlockSoundGroup.GLASS).nonOpaque()
                .allowsSpawning(Blocks::never).solidBlock(Blocks::never).suffocates(Blocks::never).blockVision(Blocks::never);
    }

    private static VoxelShape box(double x0, double y0, double z0, double x1, double y1, double z1) {
        return VoxelShapes.cuboid(new Box(x0 / 16, y0 / 16, z0 / 16, x1 / 16, y1 / 16, z1 / 16));
    }

    /** 按 y 旋转（北 → 东 → 南 → 西）转一组模板坐标里的盒子。 */
    private static VoxelShape turned(int yaw, double[]... boxes) {
        VoxelShape shape = VoxelShapes.empty();
        for (double[] b : boxes) {
            double[] lo = LinerLooks.rotateY(b[0], b[2], yaw);
            double[] hi = LinerLooks.rotateY(b[3], b[5], yaw);
            shape = VoxelShapes.union(shape, box(Math.min(lo[0], hi[0]), b[1], Math.min(lo[1], hi[1]),
                    Math.max(lo[0], hi[0]), b[4], Math.max(lo[1], hi[1])));
        }
        return shape.simplify();
    }

    // ---------------------------------------------------------------- 整扇窗

    /**
     * 整扇窗（ADR-0091）：朝向（正面朝屋里、朝着摆它的人）+ 这一格在整扇里的第几列（站在正面看从左往右；宽 1 没有这个属性）·
     * 第几排（从下往上；高 1 没有）。每一格的样子就是旧的「接邻居」窗拼成同样大小时那一格（框只在整扇外沿：
     * {@link LinerConnect.Rules#frameMask}），模板正面朝南。
     *
     * <p>放下：点中的那一格是左下角，往右、往上摆满整扇；有一格摆不下就不放。拆：拆一格整扇一起拆（{@link #onBreak}）。
     * 不靠邻居更新连锁拆：北辰号一 tick 摆一段，有 6 组窗跨在段与段之间，先摆的那一段会把还没摆的半扇当成断了、整扇拆掉；
     * 这一族挖不动、炸不掉、推不动，拆只有创造模式里的玩家这一种。
     */
    public static final class Window extends Block implements LinerLooks.Styled {

        private static final IntProperty[] COLS = {null, null, IntProperty.of("col", 0, 1), IntProperty.of("col", 0, 2)};
        private static final IntProperty[] ROWS = {null, null, IntProperty.of("row", 0, 1), IntProperty.of("row", 0, 2)};
        /** 属性只能在构造时传进来：{@code appendProperties} 在 {@code Block} 的构造器里就被调用（同 {@link LinerBlock}）。 */
        private static final ThreadLocal<int[]> PENDING = new ThreadLocal<>();

        private final int width;
        private final int height;
        private final IntProperty col;
        private final IntProperty row;
        private final Map<BlockState, VoxelShape> shapes = new java.util.concurrent.ConcurrentHashMap<>();

        static Window create(AbstractBlock.Settings settings, int width, int height) {
            PENDING.set(new int[]{width, height});
            try {
                return new Window(settings, width, height);
            } finally {
                PENDING.remove();
            }
        }

        private Window(AbstractBlock.Settings settings, int width, int height) {
            super(settings);
            this.width = width;
            this.height = height;
            this.col = COLS[width];
            this.row = ROWS[height];
            setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.SOUTH));
        }

        @Override
        protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
            int[] size = PENDING.get();
            builder.add(FACING);
            if (size[0] > 1) {
                builder.add(COLS[size[0]]);
            }
            if (size[1] > 1) {
                builder.add(ROWS[size[1]]);
            }
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        public int col(BlockState s) {
            return col == null ? 0 : s.get(col);
        }

        public int row(BlockState s) {
            return row == null ? 0 : s.get(row);
        }

        /** 同一扇里 (c, r) 那一格的状态。 */
        public BlockState cell(BlockState s, int c, int r) {
            BlockState out = s;
            if (col != null) {
                out = out.with(col, c);
            }
            if (row != null) {
                out = out.with(row, r);
            }
            return out;
        }

        /** 整扇里 (c, r) 那一格画哪几边的框：上 · 下 · 左 · 右是整扇外沿的那几边（与旧窗拼成同样大小时相同）。 */
        public static String mask(int c, int r, int width, int height) {
            return LinerConnect.Rules.frameMask(r < height - 1, r > 0, c > 0, c < width - 1);
        }

        /** 左下角 origin 起，往右第 c 列、往上第 r 排那一格（左右按站在正面看的人算）。 */
        static BlockPos at(BlockPos origin, Direction facing, int c, int r) {
            return origin.offset(LinerConnect.viewerRight(facing), c).up(r);
        }

        @Override
        public LinerLooks.Look look(BlockState s) {
            String mask = mask(col(s), row(s), width, height);
            // 玻璃每种 mask 一张：画框那几边一圈压边，左上那一格（上、左都画框）带反光 —— 一扇窗一到两段斜纹，不论多大
            return LinerLooks.look("glass/window_" + mask, tex("frame", "glass/window_frame", "glass", "glass/window_glass_" + mask))
                    .turned(LinerBlock.yawOf(s.get(FACING)));
        }

        /** 物品是整扇缩小（{@code liner_glass.py} 出 {@code glass/window_item_<宽>x<高>}，每种 mask 的玻璃各一个变量 {@code glass_<mask>}）。 */
        @Override
        public LinerLooks.Look itemLook() {
            Map<String, String> t = new LinkedHashMap<>();
            t.put("frame", "glass/window_frame");
            for (int c = 0; c < width; c++) {
                for (int r = 0; r < height; r++) {
                    String m = mask(c, r, width, height);
                    t.put("glass_" + m, "glass/window_glass_" + m);
                }
            }
            return LinerLooks.look("glass/window_item_" + width + "x" + height, t);
        }

        @Override
        public BlockState getPlacementState(ItemPlacementContext ctx) {
            Direction facing = ctx.getHorizontalPlayerFacing().getOpposite();
            World world = ctx.getWorld();
            for (int c = 0; c < width; c++) {
                for (int r = 0; r < height; r++) {
                    BlockPos p = at(ctx.getBlockPos(), facing, c, r);
                    if ((c > 0 || r > 0) && (world.isOutOfHeightLimit(p) || !world.getWorldBorder().contains(p)
                            || !world.getBlockState(p).canReplace(ctx))) {
                        return null;
                    }
                }
            }
            return getDefaultState().with(FACING, facing);
        }

        /** 点中的那一格放的是左下角；其余几格在这里摆满（同游戏自带的门放上半扇）。 */
        @Override
        public void onPlaced(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
            Direction f = state.get(FACING);
            for (int c = 0; c < width; c++) {
                for (int r = 0; r < height; r++) {
                    if (c > 0 || r > 0) {
                        world.setBlockState(at(pos, f, c, r), cell(state, c, r), Block.NOTIFY_ALL);
                    }
                }
            }
        }

        /** 拆一格整扇一起拆：只拆认得出是同一扇的那几格（同一种、同一朝向、列 · 排对得上）。 */
        @Override
        public BlockState onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
            if (!world.isClient) {
                Direction f = state.get(FACING);
                BlockPos origin = at(pos, f, -col(state), -row(state));
                for (int c = 0; c < width; c++) {
                    for (int r = 0; r < height; r++) {
                        BlockPos p = at(origin, f, c, r);
                        BlockState o = world.getBlockState(p);
                        if (!p.equals(pos) && o.isOf(this) && o.get(FACING) == f && col(o) == c && row(o) == r) {
                            world.setBlockState(p, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL | Block.SKIP_DROPS);
                            world.syncWorldEvent(player, WorldEvents.BLOCK_BROKEN, p, Block.getRawIdFromState(o));
                        }
                    }
                }
            }
            return super.onBreak(world, pos, state, player);
        }

        /** 同一朝向的窗挨着：相接那一面（框条的端面）不画 —— 只有整扇里面那几边写了这种面（模板里的 cullface）。 */
        @Override
        protected boolean isSideInvisible(BlockState state, BlockState stateFrom, Direction direction) {
            return stateFrom.isOf(this) && stateFrom.get(FACING) == state.get(FACING) || super.isSideInvisible(state, stateFrom, direction);
        }

        /** 轮廓 = 玻璃面那 2 像素厚的一片（前出的框条不进轮廓，与大框相同）。 */
        @Override
        protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
            return shapes.computeIfAbsent(state, s -> turned(LinerBlock.yawOf(s.get(FACING)), new double[]{0, 0, 14, 16, 16, 16}));
        }

        @Override
        protected VoxelShape getCameraCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
            return VoxelShapes.empty();
        }

        @Override
        protected float getAmbientOcclusionLightLevel(BlockState state, BlockView world, BlockPos pos) {
            return 1.0F;
        }

        @Override
        protected boolean isTransparent(BlockState state, BlockView world, BlockPos pos) {
            return true;
        }

        /** 转：列 · 排按站在正面看的人算，跟着朝向一起转，不用换。 */
        @Override
        protected BlockState rotate(BlockState state, BlockRotation rotation) {
            return state.with(FACING, rotation.rotate(state.get(FACING)));
        }

        /** 镜像：朝向照游戏自带的做法转；左右对调，所以列从另一头数。 */
        @Override
        protected BlockState mirror(BlockState state, BlockMirror mirror) {
            if (mirror == BlockMirror.NONE) {
                return state;
            }
            BlockState s = state.rotate(mirror.getRotation(state.get(FACING)));
            return col == null ? s : s.with(col, width - 1 - state.get(col));
        }
    }

    // ---------------------------------------------------------------- 穹顶玻璃

    /** 穹顶玻璃：整块乳白磨砂，同种之间不画面，格与格之间没有框。 */
    public static final class DomeGlass extends TransparentBlock implements LinerLooks.Styled {

        DomeGlass(AbstractBlock.Settings settings) {
            super(settings);
        }

        @Override
        public LinerLooks.Look look(BlockState state) {
            return LinerLooks.look("glass/dome_glass", tex("glass", "glass/dome_glass", "side", "glass/dome_glass_side"));
        }
    }

    // ---------------------------------------------------------------- 穹顶的肋

    /** 穹顶的肋：白漆，挂在玻璃下面那一格；形状由生成器写好（不看邻居）。 */
    public static final class DomeRib extends Block implements LinerLooks.Styled {

        private static final Map<String, double[][]> BOXES = Map.of(
                "rib", new double[][]{{6.5, 13, 0, 9.5, 16, 16}},
                "rib_step", new double[][]{{6.5, 0, 0, 9.5, 16, 3}, {6.5, 13, 3, 9.5, 16, 16}, {0, 0, 0, 16, 2, 2}},
                "rib_ring", new double[][]{{0, 0, 0, 16, 2, 2}},
                "rib_ring_corner", new double[][]{{0, 0, 0, 16, 2, 2}, {0, 0, 2, 2, 2, 16}},
                "rib_curb", new double[][]{{0, 11.5, 0, 16, 16, 3}},
                "rib_curb_corner", new double[][]{{0, 11.5, 0, 16, 16, 3}, {0, 11.5, 3, 3, 16, 16}},
                "rib_curb_rib", new double[][]{{0, 11.5, 0, 16, 16, 3}, {6.5, 13, 3, 9.5, 16, 16}},
                "rib_sill", new double[][]{{0, 0, 0, 16, 2.5, 4}},
                "rib_sill_corner", new double[][]{{0, 0, 0, 4, 2.5, 4}});

        private final Map<BlockState, VoxelShape> shapes = new java.util.concurrent.ConcurrentHashMap<>();

        DomeRib(AbstractBlock.Settings settings) {
            super(settings);
            setDefaultState(getStateManager().getDefaultState().with(RIB_SHAPE, RibShape.Z));
        }

        @Override
        protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
            builder.add(RIB_SHAPE);
        }

        @Override
        public LinerLooks.Look look(BlockState s) {
            RibShape shape = s.get(RIB_SHAPE);
            return LinerLooks.look("glass/" + shape.template, tex("rib", "glass/dome_rib"), shape.yaw);
        }

        @Override
        public BlockState getPlacementState(ItemPlacementContext ctx) {
            return getDefaultState().with(RIB_SHAPE, ctx.getHorizontalPlayerFacing().getAxis() == Direction.Axis.X ? RibShape.X : RibShape.Z);
        }

        @Override
        protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
            return shapes.computeIfAbsent(state, s -> turned(s.get(RIB_SHAPE).yaw, BOXES.get(s.get(RIB_SHAPE).template)));
        }

        @Override
        protected float getAmbientOcclusionLightLevel(BlockState state, BlockView world, BlockPos pos) {
            return 1.0F;
        }

        @Override
        protected boolean isTransparent(BlockState state, BlockView world, BlockPos pos) {
            return true;
        }

        @Override
        protected BlockState rotate(BlockState state, BlockRotation rotation) {
            return state.with(RIB_SHAPE, state.get(RIB_SHAPE).map(rotation::rotate));
        }

        @Override
        protected BlockState mirror(BlockState state, BlockMirror mirror) {
            return state.with(RIB_SHAPE, state.get(RIB_SHAPE).map(mirror::apply));
        }
    }
}
