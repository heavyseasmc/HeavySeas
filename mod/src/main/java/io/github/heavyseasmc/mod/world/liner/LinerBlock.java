package io.github.heavyseasmc.mod.world.liner;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.Property;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldAccess;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.AXIS;
import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.CELL;
import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.DOWN;
import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.EAST;
import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.END;
import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.FACING;
import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.LEFT;
import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.NORTH;
import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.PART;
import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.RIGHT;
import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.SIDE;
import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.SOUTH;
import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.UP;
import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.WEST;

/**
 * 大邮轮的一件装饰方块（ADR-0062）。一个类管全部：每种方块有自己的一组属性、一种摆法（{@link Kind}），
 * 以及「这个状态长什么样」的函数 —— 与救生艇那一族同一个做法：同一个函数既算轮廓，又让批量生成工具写方块状态文件。
 *
 * <p>大框、护墙、顶帽、地毯是「看邻居」的：放下时与邻居变了时各按 {@link LinerConnect.Rules} 重算一次自己画哪张。
 *
 * <p>❗属性只能在构造时经 {@link #PENDING} 传进来：{@code appendProperties} 在 {@code Block} 的构造器里就被调用。
 */
public final class LinerBlock extends Block {

    /** 摆法：放下时朝哪、看哪几个邻居。 */
    public enum Kind {
        /** 六面同图，没有朝向。 */
        PLAIN,
        /** 有正面的整块：正面朝着摆它的人。 */
        FRONT,
        /** 大框：正面朝着摆它的人，上下左右接同一种、同一朝向的框。 */
        FRAME,
        /** 护墙：正面朝着摆它的人，看正上方那块框。 */
        WAINSCOT,
        /** 挂在墙上的件（腰线、檐口、壁柱）：贴在点中的那一面上，正面朝外。 */
        WALL_PIECE,
        /** 木壁柱：同上，再按点在那一面的左半还是右半定靠哪边。 */
        PILASTER_SIDE,
        /** 顶帽：同挂墙的件，两头看左右是不是同一朝向的顶帽。 */
        CAPPING,
        /** 甲板：板顺着摆它的人面朝的方向。 */
        DECK,
        /** 地毯：四边接不接地毯、花纹第几格。 */
        CARPET
    }

    private static final ThreadLocal<Property<?>[]> PENDING = new ThreadLocal<>();

    private final Kind kind;
    private final Function<BlockState, LinerLooks.Look> look;
    private final Map<BlockState, VoxelShape> shapes = new ConcurrentHashMap<>();

    static LinerBlock create(AbstractBlock.Settings settings, Kind kind, Function<BlockState, LinerLooks.Look> look,
                             Property<?>... properties) {
        PENDING.set(properties);
        try {
            return new LinerBlock(settings, kind, look);
        } finally {
            PENDING.remove();
        }
    }

    private LinerBlock(AbstractBlock.Settings settings, Kind kind, Function<BlockState, LinerLooks.Look> look) {
        super(settings);
        this.kind = kind;
        this.look = look;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(PENDING.get());
    }

    void defaultTo(BlockState state) {
        setDefaultState(state);
    }

    public Kind kind() {
        return kind;
    }

    /** 这个状态的样子，朝向已经算进 y 旋转里（模板一律正面朝南作画）。 */
    public LinerLooks.Look look(BlockState state) {
        LinerLooks.Look base = look.apply(state);
        return state.contains(FACING) ? base.turned(yawOf(state.get(FACING))) : base;
    }

    /** 朝向 → y 旋转：模板朝南；Minecraft 的 y 旋转从上往下看是顺时针（北 → 东 → 南 → 西）。 */
    static int yawOf(Direction facing) {
        return switch (facing) {
            case WEST -> 90;
            case NORTH -> 180;
            case EAST -> 270;
            default -> 0;
        };
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        BlockState s = getDefaultState();
        Direction toPlayer = ctx.getHorizontalPlayerFacing().getOpposite();
        if (s.contains(FACING)) {
            boolean onWall = kind == Kind.WALL_PIECE || kind == Kind.PILASTER_SIDE || kind == Kind.CAPPING;
            s = s.with(FACING, onWall && ctx.getSide().getAxis().isHorizontal() ? ctx.getSide() : toPlayer);
        }
        if (s.contains(AXIS)) {
            s = s.with(AXIS, ctx.getHorizontalPlayerFacing().getAxis());
        }
        if (kind == Kind.PILASTER_SIDE) {
            Direction left = LinerConnect.viewerLeft(s.get(FACING));
            Vec3d hit = ctx.getHitPos();
            BlockPos p = ctx.getBlockPos();
            double along = (hit.x - p.getX() - 0.5) * left.getOffsetX() + (hit.z - p.getZ() - 0.5) * left.getOffsetZ();
            s = s.with(SIDE, along > 0 ? LinerBlocks.PilasterSide.LEFT : LinerBlocks.PilasterSide.RIGHT);
        }
        return connect(s, ctx.getWorld(), ctx.getBlockPos());
    }

