package io.github.heavyseasmc.mod.world.liner;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static io.github.heavyseasmc.mod.world.liner.LinerLooks.tex;

/**
 * 大楼梯一族里「看邻居」的那几件（ADR 草稿 stairs）：平台栏杆 · 斜栏杆 · 起步柱（球形柱头 / 黄铜灯）· 洞口收口。
 *
 * <ul>
 *   <li><b>平台栏杆</b>（{@link Kind#BALUSTRADE}）：铸铁栏杆 + 桃花心木扶手，四向按邻居接（像玻璃板）：
 *       邻居是平台栏杆、起步柱，或者斜栏杆的顶段放平的那一头正对着这一格，就往那边伸一臂。</li>
 *   <li><b>斜栏杆</b>（{@link Kind#SLOPE}）：坐在楼梯方块上面那一格，扶手斜 45°；朝向 = 上坡的方向。
 *       哪一段（底 · 中 · 顶 · 单独一格）看斜下方与斜上方是不是同朝向的斜栏杆：底段的扶手往回伸到起步柱，顶段经 22.5° 一折放平。</li>
 *   <li><b>起步柱</b>（{@link Kind#NEWEL} · {@link Kind#NEWEL_LAMP}）：四向接扶手的短截，接法同平台栏杆；带灯的那一种右键开关，亮着时 13。</li>
 *   <li><b>木作主景</b>（{@link Kind#FEATURE_POST} · {@link Kind#FEATURE_LINTEL} · {@link Kind#CLOCK}，平台正面那道墙）：都贴在墙前面那一格；
 *       立框按上下邻居分段（同收口），横楣按左右邻居定哪一头出端面（同顶帽 {@link LinerConnect.Rules#cappingEnd}），钟面是一格的细模。</li>
 *   <li><b>收口</b>（{@link Kind#WELL_TRIM}）：照檐口那一套 —— 贴在宿主（楼板侧面）前面那一格、正面朝外，拐角按 {@link LinerConnect.Rules#cornerShape}；
 *       上下叠着两格（上一层楼板 + 下一层的平顶）时分成上 / 下两段（{@link Rules#trimPart}）。</li>
 * </ul>
 * 模板：栏杆、起步柱朝北作画（北 0 · 东 90 · 南 180 · 西 270，斜栏杆「朝北」= 往北是上坡）；收口照檐口朝南作画（南 0 · 西 90 · 北 180 · 东 270）。
 * 纯规则在 {@link Rules}，单测 {@code LinerStairRulesTest}；{@code liner_build.py} 里有同一套规则的 Python 抄本（结构放下去游戏会再算一遍）。
 */
public final class LinerStairPiece extends Block implements LinerLooks.Styled {

    /** 摆法。 */
    public enum Kind {
        BALUSTRADE, SLOPE, NEWEL, NEWEL_LAMP, WELL_TRIM,
        /** 平台正面木作主景的立框（叠几格就几格高，底段台座、顶段柱头，part 同收口）。 */
        FEATURE_POST,
        /** 主景的横楣（一段梁，两头不再接同一种横楣的那一头出端面，end 同顶帽）。 */
        FEATURE_LINTEL,
        /** 主景的钟面（一格，不写字）。 */
        CLOCK
    }

    /** 斜栏杆是一段里的哪一段。 */
    public enum SlopePart implements StringIdentifiable {
        BOTTOM, MIDDLE, TOP, SINGLE;

        @Override
        public String asString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 收口是上下叠着的哪一段：单独一格 · 上段（下面还有一格收口）· 下段 · 中段。 */
    public enum TrimPart implements StringIdentifiable {
        SINGLE, TOP, BOTTOM, MIDDLE;

        @Override
        public String asString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static final BooleanProperty NORTH = Properties.NORTH;
    public static final BooleanProperty EAST = Properties.EAST;
    public static final BooleanProperty SOUTH = Properties.SOUTH;
    public static final BooleanProperty WEST = Properties.WEST;
    public static final BooleanProperty LIT = Properties.LIT;
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final EnumProperty<SlopePart> SLOPE_PART = EnumProperty.of("part", SlopePart.class);
    public static final EnumProperty<TrimPart> TRIM_PART = EnumProperty.of("part", TrimPart.class);
    /** 起步灯亮着时的光照：在落地灯（13–14）与小吊灯（12）之间（ADR-0069 §1a 灯的主次）。 */
    public static final int LAMP_LIGHT = 13;

