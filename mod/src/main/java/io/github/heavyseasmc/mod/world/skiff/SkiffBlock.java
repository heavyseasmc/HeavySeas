package io.github.heavyseasmc.mod.world.skiff;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.util.math.Direction;
import net.minecraft.world.WorldAccess;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 救生艇上的一件装饰方块（ADR-0053 · ADR-0056 · ADR-0057）。
 *
 * <p>一个类管全部 26 种：每种方块有自己的一组属性，以及「这个状态长什么样」的函数（{@link SkiffLooks.Look}）——
 * 用哪块模板模型、贴哪几张图、方块状态里转多少度。同一个函数两处用：这里按它算轮廓形状，
 * 批量生成工具（datagen）按它写方块状态文件，所以「看上去的样子」与「点得中的形状」不会各说各的。
 *
 * <p>❗属性只能在构造时经 {@link #PENDING} 传进来：{@code appendProperties} 在 {@code Block} 的构造器里就被调用，
 * 那时子类的字段还没赋值。
 */
public final class SkiffBlock extends Block {

    private static final ThreadLocal<Property<?>[]> PENDING = new ThreadLocal<>();

    private final Function<BlockState, SkiffLooks.Look> look;
    private final Map<BlockState, VoxelShape> shapes = new ConcurrentHashMap<>();

    static SkiffBlock create(AbstractBlock.Settings settings, Function<BlockState, SkiffLooks.Look> look,
                                Property<?>... properties) {
        PENDING.set(properties);
        try {
            return new SkiffBlock(settings, look);
        } finally {
            PENDING.remove();
        }
    }

    private SkiffBlock(AbstractBlock.Settings settings, Function<BlockState, SkiffLooks.Look> look) {
        super(settings);
        this.look = look;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(PENDING.get());
    }

    void defaultTo(BlockState state) {
        setDefaultState(state);
    }

    /** 这个状态的样子，朝向已经算进 y 旋转里（模板一律按「船头朝南」作画）。 */
    public SkiffLooks.Look look(BlockState state) {
        SkiffLooks.Look base = look.apply(state);
        int yaw = state.contains(Properties.HORIZONTAL_FACING)
                ? SkiffLooks.yawOf(state.get(Properties.HORIZONTAL_FACING)) : 0;
        return base.turned(yaw);
    }

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return shapes.computeIfAbsent(state, s -> SkiffLooks.shapeOf(look(s)));
    }

    /** 带水的那一格里是静水：水不会把它冲掉，周围的水也照常画（不留一个干的空格）。 */
    @Override
    protected FluidState getFluidState(BlockState state) {
        return state.contains(SkiffBlocks.WATERLOGGED) && state.get(SkiffBlocks.WATERLOGGED)
                ? Fluids.WATER.getStill(false) : super.getFluidState(state);
    }

    @Override
    protected BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                   WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        if (state.contains(SkiffBlocks.WATERLOGGED) && state.get(SkiffBlocks.WATERLOGGED)) {
            world.scheduleFluidTick(pos, Fluids.WATER, Fluids.WATER.getTickRate(world));
        }
        return super.getStateForNeighborUpdate(state, direction, neighborState, world, pos, neighborPos);
    }

    /** 灯 = 添油，桅杆 = 升 / 降帆（ADR-0057 §4）；其余方块不响应。 */
    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        return SkiffProps.use(this, state, world, pos, player);
    }

    /** 划过的桨、偏过的舵到点回位。 */
    @Override
    protected void scheduledTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
        SkiffProps.settle(state, world, pos);
    }

    @Override
    protected BlockState rotate(BlockState state, BlockRotation rotation) {
        return state.contains(Properties.HORIZONTAL_FACING)
                ? state.with(Properties.HORIZONTAL_FACING, rotation.rotate(state.get(Properties.HORIZONTAL_FACING)))
                : state;
    }

    /** 只转朝向。镜像不会把左舷换成右舷 —— 船体放置从不镜像（{@code Hull#place} 写死 {@code BlockMirror.NONE}）。 */
    @Override
    protected BlockState mirror(BlockState state, BlockMirror mirror) {
        return state.contains(Properties.HORIZONTAL_FACING)
                ? state.rotate(mirror.getRotation(state.get(Properties.HORIZONTAL_FACING)))
                : state;
    }
}
