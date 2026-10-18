package io.github.heavyseasmc.mod.world.liner;

import io.github.heavyseasmc.mod.HeavySeasMod;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.state.StateManager;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.WorldAccess;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.AXIS;
import static io.github.heavyseasmc.mod.world.liner.LinerLooks.tex;

/**
 * 方块库第 5 批 · 5d（ADR-0093）：五种材料补楼梯 / 台阶 —— 白漆外墙 楼梯 + 台阶 · 平顶 台阶 · 桃花心木 台阶 · 柚木 楼梯 + 台阶 ·
 * 船壳板 楼梯 + 台阶 × 五色，共 16 种。楼梯继承游戏的楼梯方块、台阶继承游戏的台阶方块：朝向 · 上下 · 拐角 · 双层 · 含水 ·
 * 碰撞与轮廓都照游戏的（新楼梯与桃花心木楼梯、游戏自带的楼梯之间照样互相拐角 —— 游戏按 {@code instanceof StairsBlock} 认）；
 * 模型是自己的（{@code docs/tools/scene/liner_shapes.py --write}：{@code template/shape/} · {@code textures/block/liner/shape/}）。
 *
 * <ul>
 *   <li><b>楼梯</b>：模板朝北作画（高的那一边在北），方块状态 y 北 0 · 东 90 · 南 180 · 西 270（同 {@link LinerProp#yawOf}）；
 *       倒扣的另有一块上下镜像的模板（{@code DecorModels} 只写 y、不写 x 与 uvlock）。三个变量：朝上的面 · 朝下的面 · 侧面。
 *       柚木楼梯的踏板横跨梯宽（{@code shape/teak_tread} = 柚木甲板转 90°）。船壳板楼梯只画横缝（用户 10-07 定「A」）：
 *       四个侧面同一张烟囱板那一套图（{@link LinerHull.Rules#funnelTexture}），按 y 记 {@link LinerHull#ROW}，楼梯怎么转都不用换图。</li>
 *   <li><b>台阶</b>：上 / 下半各一块模板，六个面各一个变量；双层直接用整块的模板（{@code cube} · {@code floor} · {@code hull/plate}）。
 *       柚木台阶照柚木甲板记 {@link LinerBlocks#AXIS}（放下时按人看的方向）；船壳板台阶照船壳板按世界坐标记 {@link LinerHull#CELL}，
 *       每个面按位置取图（竖缝与旁边的整块船壳对得上）。</li>
 * </ul>
 * 通用件，与大邮轮那一族一样只在创造模式里拿（挖不动、不掉东西、没有配方、活塞推不动）。物品栏里每种紧跟它的整块（整块 → 楼梯 → 台阶）。
 */
public final class LinerShapes {

    private static final Map<String, Block> BLOCKS = new LinkedHashMap<>();

    // ---------------------------------------------------------------- 白漆外墙 · 平顶 · 桃花心木
    public static final Stairs WALL_WHITE_STAIRS = add("liner_wall_white_stairs",
            new Stairs(LinerBlocks.WALL_WHITE.getDefaultState(), settings(MapColor.OFF_WHITE, BlockSoundGroup.WOOD), "wall_white", "wall_white", "wall_white"));
    public static final Slab WALL_WHITE_SLAB = add("liner_wall_white_slab",
            Slab.plain(settings(MapColor.OFF_WHITE, BlockSoundGroup.WOOD), "wall_white"));
    public static final Slab CEILING_SLAB = add("liner_ceiling_slab",
            Slab.plain(settings(MapColor.OFF_WHITE, BlockSoundGroup.WOOD), "ceiling"));
    public static final Slab MAHOGANY_SLAB = add("liner_mahogany_slab",
            Slab.plain(settings(MapColor.DARK_RED, BlockSoundGroup.WOOD), "mahogany"));

    // ---------------------------------------------------------------- 柚木：楼梯的踏板横跨梯宽；台阶照甲板的走向
    public static final Stairs TEAK_STAIRS = add("liner_teak_stairs",
            new Stairs(LinerBlocks.TEAK_DECK.getDefaultState(), settings(MapColor.OAK_TAN, BlockSoundGroup.WOOD), "shape/teak_tread", "teak_side", "teak_side"));
    public static final TeakSlab TEAK_SLAB = add("liner_teak_slab", new TeakSlab(settings(MapColor.OAK_TAN, BlockSoundGroup.WOOD)));