    private static final ThreadLocal<Kind> PENDING = new ThreadLocal<>();

    private final Kind kind;
    private final Map<BlockState, VoxelShape> outlines = new ConcurrentHashMap<>();
    private final Map<BlockState, VoxelShape> collisions = new ConcurrentHashMap<>();

    static LinerStairPiece create(AbstractBlock.Settings settings, Kind kind) {
        PENDING.set(kind);
        try {
            return new LinerStairPiece(settings, kind);
        } finally {
            PENDING.remove();
        }
    }

    private LinerStairPiece(AbstractBlock.Settings settings, Kind kind) {
        super(settings);
        this.kind = kind;
        BlockState s = getStateManager().getDefaultState();
        switch (kind) {
            case BALUSTRADE, NEWEL, NEWEL_LAMP -> {
                s = s.with(NORTH, false).with(EAST, false).with(SOUTH, false).with(WEST, false);
                if (kind == Kind.NEWEL_LAMP) {
                    s = s.with(LIT, true);
                }
            }
            case SLOPE -> s = s.with(FACING, Direction.NORTH).with(SLOPE_PART, SlopePart.SINGLE);
            case WELL_TRIM -> s = s.with(FACING, Direction.SOUTH).with(LinerBlocks.SHAPE, LinerBlocks.CornerShape.STRAIGHT)
                    .with(TRIM_PART, TrimPart.SINGLE);
            case FEATURE_POST -> s = s.with(FACING, Direction.SOUTH).with(TRIM_PART, TrimPart.SINGLE);
            case FEATURE_LINTEL -> s = s.with(FACING, Direction.SOUTH).with(LinerBlocks.END, LinerBlocks.CappingEnd.BOTH);
            case CLOCK -> s = s.with(FACING, Direction.SOUTH);
        }
        setDefaultState(s);
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        switch (PENDING.get()) {
            case BALUSTRADE, NEWEL -> builder.add(NORTH, EAST, SOUTH, WEST);
            case NEWEL_LAMP -> builder.add(NORTH, EAST, SOUTH, WEST, LIT);
            case SLOPE -> builder.add(FACING, SLOPE_PART);
            case WELL_TRIM -> builder.add(FACING, LinerBlocks.SHAPE, TRIM_PART);
            case FEATURE_POST -> builder.add(FACING, TRIM_PART);
            case FEATURE_LINTEL -> builder.add(FACING, LinerBlocks.END);
            case CLOCK -> builder.add(FACING);
        }
    }

    public Kind kind() {
        return kind;
    }

    // ---------------------------------------------------------------- 样子

    @Override
    public LinerLooks.Look look(BlockState s) {
        return switch (kind) {
            case BALUSTRADE -> LinerLooks.look("stairs/balustrade_" + mask(s), tex("b", "stairs/balustrade"), 0);
            case SLOPE -> LinerLooks.look("stairs/balustrade_slope_" + s.get(SLOPE_PART).asString(), tex("b", "stairs/balustrade"),
                    LinerProp.yawOf(s.get(FACING)));
            case NEWEL -> LinerLooks.look("stairs/newel_" + mask(s), tex("b", "stairs/newel"), 0);
            case NEWEL_LAMP -> {
                String st = s.get(LIT) ? "lit" : "off";
                yield LinerLooks.look("stairs/newel_lamp_" + mask(s) + "_" + st, tex("b", "stairs/newel", "g", "stairs/newel_glow_" + st), 0);
            }
            case WELL_TRIM -> LinerLooks.look("stairs/well_trim_" + s.get(LinerBlocks.SHAPE).asString() + "_" + s.get(TRIM_PART).asString(),
                    tex("p", "stairs/trim", "n", "stairs/nosing"), LinerBlock.yawOf(s.get(FACING)));
            case FEATURE_POST -> LinerLooks.look("stairs/feature_post_" + s.get(TRIM_PART).asString(), tex("w", "stairs/feature_post"),
                    LinerBlock.yawOf(s.get(FACING)));
            case FEATURE_LINTEL -> LinerLooks.look("stairs/feature_lintel_" + s.get(LinerBlocks.END).asString(),
                    tex("l", "stairs/feature_lintel"), LinerBlock.yawOf(s.get(FACING)));
            case CLOCK -> LinerLooks.look("stairs/clock", tex("b", "stairs/clock"), LinerBlock.yawOf(s.get(FACING)));
        };
    }