    @Override
    protected BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                   WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        return connect(state, world, pos);
    }

    /** 按邻居重算「看邻居」的那几样；别的方块原样返回。 */
    BlockState connect(BlockState s, BlockView world, BlockPos pos) {
        switch (kind) {
            case FRAME -> {
                Direction f = s.get(FACING);
                return s.with(UP, same(world, pos.up(), f)).with(DOWN, same(world, pos.down(), f))
                        .with(LEFT, same(world, pos.offset(LinerConnect.viewerLeft(f)), f))
                        .with(RIGHT, same(world, pos.offset(LinerConnect.viewerRight(f)), f));
            }
            case WAINSCOT -> {
                Direction f = s.get(FACING);
                BlockState above = world.getBlockState(pos.up());
                boolean frame = above.getBlock() instanceof LinerBlock b && b.kind == Kind.FRAME && above.get(FACING) == f;
                String part = LinerConnect.Rules.wainscotPart(frame, frame && above.get(LEFT), frame && above.get(RIGHT));
                return s.with(PART, LinerBlocks.WainscotPart.of(part));
            }
            case CAPPING -> {
                Direction f = s.get(FACING);
                String end = LinerConnect.Rules.cappingEnd(same(world, pos.offset(LinerConnect.viewerLeft(f)), f),
                        same(world, pos.offset(LinerConnect.viewerRight(f)), f));
                return s.with(END, LinerBlocks.CappingEnd.of(end));
            }
            case CARPET -> {
                return s.with(NORTH, isThis(world, pos.north())).with(EAST, isThis(world, pos.east()))
                        .with(SOUTH, isThis(world, pos.south())).with(WEST, isThis(world, pos.west()))
                        .with(CELL, LinerConnect.Rules.cell(pos.getX(), pos.getZ(), LinerConnect.CARPET_PERIOD));
            }
            default -> {
                return s;
            }
        }
    }

    /** 那一格是同一种方块、同一朝向。 */
    private boolean same(BlockView world, BlockPos pos, Direction facing) {
        BlockState other = world.getBlockState(pos);
        return other.isOf(this) && other.get(FACING) == facing;
    }

    private boolean isThis(BlockView world, BlockPos pos) {
        return world.getBlockState(pos).isOf(this);
    }

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return shapes.computeIfAbsent(state, s -> LinerLooks.shapeOf(look(s)));
    }

    /**
     * 结构整体旋转时转朝向与地毯的四边；左右、两头、花纹第几格不在这里换 —— 放下之后邻居一更新就按位置重算
     * （「放下以后连不连」留给游戏内实测，ADR-0062 §3 步骤 5）。
     */
    @Override
    protected BlockState rotate(BlockState state, BlockRotation rotation) {
        BlockState s = state;
        if (s.contains(FACING)) {
            s = s.with(FACING, rotation.rotate(s.get(FACING)));
        }
        if (s.contains(NORTH)) {
            s = s.with(LinerBlocks.side(rotation.rotate(Direction.NORTH)), state.get(NORTH))
                    .with(LinerBlocks.side(rotation.rotate(Direction.EAST)), state.get(EAST))
                    .with(LinerBlocks.side(rotation.rotate(Direction.SOUTH)), state.get(SOUTH))
                    .with(LinerBlocks.side(rotation.rotate(Direction.WEST)), state.get(WEST));
        }
        return s;
    }

    @Override
    protected BlockState mirror(BlockState state, BlockMirror mirror) {
        return state.contains(FACING) ? state.rotate(mirror.getRotation(state.get(FACING))) : state;
    }
}