    // ---------------------------------------------------------------- 船壳板 × 五色（颜色与顶 / 底那一面同 LinerHull 的船壳板）
    public static final HullStairs HULL_WHITE_STAIRS = add("liner_hull_white_stairs", hullStairs(LinerHull.HULL_WHITE, MapColor.OFF_WHITE, "white", "white"));
    public static final HullSlab HULL_WHITE_SLAB = add("liner_hull_white_slab", new HullSlab(steel(MapColor.OFF_WHITE), "white", "white"));
    public static final HullStairs HULL_BLACK_STAIRS = add("liner_hull_black_stairs", hullStairs(LinerHull.HULL_BLACK, MapColor.BLACK, "black", "black"));
    public static final HullSlab HULL_BLACK_SLAB = add("liner_hull_black_slab", new HullSlab(steel(MapColor.BLACK), "black", "black"));
    public static final HullStairs HULL_RED_STAIRS = add("liner_hull_red_stairs", hullStairs(LinerHull.HULL_RED, MapColor.DARK_RED, "red", "red"));
    public static final HullSlab HULL_RED_SLAB = add("liner_hull_red_slab", new HullSlab(steel(MapColor.DARK_RED), "red", "red"));
    /** 水线那一道：侧面是红板上沿一条窄白带，顶 / 底照红板（同 {@link LinerHull#HULL_WATERLINE}）。 */
    public static final HullStairs HULL_WATERLINE_STAIRS = add("liner_hull_waterline_stairs",
            hullStairs(LinerHull.HULL_WATERLINE, MapColor.DARK_RED, "waterline", "red"));
    public static final HullSlab HULL_WATERLINE_SLAB = add("liner_hull_waterline_slab", new HullSlab(steel(MapColor.DARK_RED), "waterline", "red"));
    public static final HullStairs HULL_BUFF_STAIRS = add("liner_hull_buff_stairs", hullStairs(LinerHull.HULL_BUFF, MapColor.TERRACOTTA_YELLOW, "buff", "buff"));
    public static final HullSlab HULL_BUFF_SLAB = add("liner_hull_buff_slab", new HullSlab(steel(MapColor.TERRACOTTA_YELLOW), "buff", "buff"));

    private LinerShapes() {
    }

    /** 方块与物品一起登记；物品只在创造模式里拿得到。 */
    public static void register() {
        BLOCKS.forEach((name, block) -> {
            Identifier id = Identifier.of(HeavySeasMod.MOD_ID, name);
            Registry.register(Registries.BLOCK, id, block);
            Registry.register(Registries.ITEM, id, new BlockItem(block, new Item.Settings()));
        });
    }

    /** 全部方块，按登记顺序（批量生成工具、物品栏与判据用）。 */
    public static Map<String, Block> all() {
        return Collections.unmodifiableMap(BLOCKS);
    }

    /** 物品栏里要不要转半圈看正面：楼梯模板朝北作画（高的那一边在北），踏步在南 —— 同大楼梯；台阶不转。 */
    public static boolean frontInGui(Block block) {
        return block instanceof StairsBlock;
    }

    private static <T extends Block> T add(String name, T block) {
        BLOCKS.put(name, block);
        return block;
    }

    private static HullStairs hullStairs(Block base, MapColor color, String side, String top) {
        return new HullStairs(base.getDefaultState(), steel(color), side, top);
    }

    /** 挖不动、不掉东西、活塞推不动（与大邮轮那一族相同）。不加 nonOpaque：同游戏自带的楼梯 / 台阶，按形状剔面、挡光。 */
    private static AbstractBlock.Settings settings(MapColor color, BlockSoundGroup sounds) {
        return AbstractBlock.Settings.create().mapColor(color).strength(-1.0f, 3_600_000.0f).dropsNothing()
                .pistonBehavior(PistonBehavior.BLOCK).sounds(sounds);
    }

    private static AbstractBlock.Settings steel(MapColor color) {
        return settings(color, BlockSoundGroup.METAL);
    }

