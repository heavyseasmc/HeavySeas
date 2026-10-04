package io.github.heavyseasmc.mod.world.liner;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.Property;
import net.minecraft.util.ActionResult;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Hand;
import net.minecraft.util.ItemActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.ARCH_PART;
import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.AXIS;
import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.BASE;
import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.CASING_PART;
import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.CELL;
import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.CORNER;
import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.DOWN;
import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.EAST;
import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.END;
import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.FACING;
import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.IVY;
import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.LAYOUT;
import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.LEFT;
import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.NORTH;
import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.PART;
import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.RIGHT;
import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.ROW;
import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.SHAPE;
import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.SIDE;
import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.SOUTH;
import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.TRIM;
import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.UP;
import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.V;
import static io.github.heavyseasmc.mod.world.liner.LinerBlocks.WEST;

/**
 * 大邮轮的一件装饰方块（ADR-0062）。一个类管全部：每种方块有自己的一组属性、一种摆法（{@link Kind}），
 * 以及「这个状态长什么样」的函数 —— 与救生艇那一族同一个做法：同一个函数既算轮廓，又让批量生成工具写方块状态文件。
 *
 * <p>大框、护墙、顶帽、地毯、檐口 / 腰线 / 踢脚的拐角、壁柱的柱脚、门套顺带的线是「看邻居」的：放下时与邻居变了时
 * 各按 {@link LinerConnect.Rules} 重算一次自己画哪张。
 *
 * <p>挂墙的那几种（{@link #onWall}）：各占宿主前面一格、宿主拆了不掉（ADR-0069 §4 倾向 A）；点中它们放新件时不往外悬空，
 * 右键转给身后的宿主。
 *
 * <p>❗属性只能在构造时经 {@link #PENDING} 传进来：{@code appendProperties} 在 {@code Block} 的构造器里就被调用。
 */
public final class LinerBlock extends Block implements LinerLooks.Styled {

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
        /** 挂在墙上的件（白漆壁柱）：贴在点中的那一面上，正面朝外。 */
        WALL_PIECE,
        /** 沿墙走的线（腰线、檐口）：同上，再按前后的同种件拐内角 / 外角（{@link LinerConnect.Rules#cornerShape}）。 */
        RUN,
        /** 木壁柱：同上，再按点在那一面的左半还是右半定靠哪边。 */
        PILASTER_SIDE,
        /** 顶帽：同挂墙的件，两头看左右是不是同一朝向的顶帽。 */
        CAPPING,
        /**
         * 门套（ADR-0069 §2 ②）：同挂墙的件；点在那一格墙面的哪儿就是哪一块（{@link LinerConnect.Rules#casingPart}），
         * 再看左右邻居顺带画踢脚 / 腰线 / 薄檐口（{@link LinerConnect.Rules#casingTrim}）。
         */
        CASING,
        /** 甲板：板顺着摆它的人面朝的方向。 */
        DECK,
        /** 地毯：四边接不接地毯、花纹第几格。 */
        CARPET,
        /**
         * 格架（ADR-0080 §7）：同挂墙的件；框条看上下左右（同大框）· 屋角补旁边那面墙（{@link LinerConnect.Rules#trellisCorner}）·
         * 常春藤中段 / 藤梢看上面那一格、布局与拐法按位置算。常春藤是叠上去的另一层（{@link #layers}）。
         */
        TRELLIS,
        /** 拱（ADR-0080 §7）：墙那一格本身，正面朝着摆它的人；一排里的哪一格 · 上下哪一排看邻居（{@link LinerConnect.Rules#archPart}）。 */
        ARCH
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
    @Override
    public LinerLooks.Look look(BlockState state) {
        LinerLooks.Look base = look.apply(state);
        return state.contains(FACING) ? base.turned(yawOf(state.get(FACING))) : base;
    }

    /** 格架上的常春藤是叠上去的一层（多部件模型）：只看朝向与藤的三个属性；底层（框条 · 内角）不看藤。 */
    @Override
    public java.util.List<LinerLooks.Layer> layers() {
        if (kind != Kind.TRELLIS) {
            return java.util.List.of();
        }
        return java.util.List.of(new LinerLooks.Layer(java.util.List.of(FACING, IVY, LAYOUT, V), s -> {
            LinerLooks.Look ivy = LinerBlocks.ivyLook(s);
            return ivy == null ? null : ivy.turned(yawOf(s.get(FACING)));
        }));
    }