    @Override
    public LinerLooks.Look itemLook() {
        return switch (kind) {
            case SLOPE -> LinerLooks.look("stairs/balustrade_slope_item", tex("b", "stairs/balustrade"), 0);
            case NEWEL_LAMP -> LinerLooks.look("stairs/newel_lamp_item", tex("b", "stairs/newel", "g", "stairs/newel_glow_lit"), 0);
            default -> null;
        };
    }

    private static String mask(BlockState s) {
        return Rules.mask(s.get(NORTH), s.get(EAST), s.get(SOUTH), s.get(WEST));
    }

    // ---------------------------------------------------------------- 摆 · 看邻居 · 开关

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        BlockState s = getDefaultState();
        World world = ctx.getWorld();
        BlockPos pos = ctx.getBlockPos();
        if (kind == Kind.SLOPE) {
            // 摆在楼梯上：跟着正下方那一级的上坡方向；不然朝摆它的人面朝的方向（人往上坡看着摆）
            BlockState below = world.getBlockState(pos.down());
            Direction f = below.getBlock() instanceof StairsBlock && below.get(StairsBlock.HALF) == BlockHalf.BOTTOM
                    ? below.get(StairsBlock.FACING) : ctx.getHorizontalPlayerFacing();
            s = s.with(FACING, f);
        } else if (kind == Kind.WELL_TRIM || kind == Kind.FEATURE_POST || kind == Kind.FEATURE_LINTEL || kind == Kind.CLOCK) {
            s = s.with(FACING, ctx.getSide().getAxis().isHorizontal() ? ctx.getSide() : ctx.getHorizontalPlayerFacing().getOpposite());
        }
        return connect(s, world, pos);
    }

    @Override
    protected BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                   WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        return connect(state, world, pos);
    }

    /** 按邻居重算：四向接（栏杆、起步柱）· 哪一段（斜栏杆）· 拐角与上下段（收口）。 */
    BlockState connect(BlockState s, BlockView world, BlockPos pos) {
        switch (kind) {
            case BALUSTRADE, NEWEL, NEWEL_LAMP -> {
                return s.with(NORTH, joins(world, pos, Direction.NORTH)).with(EAST, joins(world, pos, Direction.EAST))
                        .with(SOUTH, joins(world, pos, Direction.SOUTH)).with(WEST, joins(world, pos, Direction.WEST));
            }
            case SLOPE -> {
                Direction f = s.get(FACING);
                boolean lower = sameSlope(world.getBlockState(pos.offset(f.getOpposite()).down()), f);
                boolean upper = sameSlope(world.getBlockState(pos.offset(f).up()), f);
                return s.with(SLOPE_PART, Rules.slopePart(lower, upper));
            }
            case WELL_TRIM -> {
                int f = LinerConnect.index(s.get(FACING));
                String shape = LinerConnect.Rules.cornerShape(f, d -> {
                    BlockState other = world.getBlockState(pos.offset(LinerConnect.direction(d)));
                    return other.isOf(this) ? LinerConnect.index(other.get(FACING)) : null;
                });
                boolean above = sameTrim(world.getBlockState(pos.up()), s.get(FACING));
                boolean below = sameTrim(world.getBlockState(pos.down()), s.get(FACING));
                return s.with(LinerBlocks.SHAPE, LinerBlocks.CornerShape.of(shape)).with(TRIM_PART, Rules.trimPart(above, below));
            }
            case FEATURE_POST -> {
                return s.with(TRIM_PART, Rules.trimPart(sameTrim(world.getBlockState(pos.up()), s.get(FACING)),
                        sameTrim(world.getBlockState(pos.down()), s.get(FACING))));
            }
            case FEATURE_LINTEL -> {
                Direction f = s.get(FACING);
                String end = LinerConnect.Rules.cappingEnd(sameTrim(world.getBlockState(pos.offset(LinerConnect.viewerLeft(f))), f),
                        sameTrim(world.getBlockState(pos.offset(LinerConnect.viewerRight(f))), f));
                return s.with(LinerBlocks.END, LinerBlocks.CappingEnd.of(end));
            }
            default -> {
                return s;
            }
        }
    }