    /** 楼梯那一块模板：{@code shape/stairs_<形状>}，倒扣的接 {@code _top}。 */
    static String stairsTemplate(BlockState state) {
        String shape = state.get(StairsBlock.SHAPE).asString().toLowerCase(Locale.ROOT);
        return "shape/stairs_" + shape + (state.get(StairsBlock.HALF) == BlockHalf.TOP ? "_top" : "");
    }

    /** 船壳板六个面（同 {@code LinerHull.HullPlate}）：四个侧面按 cell 与那一面的朝向取图，顶 / 底是素板。 */
    static Map<String, String> hullFaces(String color, String topColor, int cell) {
        String top = "hull/" + topColor + "_top";
        return tex("north", LinerHull.Rules.plateTexture(color, cell, Direction.NORTH),
                "south", LinerHull.Rules.plateTexture(color, cell, Direction.SOUTH),
                "east", LinerHull.Rules.plateTexture(color, cell, Direction.EAST),
                "west", LinerHull.Rules.plateTexture(color, cell, Direction.WEST),
                "up", top, "down", top);
    }

    // ================================================================ 楼梯

    /** 一种材料的楼梯：三个变量（朝上的面 · 朝下的面 · 侧面）是定的。 */
    public static class Stairs extends StairsBlock implements LinerLooks.Styled {

        private final String up;
        private final String down;
        private final String side;

        Stairs(BlockState base, AbstractBlock.Settings settings, String up, String down, String side) {
            super(base, settings);
            this.up = up;
            this.down = down;
            this.side = side;
        }

        /** 三个变量的贴图（第一项兼作碎屑贴图）。 */
        Map<String, String> textures(BlockState state) {
            return tex("side", side, "up", up, "down", down);
        }

        @Override
        public LinerLooks.Look look(BlockState state) {
            return LinerLooks.look(stairsTemplate(state), textures(state), LinerProp.yawOf(state.get(FACING)));
        }
    }

    /**
     * 船壳板楼梯：只画横缝 —— 四个侧面同一张烟囱板的图（不画竖缝，楼梯怎么转都不用换图），按 y 记 {@link LinerHull#ROW}。
     * 放下与邻居变了时先走游戏的楼梯那一套（朝向 · 拐角 · 含水），再按位置写 ROW（同 {@code LinerHull.FunnelPlate}）。
     */
    public static final class HullStairs extends Stairs {

        private final String color;
        private final String topColor;

        HullStairs(BlockState base, AbstractBlock.Settings settings, String color, String topColor) {
            super(base, settings, null, null, null);
            this.color = color;
            this.topColor = topColor;
        }

        @Override
        protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
            super.appendProperties(builder);
            builder.add(LinerHull.ROW);
        }

        @Override
        public BlockState getPlacementState(ItemPlacementContext ctx) {
            BlockState s = super.getPlacementState(ctx);
            return s == null ? null : s.with(LinerHull.ROW, LinerHull.Rules.row(ctx.getBlockPos().getY()));
        }