    @Override
    public java.util.List<Property<?>> baseProperties() {
        return kind == Kind.TRELLIS ? java.util.List.of(FACING, UP, DOWN, LEFT, RIGHT, CORNER) : java.util.List.of();
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

    /** 挂在墙上的那几种（宿主前面那一格、正面朝外）。 */
    static boolean onWall(Kind kind) {
        return kind == Kind.WALL_PIECE || kind == Kind.RUN || kind == Kind.PILASTER_SIDE || kind == Kind.CAPPING || kind == Kind.CASING
                || kind == Kind.TRELLIS;
    }

    /** 这个状态若是我们的挂墙件，回它的朝向；不是回 {@code null}。 */
    static Direction wallFacing(BlockState state) {
        return state.getBlock() instanceof LinerBlock b && onWall(b.kind) && state.contains(FACING) ? state.get(FACING) : null;
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        BlockState s = getDefaultState();
        Direction toPlayer = ctx.getHorizontalPlayerFacing().getOpposite();
        if (s.contains(FACING)) {
            Direction facing = toPlayer;
            if (onWall(kind)) {
                facing = ctx.getSide().getAxis().isHorizontal() ? ctx.getSide() : toPlayer;
                // 点中的是我们自己的挂墙件（ADR-0069 §4）：点它的正面时，游戏会把新件放到它再往外一格 —— 悬空离墙，不放；
                //   点它的侧面 / 顶面 / 底面时，新件放在它旁边那一格，跟着它贴同一面墙（照它的朝向，不照点中的那一面）
                if (!ctx.canReplaceExisting()) {
                    Direction stuck = wallFacing(ctx.getWorld().getBlockState(ctx.getBlockPos().offset(ctx.getSide().getOpposite())));
                    if (stuck != null) {
                        if (ctx.getSide() == stuck) {
                            return null;
                        }
                        facing = stuck;
                    }
                }
            }
            s = s.with(FACING, facing);
        }
        if (s.contains(AXIS)) {
            s = s.with(AXIS, ctx.getHorizontalPlayerFacing().getAxis());
        }
        if (kind == Kind.PILASTER_SIDE || kind == Kind.CASING) {
            // 点在这一格墙面上的哪儿：u 从站在正面看的人的左手 0 到右手 1，v 从下 0 到上 1
            Direction right = LinerConnect.viewerRight(s.get(FACING));
            Vec3d hit = ctx.getHitPos();
            BlockPos p = ctx.getBlockPos();
            double u = (hit.x - p.getX() - 0.5) * right.getOffsetX() + (hit.z - p.getZ() - 0.5) * right.getOffsetZ() + 0.5;
            double v = hit.y - p.getY();
            if (kind == Kind.PILASTER_SIDE) {
                s = s.with(SIDE, u < 0.5 ? LinerBlocks.PilasterSide.LEFT : LinerBlocks.PilasterSide.RIGHT);
            } else {
                s = s.with(CASING_PART, LinerBlocks.CasingPart.of(LinerConnect.Rules.casingPart(u, v)));
            }
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
            case RUN -> {
                int f = LinerConnect.index(s.get(FACING));
                String shape = LinerConnect.Rules.cornerShape(f, d -> {
                    BlockState other = world.getBlockState(pos.offset(LinerConnect.direction(d)));
                    return other.isOf(this) ? LinerConnect.index(other.get(FACING)) : null;
                });
                return s.with(SHAPE, LinerBlocks.CornerShape.of(shape));
            }
            case CARPET -> {
                // 两版地毯（底面木色 · 底面白平顶）互相接：毯边只画在不挨着地毯的那几边
                return s.with(NORTH, isCarpet(world, pos.north())).with(EAST, isCarpet(world, pos.east()))
                        .with(SOUTH, isCarpet(world, pos.south())).with(WEST, isCarpet(world, pos.west()))
                        .with(CELL, LinerConnect.Rules.cell(pos.getX(), pos.getZ(), LinerConnect.CARPET_PERIOD));
            }
            case WALL_PIECE, PILASTER_SIDE -> {
                if (!s.contains(BASE)) {
                    return s;
                }
                Direction f = s.get(FACING);
                BlockState below = world.getBlockState(pos.down());
                boolean bottom = !(below.isOf(this) && below.get(FACING) == f && (!s.contains(SIDE) || below.get(SIDE) == s.get(SIDE)));
                String base = LinerConnect.Rules.pilasterBase(bottom,
                        isSkirting(world.getBlockState(pos.offset(LinerConnect.viewerLeft(f))), f),
                        isSkirting(world.getBlockState(pos.offset(LinerConnect.viewerRight(f))), f));
                return s.with(BASE, LinerBlocks.PilasterBase.of(base));
            }
            case CASING -> {
                Direction f = s.get(FACING);
                String trim = LinerConnect.Rules.casingTrim(s.get(CASING_PART).asString(),
                        runBeside(world, pos, f, LinerConnect.viewerLeft(f)), runBeside(world, pos, f, LinerConnect.viewerRight(f)));
                return s.with(TRIM, LinerBlocks.Trim.of(trim));
            }
            case TRELLIS -> {
                // ① 内角看正前方那一格 ② 框条看上下左右（左右那一格是补到我这面墙上的内角也算接着）③ 藤：中段 / 藤梢看上面那一格，布局与拐法按位置
                Direction f = s.get(FACING);
                int fi = LinerConnect.index(f);
                BlockState front = world.getBlockState(pos.offset(f));
                String corner = LinerConnect.Rules.trellisCorner(fi, front.isOf(this) ? LinerConnect.index(front.get(FACING)) : null);
                BlockState above = world.getBlockState(pos.up());
                boolean ivyAbove = above.isOf(this) && above.get(FACING) == f && above.get(IVY) != LinerBlocks.Ivy.NONE;
                String ivy = LinerConnect.Rules.ivyState(s.get(IVY) != LinerBlocks.Ivy.NONE, ivyAbove);
                return s.with(CORNER, LinerBlocks.TrellisCorner.of(corner))
                        .with(UP, trellisJoins(world, pos.up(), fi, false)).with(DOWN, trellisJoins(world, pos.down(), fi, false))
                        .with(LEFT, trellisJoins(world, pos.offset(LinerConnect.viewerLeft(f)), fi, true))
                        .with(RIGHT, trellisJoins(world, pos.offset(LinerConnect.viewerRight(f)), fi, true))
                        .with(IVY, LinerBlocks.Ivy.of(ivy))
                        .with(LAYOUT, LinerBlocks.IvyLayout.of(LinerConnect.Rules.ivyLayout(pos.getX(), pos.getZ())))
                        .with(V, LinerConnect.Rules.ivyVariant(pos.getX(), pos.getY(), pos.getZ()));
            }
            case ARCH -> {
                Direction f = s.get(FACING);
                String part = LinerConnect.Rules.archPart(same(world, pos.offset(LinerConnect.viewerLeft(f)), f),
                        same(world, pos.offset(LinerConnect.viewerRight(f)), f));
                return s.with(LinerBlocks.ARCH_PART, LinerBlocks.ArchPart.of(part))
                        .with(ROW, LinerBlocks.ArchRow.of(LinerConnect.Rules.archRow(same(world, pos.up(), f))));
            }
            default -> {
                return s;
            }
        }
    }

    /** 格架的这一边接不接着（规则在 {@link LinerConnect.Rules#trellisJoins}）：只认同一种方块（格架）。 */
    private boolean trellisJoins(BlockView world, BlockPos q, int facing, boolean sideways) {
        BlockState o = world.getBlockState(q);
        return o.isOf(this) && LinerConnect.Rules.trellisJoins(facing, LinerConnect.index(o.get(FACING)), o.get(CORNER).asString(), sideways);
    }

    /** 从 pos 往 side 那一边沿墙找那条线（规则在 {@link LinerConnect.Rules#runBeside}）。 */
    private static String runBeside(BlockView world, BlockPos pos, Direction facing, Direction side) {
        return LinerConnect.Rules.runBeside(i -> lineKind(world.getBlockState(pos.offset(side, i)), facing));
    }

    /** 沿墙那一格对门套来说是什么：同一朝向的挂墙件交给 {@link LinerConnect.Rules#lineKind}，别的（墙、空气、朝向不同的件）= {@code null}。 */
    static String lineKind(BlockState o, Direction facing) {
        if (wallFacing(o) != facing) {
            return null;
        }
        Block b = o.getBlock();
        String block = b == LinerBlocks.SKIRTING ? "liner_skirting" : b == LinerBlocks.CHAIR_RAIL ? "liner_chair_rail"
                : b == LinerBlocks.CORNICE_THIN ? "liner_cornice_thin" : b == LinerBlocks.DOOR_CASING ? "liner_door_casing"
                : b == LinerBlocks.DOOR_CASING_TALL ? "liner_door_casing_tall" : "other";
        return LinerConnect.Rules.lineKind(block, o.contains(BASE) ? o.get(BASE).asString() : null);
    }

    private static boolean isSkirting(BlockState state, Direction facing) {
        return state.isOf(LinerBlocks.SKIRTING) && state.get(FACING) == facing;
    }

    private static boolean isCarpet(BlockView world, BlockPos pos) {
        return world.getBlockState(pos).getBlock() instanceof LinerBlock b && b.kind == Kind.CARPET;
    }

    /** 那一格是同一种方块、同一朝向。 */
    private boolean same(BlockView world, BlockPos pos, Direction facing) {
        BlockState other = world.getBlockState(pos);
        return other.isOf(this) && other.get(FACING) == facing;
    }

    /**
     * 右键转交给身后的宿主（ADR-0069 §4，学列车模组的做法）：挂墙件贴在门、箱子这类点了会动的东西前面时，不妨碍点它。
     * 身后是空气、或也是挂墙件时不转（两件背对背挂在一块薄墙两边时，免得来回转）。
     */
    private BlockPos hostOf(BlockState state, BlockPos pos, BlockView world) {
        Direction f = wallFacing(state);
        if (f == null) {
            return null;
        }
        BlockPos host = pos.offset(f.getOpposite());
        BlockState h = world.getBlockState(host);
        return h.isAir() || wallFacing(h) != null ? null : host;
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        BlockPos host = hostOf(state, pos, world);
        return host == null ? super.onUse(state, world, pos, player, hit)
                : world.getBlockState(host).onUse(world, player, hit.withBlockPos(host));
    }

    @Override
    protected ItemActionResult onUseWithItem(ItemStack stack, BlockState state, World world, BlockPos pos, PlayerEntity player,
                                             Hand hand, BlockHitResult hit) {
        BlockPos host = hostOf(state, pos, world);
        return host == null ? super.onUseWithItem(stack, state, world, pos, player, hand, hit)
                : world.getBlockState(host).onUseWithItem(stack, world, player, hand, hit.withBlockPos(host));
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

    /**
     * 镜像：朝向照游戏自带的做法转；镜像还会把左右对调（转不会）—— 大框左右的接缝、木壁柱靠哪边、顶帽哪一头、
     * 拐角的左右、地毯的四边都跟着换。看邻居的那几样放下去之后会重算，靠哪边的木壁柱不会，第一版漏了它。
     */
    @Override
    protected BlockState mirror(BlockState state, BlockMirror mirror) {
        if (mirror == BlockMirror.NONE) {
            return state;
        }
        BlockState s = state.contains(FACING) ? state.rotate(mirror.getRotation(state.get(FACING))) : state;
        if (s.contains(LEFT)) {
            s = s.with(LEFT, state.get(RIGHT)).with(RIGHT, state.get(LEFT));
        }
        if (s.contains(SIDE)) {
            s = s.with(SIDE, state.get(SIDE) == LinerBlocks.PilasterSide.LEFT ? LinerBlocks.PilasterSide.RIGHT
                    : LinerBlocks.PilasterSide.LEFT);
        }
        if (s.contains(END)) {
            LinerBlocks.CappingEnd end = state.get(END);
            s = s.with(END, end == LinerBlocks.CappingEnd.LEFT ? LinerBlocks.CappingEnd.RIGHT
                    : end == LinerBlocks.CappingEnd.RIGHT ? LinerBlocks.CappingEnd.LEFT : end);
        }
        if (s.contains(SHAPE)) {
            s = s.with(SHAPE, state.get(SHAPE).mirrored());
        }
        if (s.contains(CASING_PART)) {
            s = s.with(CASING_PART, state.get(CASING_PART).mirrored());
        }
        if (s.contains(CORNER)) {
            s = s.with(CORNER, state.get(CORNER).mirrored());
        }
        if (s.contains(ARCH_PART)) {
            s = s.with(ARCH_PART, state.get(ARCH_PART).mirrored());
        }
        if (s.contains(NORTH)) {
            for (Direction d : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
                s = s.with(LinerBlocks.side(mirror.apply(d)), state.get(LinerBlocks.side(d)));
            }
        }
        return s;
    }
}