    /**
     * 斜栏杆看的是<b>斜</b>邻居（下坡一格低一格、上坡一格高一格），而游戏只在<b>直</b>邻居变了时通知 —— 摆下 / 拆掉一格斜栏杆时，
     * 自己把斜上方与斜下方那两格重算一遍（结构放下去时游戏逐格重算，不靠这里）。
     */
    @Override
    protected void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
        super.onBlockAdded(state, world, pos, oldState, notify);
        if (kind == Kind.SLOPE && !world.isClient && !oldState.isOf(this)) {
            refreshDiagonals(world, pos, state.get(FACING));
        }
    }

    @Override
    protected void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        super.onStateReplaced(state, world, pos, newState, moved);
        if (kind == Kind.SLOPE && !world.isClient && !newState.isOf(this)) {
            refreshDiagonals(world, pos, state.get(FACING));
        }
    }

    private void refreshDiagonals(World world, BlockPos pos, Direction f) {
        for (BlockPos q : new BlockPos[]{pos.offset(f).up(), pos.offset(f.getOpposite()).down()}) {
            BlockState old = world.getBlockState(q);
            if (old.isOf(this)) {
                BlockState now = connect(old, world, q);
                if (now != old) {
                    world.setBlockState(q, now, Block.NOTIFY_ALL);
                }
            }
        }
    }

    private boolean sameSlope(BlockState other, Direction f) {
        return other.isOf(this) && other.get(FACING) == f;
    }

    private boolean sameTrim(BlockState other, Direction f) {
        return other.isOf(this) && other.get(FACING) == f;
    }

    /** 往 d 那边接不接（{@link Rules#joins}）。 */
    private static boolean joins(BlockView world, BlockPos pos, Direction d) {
        BlockState n = world.getBlockState(pos.offset(d));
        if (!(n.getBlock() instanceof LinerStairPiece p)) {
            return false;
        }
        return Rules.joins(p.kind, p.kind == Kind.SLOPE ? n.get(FACING) : null, p.kind == Kind.SLOPE ? n.get(SLOPE_PART) : null, d);
    }

    /** 起步灯：右键开关。别的件右键没有反应。 */
    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (kind != Kind.NEWEL_LAMP) {
            return ActionResult.PASS;
        }
        if (!world.isClient) {
            boolean lit = !state.get(LIT);
            world.setBlockState(pos, state.with(LIT, lit), Block.NOTIFY_ALL);
            world.playSound(null, pos, SoundEvents.BLOCK_STONE_BUTTON_CLICK_ON, SoundCategory.BLOCKS, 0.3f, lit ? 0.65f : 0.5f);
        }
        return ActionResult.success(world.isClient);
    }

    // ---------------------------------------------------------------- 结构转动与镜像

    @Override
    protected BlockState rotate(BlockState state, BlockRotation rotation) {
        return switch (kind) {
            case BALUSTRADE, NEWEL, NEWEL_LAMP -> state.with(side(rotation.rotate(Direction.NORTH)), state.get(NORTH))
                    .with(side(rotation.rotate(Direction.EAST)), state.get(EAST))
                    .with(side(rotation.rotate(Direction.SOUTH)), state.get(SOUTH))
                    .with(side(rotation.rotate(Direction.WEST)), state.get(WEST));
            case SLOPE, WELL_TRIM, FEATURE_POST, FEATURE_LINTEL, CLOCK -> state.with(FACING, rotation.rotate(state.get(FACING)));
        };
    }

    /** 镜像：四向照镜子换边；斜栏杆的上坡方向照游戏自带的做法转；收口的拐角左右对调（同檐口）。 */
    @Override
    protected BlockState mirror(BlockState state, BlockMirror mirror) {
        if (mirror == BlockMirror.NONE) {
            return state;
        }
        return switch (kind) {
            case BALUSTRADE, NEWEL, NEWEL_LAMP -> {
                BlockState s = state;
                for (Direction d : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
                    s = s.with(side(mirror.apply(d)), state.get(side(d)));
                }
                yield s;
            }
            case SLOPE -> state.rotate(mirror.getRotation(state.get(FACING)));
            case WELL_TRIM -> state.rotate(mirror.getRotation(state.get(FACING)))
                    .with(LinerBlocks.SHAPE, state.get(LinerBlocks.SHAPE).mirrored());
            case FEATURE_POST, CLOCK -> state.rotate(mirror.getRotation(state.get(FACING)));
            case FEATURE_LINTEL -> {                                  // 镜像左右对调：哪一头出端面跟着换（同顶帽）
                LinerBlocks.CappingEnd end = state.get(LinerBlocks.END);
                yield state.rotate(mirror.getRotation(state.get(FACING))).with(LinerBlocks.END,
                        end == LinerBlocks.CappingEnd.LEFT ? LinerBlocks.CappingEnd.RIGHT
                                : end == LinerBlocks.CappingEnd.RIGHT ? LinerBlocks.CappingEnd.LEFT : end);
            }
        };
    }

    static BooleanProperty side(Direction d) {
        return switch (d) {
            case NORTH -> NORTH;
            case EAST -> EAST;
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            default -> throw new IllegalArgumentException(d.toString());
        };
    }

    // ---------------------------------------------------------------- 轮廓与碰撞（照外形拼几块；栏杆与斜栏杆的碰撞高到 1.5 格，跳不过去）

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return outlines.computeIfAbsent(state, s -> shape(s, false));
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return collisions.computeIfAbsent(state, s -> shape(s, true));
    }

    private VoxelShape shape(BlockState s, boolean collision) {
        List<double[]> boxes = new ArrayList<>();
        int yaw = 0;
        switch (kind) {
            case BALUSTRADE, NEWEL, NEWEL_LAMP -> {
                double top = collision ? 24 : (kind == Kind.BALUSTRADE ? 16 : kind == Kind.NEWEL ? 24 : 24);
                boolean post = kind != Kind.BALUSTRADE;
                if (post) {
                    boxes.add(new double[]{4, 0, 4, 12, top, 12});
                } else {
                    boxes.add(new double[]{6.5, 0, 6.5, 9.5, top, 9.5});
                }
                double r = post ? 4 : 6.5;
                if (s.get(NORTH)) {
                    boxes.add(new double[]{6.5, 0, 0, 9.5, collision ? 24 : 16, r});
                }
                if (s.get(SOUTH)) {
                    boxes.add(new double[]{6.5, 0, 16 - r, 9.5, collision ? 24 : 16, 16});
                }
                if (s.get(WEST)) {
                    boxes.add(new double[]{0, 0, 6.5, r, collision ? 24 : 16, 9.5});
                }
                if (s.get(EAST)) {
                    boxes.add(new double[]{16 - r, 0, 6.5, 16, collision ? 24 : 16, 9.5});
                }
            }
            case SLOPE -> {
                // 朝北作画：每 4 像素一块，从格底到扶手顶（碰撞再高 8 像素，跳不过去）
                yaw = LinerProp.yawOf(s.get(FACING));
                SlopePart part = s.get(SLOPE_PART);
                for (int q = 0; q < 4; q++) {
                    double a1 = 4 * q + 4;
                    double top = Math.min(24, Rules.railTop(a1, part) + (collision ? 8 : 0));
                    boxes.add(new double[]{6.5, 0, 16 - a1, 9.5, Math.max(1, top), 16 - 4 * q});
                }
            }
            case FEATURE_POST -> {
                yaw = LinerBlock.yawOf(s.get(FACING));
                boxes.add(new double[]{4, 0, 0, 12, 16, 4});
            }
            case FEATURE_LINTEL -> {
                yaw = LinerBlock.yawOf(s.get(FACING));
                boxes.add(new double[]{0, 0, 0, 16, 14.5, 4.5});
            }
            case CLOCK -> {
                yaw = LinerBlock.yawOf(s.get(FACING));
                boxes.add(new double[]{1, 1, 0, 15, 15, 3});
            }
            case WELL_TRIM -> {
                // 照檐口朝南作画：贴着北边宿主前出 3 像素那一层（压边那一段高出楼面的部分不算进去，免得踩上去被绊）
                yaw = LinerBlock.yawOf(s.get(FACING));
                boxes.add(new double[]{0, 0, 0, 16, 16, 3});
                LinerBlocks.CornerShape cs = s.get(LinerBlocks.SHAPE);
                if (cs == LinerBlocks.CornerShape.INNER_LEFT) {
                    boxes.add(new double[]{0, 0, 3, 3, 16, 16});
                } else if (cs == LinerBlocks.CornerShape.INNER_RIGHT) {
                    boxes.add(new double[]{13, 0, 3, 16, 16, 16});
                }
            }
        }
        VoxelShape shape = VoxelShapes.empty();
        for (double[] b : boxes) {
            double[] lo = LinerLooks.rotateY(b[0], b[2], yaw);
            double[] hi = LinerLooks.rotateY(b[3], b[5], yaw);
            shape = VoxelShapes.union(shape, VoxelShapes.cuboid(Math.min(lo[0], hi[0]) / 16, b[1] / 16, Math.min(lo[1], hi[1]) / 16,
                    Math.max(lo[0], hi[0]) / 16, b[4] / 16, Math.max(lo[1], hi[1]) / 16));
        }
        return shape.simplify();
    }

    // ---------------------------------------------------------------- 纯规则

    /** 不碰世界的那一半：单测直接测这里（{@code LinerStairRulesTest}）。 */
    public static final class Rules {

        private Rules() {
        }

        /**
         * 平台栏杆 · 起步柱往 {@code toward} 那边伸不伸一臂：那一格是平台栏杆或起步柱 → 接；
         * 是斜栏杆、而且是顶段（或单独一格）、上坡方向正对着这一格（这一格在它的上坡那一头）→ 接（它放平的那一头在格边上等着）；
         * 别的（墙、楼梯、斜栏杆的中段与底段、收口）都不接。
         */
        public static boolean joins(Kind neighbor, Direction neighborFacing, SlopePart neighborPart, Direction toward) {
            return switch (neighbor) {
                case BALUSTRADE, NEWEL, NEWEL_LAMP -> true;
                case SLOPE -> (neighborPart == SlopePart.TOP || neighborPart == SlopePart.SINGLE) && neighborFacing == toward.getOpposite();
                default -> false;
            };
        }

        /** 斜栏杆是哪一段：斜下方（下坡一格、低一格）与斜上方（上坡一格、高一格）是不是同朝向的斜栏杆。 */
        public static SlopePart slopePart(boolean lowerSame, boolean upperSame) {
            if (lowerSame && upperSame) {
                return SlopePart.MIDDLE;
            }
            if (upperSame) {
                return SlopePart.BOTTOM;
            }
            return lowerSame ? SlopePart.TOP : SlopePart.SINGLE;
        }

        /** 收口是上下叠着的哪一段：正上方 / 正下方是不是同朝向的收口。 */
        public static TrimPart trimPart(boolean above, boolean below) {
            if (above && below) {
                return TrimPart.MIDDLE;
            }
            if (below) {
                return TrimPart.TOP;
            }
            return above ? TrimPart.BOTTOM : TrimPart.SINGLE;
        }

        /** 四向接的那几边拼成模板名的后缀（n · e · s · w 的次序；一边都不接 = {@code none}，与 {@code liner_stairs.py} 的 MASKS 一致）。 */
        public static String mask(boolean north, boolean east, boolean south, boolean west) {
            StringBuilder b = new StringBuilder();
            if (north) {
                b.append('n');
            }
            if (east) {
                b.append('e');
            }
            if (south) {
                b.append('s');
            }
            if (west) {
                b.append('w');
            }
            return b.isEmpty() ? "none" : b.toString();
        }

        /** 斜栏杆在 a 处（顺着上坡量，0–16 像素）的扶手顶，斜栏杆那一格的坐标（与 {@code liner_stairs.py} 的 rail_y 同一套数）。 */
        static double railTop(double a, SlopePart part) {
            double k = 6.5;
            double flat = 14.5;
            double a1 = 6.757;
            double a2 = 9.757;
            double c;
            if ((part == SlopePart.TOP || part == SlopePart.SINGLE) && a > a1) {
                c = a >= a2 ? flat : k + a1 + (a - a1) * Math.tan(Math.toRadians(22.5));
            } else {
                c = a + k;
            }
            return c + 1.5;
        }
    }
}