        @Override
        protected BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                       WorldAccess world, BlockPos pos, BlockPos neighborPos) {
            BlockState s = super.getStateForNeighborUpdate(state, direction, neighborState, world, pos, neighborPos);
            return s.isOf(this) ? s.with(LinerHull.ROW, LinerHull.Rules.row(pos.getY())) : s;
        }

        @Override
        Map<String, String> textures(BlockState state) {
            String top = "hull/" + topColor + "_top";
            return tex("side", LinerHull.Rules.funnelTexture(color, state.get(LinerHull.ROW)), "up", top, "down", top);
        }
    }

    // ================================================================ 台阶

    /** 一种材料的台阶：上 / 下半一块模板（六个面各一个变量、y 旋转），双层是整块的样子。 */
    public static class Slab extends SlabBlock implements LinerLooks.Styled {

        private final Function<BlockState, Map<String, String>> faces;
        private final Function<BlockState, LinerLooks.Look> full;
        private final ToIntFunction<BlockState> yaw;

        Slab(AbstractBlock.Settings settings, Function<BlockState, Map<String, String>> faces,
             Function<BlockState, LinerLooks.Look> full, ToIntFunction<BlockState> yaw) {
            super(settings);
            this.faces = faces;
            this.full = full;
            this.yaw = yaw;
        }

        /** 六个面同一张图、双层用 {@code cube}（白漆外墙 · 平顶 · 桃花心木：整块就是这么画的）。 */
        static Slab plain(AbstractBlock.Settings settings, String texture) {
            Map<String, String> six = tex("north", texture, "south", texture, "east", texture, "west", texture, "up", texture, "down", texture);
            return new Slab(settings, s -> six, s -> LinerLooks.look("cube", tex("all", texture)), s -> 0);
        }

        @Override
        public LinerLooks.Look look(BlockState state) {
            SlabType type = state.get(TYPE);
            if (type == SlabType.DOUBLE) {
                return full.apply(state);
            }
            return LinerLooks.look(type == SlabType.TOP ? "shape/slab_top" : "shape/slab_bottom", faces.apply(state), yaw.applyAsInt(state));
        }
    }

    /**
     * 柚木台阶：照柚木甲板记板的走向（{@link LinerBlocks#AXIS}：放下时按人看的方向，axis = x 时 y 90）；双层就是一格柚木甲板（{@code floor}）。
     * 叠成双层时沿用下面那一块的走向（游戏的台阶叠层只改 type）。
     */
    public static final class TeakSlab extends Slab {

        TeakSlab(AbstractBlock.Settings settings) {
            super(settings,
                    s -> tex("up", "teak_deck", "north", "teak_side", "south", "teak_side", "east", "teak_side", "west", "teak_side", "down", "teak_side"),
                    s -> LinerLooks.look("floor", tex("top", "teak_deck", "side", "teak_side"), deckYaw(s)),
                    TeakSlab::deckYaw);
        }

        static int deckYaw(BlockState s) {
            return s.get(AXIS) == Direction.Axis.X ? 90 : 0;
        }

        @Override
        protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
            super.appendProperties(builder);
            builder.add(AXIS);
        }

        @Override
        public BlockState getPlacementState(ItemPlacementContext ctx) {
            BlockState s = super.getPlacementState(ctx);
            if (s == null || ctx.getWorld().getBlockState(ctx.getBlockPos()).isOf(this)) {
                return s;                                            // 叠成双层：沿用已经在那儿的那一块的走向
            }
            return s.with(AXIS, ctx.getHorizontalPlayerFacing().getAxis());
        }

        /** 结构整体转 90° 时板的走向跟着换（同柚木甲板 {@code LinerBlock#rotate}）。 */
        @Override
        protected BlockState rotate(BlockState state, BlockRotation rotation) {
            if (rotation == BlockRotation.CLOCKWISE_90 || rotation == BlockRotation.COUNTERCLOCKWISE_90) {
                return state.with(AXIS, state.get(AXIS) == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X);
            }
            return state;
        }
    }

    /**
     * 船壳板台阶：照船壳板按世界坐标记 {@link LinerHull#CELL}，每个面按位置取图（竖缝与旁边的整块船壳对得上）；台阶不转（y 恒 0）。
     * 双层就是一格船壳板（{@code hull/plate}）。放下与邻居变了时先走游戏的台阶那一套（叠层 · 含水），再按位置写 CELL。
     */
    public static final class HullSlab extends Slab {

        HullSlab(AbstractBlock.Settings settings, String color, String topColor) {
            super(settings, s -> hullFaces(color, topColor, s.get(LinerHull.CELL)),
                    s -> LinerLooks.look("hull/plate", hullFaces(color, topColor, s.get(LinerHull.CELL))), s -> 0);
        }

        @Override
        protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
            super.appendProperties(builder);
            builder.add(LinerHull.CELL);
        }

        @Override
        public BlockState getPlacementState(ItemPlacementContext ctx) {
            BlockState s = super.getPlacementState(ctx);
            return s == null ? null : s.with(LinerHull.CELL, LinerHull.Rules.cell(ctx.getBlockPos()));
        }

        @Override
        protected BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                       WorldAccess world, BlockPos pos, BlockPos neighborPos) {
            BlockState s = super.getStateForNeighborUpdate(state, direction, neighborState, world, pos, neighborPos);
            return s.isOf(this) ? s.with(LinerHull.CELL, LinerHull.Rules.cell(pos)) : s;
        }
    }
}
