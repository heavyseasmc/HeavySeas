package io.github.heavyseasmc.mod.world.liner;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.net.LiftFloorsS2C;
import io.github.heavyseasmc.mod.net.LiftRideS2C;
import io.github.heavyseasmc.mod.net.LiftTravelC2S;
import io.github.heavyseasmc.mod.state.GameComponents;
import io.github.heavyseasmc.mod.ui.LiftPanel;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.MapColor;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.pathing.NavigationType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.TallBlockItem;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;

import static io.github.heavyseasmc.mod.world.liner.LinerLooks.tex;

/**
 * 北辰号的电梯（用户 2026-10-14：从 E 甲板通到艇甲板；同一天看过第一版之后「太小气了，做真实一点，多安排几部」「要有交互感」）。
 *
 * <h2>一部电梯是什么</h2>
 * 同一条井道上每层一组：<b>两扇宽的栅门</b>（{@link #GATE}，左右两块、各两格高）+ 门后 2 × 2、三格高的轿厢（生成器摆护墙、顶灯、长凳）
 * + 门正上方的<b>楼层指针盘</b>（{@link #DIAL}，两块宽）+ 电梯厅墙上的<b>召唤按钮</b>（{@link #CALL}）+ 轿厢里的<b>按钮面板</b>（{@link #PANEL}）。
 * 「井道」= 两扇门占的那两格（{@link Rules#shaftOf}）；楼层表 = 这两格从下往上每一个左扇下半块（且它的右扇落在另一格）。
 * 不按「左扇那一列」认：门朝向相反的一层（艇甲板那一站开进穹顶厅，门朝船尾）左右扇对调，左扇就落在另一格 —— 按列认，
 * 那一站要么错开一格、要么成了另一部（ADR-0105，用户「只留两部吧，不然都不对称」）。
 *
 * <h2>没有真的轿厢，只有一个「轿厢在第几层」</h2>
 * 每层的轿厢都是静止的一间小屋；服务端为每部电梯记一个虚拟的轿厢位置（{@link Car}）：停在哪一层、开没开门、正在去哪一层。
 * <ul>
 *   <li><b>叫车</b>：按召唤按钮（或直接右键栅门）→ 轿厢不在这一层就「开过来」：每过一层 {@link #TICKS_PER_FLOOR} tick，
 *       这一部每一层的指针盘都跟着走一格；到了这一层栅门打开（叠到两侧，人走得进去）。按钮按下后亮着，到了熄灭。</li>
 *   <li><b>乘梯</b>：站进轿厢、右键按钮面板 → 楼层面板 → 选一层 → 这一层栅门合上，轿厢里（栅门那一排 + 背后 2 × 2，三格高）的所有人
 *       一起「走」：客户端收到 {@link LiftRideS2C} 渐暗、轻晃；服务端按每层 {@link #TICKS_PER_FLOOR} tick 走完、指针一路跟着，
 *       然后把每个人送到目标层轿厢里<b>同一个相对位置、同一个朝向</b>，目标层栅门打开，快捷栏上方报「已到 X」。</li>
 *   <li><b>门自己合上</b>：开着 {@link #DOOR_HOLD_TICKS} tick 没人站在轿厢里或门洞里就合上；别的层有人叫车时，门开满
 *       {@link #MIN_OPEN_TICKS} tick、里面没人就先合上去接。</li>
 * </ul>
 * 虚拟轿厢不存档：一部电梯第一次被碰到时（起服后、或换了个世界）从它最低那一层的指针盘读出「在第几层」（指针盘是方块状态，跟着存档走），
 * 读不到就当在最低一层；同时把这一部每一层的栅门合上、指针拨到这一层、召唤按钮熄掉 —— 停服时开着的门与亮着的按钮不留到下一次。
 *
 * <h2>思路借了谁、哪里故意不同</h2>
 * 借机械动力的电梯三处思路（只学交互，没有看它的贴图与模型，也没有抄它的代码）：<b>同一列就是同一部电梯</b>、
 * <b>楼层表按 y 从低到高排、没起名就按次序编号</b>、<b>客户端只发「去第几层」，服务端核对那一层还在这条井道里</b>。
 * 故意不同：它真的移动轿厢（连人带方块一起搬），这里不搬 —— 每层一间静止的轿厢 + 一个虚拟的轿厢位置，
 * 「动」由指针盘、栅门开合、客户端渐暗轻晃与到站报一句来表现。
 *
 * <h2>声音</h2>
 * 一律不用游戏自带的音效（用户定），本轮不加；留了五个接口点（开门 · 关门 · 出发 · 到站铃 · 指针每走一格），搜「声音接口点」。
 *
 * <p>模板与贴图（{@code template/lift/*} · {@code textures/block/liner/lift/*}）眼下由模组侧的一支生成脚本出，
 * 以后并进 {@code liner_hull.py}（门那一族）。
 */
public final class LinerLift {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final EnumProperty<DoubleBlockHalf> HALF = Properties.DOUBLE_BLOCK_HALF;
    public static final EnumProperty<Deck> DECK = EnumProperty.of("deck", Deck.class);
    /** 从电梯厅看过去的左右（栅门与指针盘都是两块宽）。 */
    public static final EnumProperty<Side> SIDE = EnumProperty.of("side", Side.class);
    /** 栅门开着。❗布尔属性的取值次序 true 在前 —— 不显式写默认值，默认状态就是「开着」（证伪表：新加布尔属性默认 true）。 */
    public static final BooleanProperty OPEN = Properties.OPEN;
    /** 指针盘指着第几层（0 = 最低那一层；层数多于 8 时最高的几层都指在 7）。 */
    public static final IntProperty FLOOR = IntProperty.of("floor", 0, 7);
    /** 召唤按钮亮着（按下之后、轿厢到这一层之前）。同 {@link #OPEN}，默认值要显式写 false。 */
    public static final BooleanProperty LIT = Properties.LIT;

    private static final Map<String, Block> BLOCKS = new LinkedHashMap<>();

    public static final LiftGate GATE = lift("liner_lift_gate", LiftGate::new, settings());
    public static final LiftDial DIAL = lift("liner_lift_dial", LiftDial::new, settings());
    public static final LiftCall CALL = lift("liner_lift_call", LiftCall::new,
            settings().noCollision().luminance(s -> s.get(LIT) ? 5 : 0));
    public static final LiftCarPanel PANEL = lift("liner_lift_panel", LiftCarPanel::new, settings().noCollision());

    /** 轿厢每过一层要多少 tick（1 秒）：叫车与乘梯同一个速度。 */
    public static final int TICKS_PER_FLOOR = 20;
    /** 门开着、没人在轿厢里或门洞里，过这么久自己合上（5 秒）。 */
    public static final int DOOR_HOLD_TICKS = 100;
    /** 别的层在叫车时，门至少开这么久才合上去接（2 秒：刚到站的人走得出来、刚叫来车的人走得进去）。 */
    public static final int MIN_OPEN_TICKS = 40;
    /** 同一人两次请求（按按钮 / 乘梯）至少隔多少 tick（1 秒）。按按钮与乘梯各记各的：按了面板、一秒内选层是正常的一趟。 */
    static final int COOLDOWN_TICKS = 20;
    /** 指针盘最多画几档（{@link #FLOOR} 的上限 + 1）。 */
    public static final int DIAL_STOPS = 8;

    /** 每个人上一次被处理的乘梯请求在第几 tick（只活一次运行，不存档）。 */
    private static final Map<UUID, Long> LAST = new HashMap<>();
    /** 每个人上一次按按钮（召唤按钮 · 栅门 · 面板）在第几 tick。 */
    private static final Map<UUID, Long> PRESSED = new HashMap<>();
    /** 每部电梯的虚拟轿厢（只活一次运行，不存档；见类注释）。 */
    private static final Map<LiftKey, Car> CARS = new LinkedHashMap<>();

    /**
     * 这一层是哪一层甲板：船体生成器给船上每扇门写明；手放的是 {@link #NONE}（面板上照位置编号）。
     *
     * <p>❗{@link #NONE} 必须排第一：方块的默认状态取每个属性的第一个值 —— 手放、{@code /setblock} 不带属性、物品图标都是默认状态
     * （「新加属性的默认值」那一条证伪：布尔属性默认取 true 漏过一次）。{@code LinerLiftRulesTest} 守着。
     */
    public enum Deck implements StringIdentifiable {
        NONE, F, E, D, C, B, A, BOAT;

        /** 方块状态里与网络上传的都是这个名字（不传序号：常量的次序改了线上含义不变）；面板照它取 lang 键（{@link LiftPanel#label}）。 */
        @Override
        public String asString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * 两块宽的件是左边那块还是右边那块（从电梯厅、面朝栅门看过去）。❗{@link #LEFT} 排第一 = 默认（单测守着）：
     * 手放的那一块是左块，另一块往观者右手放。
     */
    public enum Side implements StringIdentifiable {
        LEFT, RIGHT;

        @Override
        public String asString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private LinerLift() {
    }

    private static AbstractBlock.Settings settings() {
        // 与北辰号的门同一套：生存里拆不动、不掉东西、活塞推不动（ADR-0069 §2 ⑤）；不是实心不透光的方块（光照得进去）
        return AbstractBlock.Settings.create().mapColor(MapColor.GOLD).strength(-1.0f, 3_600_000.0f)
                .dropsNothing().pistonBehavior(PistonBehavior.BLOCK).nonOpaque();
    }

    private static <B extends Block> B lift(String name, Function<AbstractBlock.Settings, B> make, AbstractBlock.Settings settings) {
        B block = make.apply(settings);
        BLOCKS.put(name, block);
        return block;
    }

    /** 方块与物品一起登记：栅门用两格高的那一种物品（先清上面那一格），别的是普通方块物品。 */
    public static void register() {
        BLOCKS.forEach((name, block) -> {
            Identifier id = Identifier.of(HeavySeasMod.MOD_ID, name);
            Registry.register(Registries.BLOCK, id, block);
            Registry.register(Registries.ITEM, id, block instanceof LiftGate
                    ? new TallBlockItem(block, new Item.Settings()) : new BlockItem(block, new Item.Settings()));
        });
    }

    /** 全部电梯方块（每一块都是 {@link LinerLooks.Styled}）。 */
    public static Map<String, Block> all() {
        return Collections.unmodifiableMap(BLOCKS);
    }

    // ================================================================ 方块

    /**
     * 栅门：上下两块像门那样一起放一起拆；左右两扇。合着是一整片栅格（挡人），开着时栅格叠到外侧只剩一叠（碰撞箱也只剩那一叠）。
     * 右键 = 按这一层的召唤按钮。
     */
    public static final class LiftGate extends Block implements LinerLooks.Styled {

        LiftGate(AbstractBlock.Settings settings) {
            super(settings);
            // deck · side 不在这里写：它们照枚举的第一个（NONE · LEFT），见两个枚举的注释；open 是布尔，必须写
            setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH).with(HALF, DoubleBlockHalf.LOWER)
                    .with(OPEN, false));
        }

        @Override
        protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
            builder.add(FACING, HALF, DECK, SIDE, OPEN);
        }

        @Override
        public LinerLooks.Look look(BlockState state) {
            return LinerLooks.look(Rules.gateTemplate(state.get(SIDE), state.get(OPEN), state.get(HALF)), tex("g", "lift/gate"),
                    Rules.yaw(state.get(FACING)));
        }

        /** 物品：合着的两扇（左右各上下两格）缩小。 */
        @Override
        public LinerLooks.Look itemLook() {
            return LinerLooks.look("lift/gate_item", tex("g", "lift/gate"));
        }

        /** 放下：像双开门一次放左右两扇各两格 —— 点的那一格是左扇，右扇往观者右手；四格都得空着。正面朝放门的人。 */
        @Override
        public BlockState getPlacementState(ItemPlacementContext ctx) {
            BlockPos pos = ctx.getBlockPos();
            World world = ctx.getWorld();
            Direction facing = ctx.getHorizontalPlayerFacing().getOpposite();
            BlockPos right = pos.offset(Rules.rightOf(facing));
            if (pos.getY() >= world.getTopY() - 1 || !world.getBlockState(pos.up()).canReplace(ctx)
                    || !world.getBlockState(right).canReplace(ctx) || !world.getBlockState(right.up()).canReplace(ctx)) {
                return null;
            }
            return getDefaultState().with(FACING, facing);
        }

        @Override
        public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
            world.setBlockState(pos.up(), state.with(HALF, DoubleBlockHalf.UPPER), Block.NOTIFY_ALL);
            if (state.get(SIDE) == Side.LEFT) {
                BlockPos right = pos.offset(Rules.rightOf(state.get(FACING)));
                BlockState r = state.with(SIDE, Side.RIGHT);
                world.setBlockState(right, r, Block.NOTIFY_ALL);
                world.setBlockState(right.up(), r.with(HALF, DoubleBlockHalf.UPPER), Block.NOTIFY_ALL);
            }
        }

        /** 上下两块互相认：另一块没了，这一块也没了（不掉东西 —— 方块本来就不掉）。左右两扇不互相认（与双开门一样）。 */
        @Override
        protected BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighbor,
                                                       WorldAccess world, BlockPos pos, BlockPos neighborPos) {
            DoubleBlockHalf half = state.get(HALF);
            if (direction.getAxis() == Direction.Axis.Y && (half == DoubleBlockHalf.LOWER) == (direction == Direction.UP)) {
                boolean partner = neighbor.isOf(this) && neighbor.get(HALF) != half;
                return partner ? state : Blocks.AIR.getDefaultState();
            }
            return super.getStateForNeighborUpdate(state, direction, neighbor, world, pos, neighborPos);
        }

        @Override
        protected boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
            if (state.get(HALF) == DoubleBlockHalf.UPPER) {
                BlockState below = world.getBlockState(pos.down());
                return below.isOf(this) && below.get(HALF) == DoubleBlockHalf.LOWER;
            }
            return true;
        }

        @Override
        protected BlockState rotate(BlockState state, BlockRotation rotation) {
            return state.with(FACING, rotation.rotate(state.get(FACING)));
        }

        /** 镜像：朝向照镜子转，左右两扇对调（镜子里左手变右手）。 */
        @Override
        protected BlockState mirror(BlockState state, BlockMirror mirror) {
            BlockState s = state.rotate(mirror.getRotation(state.get(FACING)));
            return mirror == BlockMirror.NONE ? s : s.with(SIDE, state.get(SIDE) == Side.LEFT ? Side.RIGHT : Side.LEFT);
        }

        @Override
        protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
            return Rules.gateOutline(state.get(FACING), state.get(HALF), state.get(SIDE), state.get(OPEN));
        }

        @Override
        protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
            return Rules.gateCollision(state.get(FACING), state.get(HALF), state.get(SIDE), state.get(OPEN));
        }

        @Override
        protected boolean canPathfindThrough(BlockState state, NavigationType type) {
            return false;
        }

        /** 右键上下左右哪一块都行：服务端认这部电梯在这一层的左扇下半块，当作按了这一层的召唤按钮。客户端只摆一下手。 */
        @Override
        protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
            if (world.isClient) {
                return ActionResult.SUCCESS;
            }
            if (world instanceof ServerWorld server && player instanceof ServerPlayerEntity sp) {
                BlockPos gate = Rules.columnOf(pos, state);
                BlockState at = server.getBlockState(gate);
                if (Rules.isLeftLower(at)) {
                    summonFrom(sp, server, gate);
                }
            }
            return ActionResult.CONSUME;
        }
    }

    /**
     * 楼层指针盘：嵌在栅门正上方那一排墙里的整块（两块宽），正面一块凸出的黄铜半圆刻度盘 + 一根指针。
     * {@link #FLOOR} 决定指针角度（{@code template/lift/dial_*}：0 层指向观者左手偏下，7 层指向右手偏下，均匀分在 160° → 20°）；
     * 指针整根落在它指向的那一块里，转轴骑在两块交界上（由左块画）。服务端轿厢走动时改它。
     */
    public static final class LiftDial extends Block implements LinerLooks.Styled {

        LiftDial(AbstractBlock.Settings settings) {
            super(settings);
            setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH).with(FLOOR, 0));
        }

        @Override
        protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
            builder.add(FACING, SIDE, FLOOR);
        }

        @Override
        public LinerLooks.Look look(BlockState state) {
            Side side = state.get(SIDE);
            return LinerLooks.look(Rules.dialTemplate(side, state.get(FLOOR)),
                    tex("p", side == Side.LEFT ? "lift/dial_l" : "lift/dial_r", "d", "lift/dial"), Rules.yaw(state.get(FACING)));
        }

        @Override
        public LinerLooks.Look itemLook() {
            return LinerLooks.look("lift/dial_item", tex("p", "lift/dial_l", "q", "lift/dial_r", "d", "lift/dial"));
        }

        /** 放下：一次放左右两块（点的那一格是左块，右块往观者右手，那一格得空着）；正面朝放的人。 */
        @Override
        public BlockState getPlacementState(ItemPlacementContext ctx) {
            Direction facing = ctx.getHorizontalPlayerFacing().getOpposite();
            if (!ctx.getWorld().getBlockState(ctx.getBlockPos().offset(Rules.rightOf(facing))).canReplace(ctx)) {
                return null;
            }
            return getDefaultState().with(FACING, facing);
        }

        @Override
        public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
            if (state.get(SIDE) == Side.LEFT) {
                world.setBlockState(pos.offset(Rules.rightOf(state.get(FACING))), state.with(SIDE, Side.RIGHT), Block.NOTIFY_ALL);
            }
        }

        @Override
        protected BlockState rotate(BlockState state, BlockRotation rotation) {
            return state.with(FACING, rotation.rotate(state.get(FACING)));
        }

        @Override
        protected BlockState mirror(BlockState state, BlockMirror mirror) {
            BlockState s = state.rotate(mirror.getRotation(state.get(FACING)));
            return mirror == BlockMirror.NONE ? s : s.with(SIDE, state.get(SIDE) == Side.LEFT ? Side.RIGHT : Side.LEFT);
        }
    }

    /** 召唤按钮：贴在电梯厅墙面上的一小块黄铜按钮板（像壁灯那样贴墙），按下后亮着、轿厢到了熄灭。 */
    public static final class LiftCall extends Block implements LinerLooks.Styled {

        LiftCall(AbstractBlock.Settings settings) {
            super(settings);
            setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH).with(LIT, false));
        }

        @Override
        protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
            builder.add(FACING, LIT);
        }

        @Override
        public LinerLooks.Look look(BlockState state) {
            return LinerLooks.look(state.get(LIT) ? "lift/call_on" : "lift/call_off", tex("f", "lift/fitting"),
                    Rules.yaw(state.get(FACING)));
        }

        @Override
        public BlockState getPlacementState(ItemPlacementContext ctx) {
            Direction facing = Rules.wallFacing(ctx);
            return facing == null ? null : getDefaultState().with(FACING, facing);
        }

        @Override
        protected BlockState rotate(BlockState state, BlockRotation rotation) {
            return state.with(FACING, rotation.rotate(state.get(FACING)));
        }

        @Override
        protected BlockState mirror(BlockState state, BlockMirror mirror) {
            return state.rotate(mirror.getRotation(state.get(FACING)));
        }

        @Override
        protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
            return Rules.wallShape(Rules.CALL_BOX, state.get(FACING));
        }

        @Override
        protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
            if (world.isClient) {
                return ActionResult.SUCCESS;
            }
            if (world instanceof ServerWorld server && player instanceof ServerPlayerEntity sp) {
                press(sp, server, pos, state);
            }
            return ActionResult.CONSUME;
        }
    }

    /** 轿厢里的按钮面板：贴在轿厢侧墙上的一小块黄铜面板，右键弹楼层面板。 */
    public static final class LiftCarPanel extends Block implements LinerLooks.Styled {

        LiftCarPanel(AbstractBlock.Settings settings) {
            super(settings);
            setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH));
        }

        @Override
        protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
            builder.add(FACING);
        }

        @Override
        public LinerLooks.Look look(BlockState state) {
            return LinerLooks.look("lift/panel", tex("f", "lift/fitting"), Rules.yaw(state.get(FACING)));
        }

        @Override
        public BlockState getPlacementState(ItemPlacementContext ctx) {
            Direction facing = Rules.wallFacing(ctx);
            return facing == null ? null : getDefaultState().with(FACING, facing);
        }

        @Override
        protected BlockState rotate(BlockState state, BlockRotation rotation) {
            return state.with(FACING, rotation.rotate(state.get(FACING)));
        }

        @Override
        protected BlockState mirror(BlockState state, BlockMirror mirror) {
            return state.rotate(mirror.getRotation(state.get(FACING)));
        }

        @Override
        protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
            return Rules.wallShape(Rules.PANEL_BOX, state.get(FACING));
        }

        @Override
        protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
            if (world.isClient) {
                return ActionResult.SUCCESS;
            }
            if (world instanceof ServerWorld server && player instanceof ServerPlayerEntity sp) {
                panel(sp, server, pos);
            }
            return ActionResult.CONSUME;
        }
    }

    // ================================================================ 服务端：虚拟轿厢

    /** 一部电梯：哪个世界 + 哪条井道。 */
    record LiftKey(RegistryKey<World> world, Shaft shaft) {
    }

    /** 轿厢此刻：停着（门合着）· 停着、门开着 · 在两层之间走。 */
    enum Phase {
        IDLE, OPEN, MOVING
    }

    /** 一部电梯的虚拟轿厢（只在服务端线程上碰）。层一律按 y 记（楼层表会变：门被拆了、加了一层，下标就挪了）。 */
    static final class Car {
        final LiftKey key;
        /** 最近一次扫出来的楼层表。 */
        List<Floor> floors = List.of();
        /** 停在（或从）哪一层（门的 y）。 */
        int atY;
        Phase phase = Phase.IDLE;
        /** OPEN：门开了 / 最近一次有人在里面是第几 tick；MOVING：出发那一 tick。 */
        long since;
        /** MOVING：去哪一层、出发时的楼层表（走的路不随中途拆门变）、指针盘此刻指在第几档、带着谁。 */
        int toY;
        List<Floor> route = List.of();
        int shown = -1;
        final List<UUID> riders = new ArrayList<>();
        /** 哪几层有人在叫车（门的 y；按钮亮着）。 */
        final Set<Integer> calls = new TreeSet<>();

        Car(LiftKey key) {
            this.key = key;
        }
    }

    /** 这条井道的楼层表（从世界底扫到顶，见 {@link Rules#scan}）。 */
    static List<Floor> floorsAt(World world, Shaft shaft) {
        return Rules.scan(shaft, world.getBottomY(), world.getTopY(), world::getBlockState);
    }

    /** {@code gate}（某一层的左扇下半块）那一部电梯的虚拟轿厢；第一次碰到时按指针盘认出它在哪一层，并把门、指针、按钮理成一致（见类注释）。楼层表每次重扫。 */
    static Car car(ServerWorld world, BlockPos gate) {
        Shaft shaft = Rules.shaftOf(gate, world.getBlockState(gate).get(FACING));
        LiftKey key = new LiftKey(world.getRegistryKey(), shaft);
        Car car = CARS.get(key);
        List<Floor> floors = floorsAt(world, shaft);
        if (car != null) {
            car.floors = floors;
            return car;
        }
        car = new Car(key);
        car.floors = floors;
        int reading = -1;
        for (Floor f : floors) {
            BlockState d = world.getBlockState(Rules.dialPositions(f.gate(), f.facing()).get(0));
            if (d.isOf(DIAL)) {
                reading = d.get(FLOOR);
                break;
            }
        }
        car.atY = floors.isEmpty() ? gate.getY() : floors.get(Math.max(0, Math.min(reading, floors.size() - 1))).y();
        int at = Rules.indexOf(floors, car.atY);
        for (Floor f : floors) {
            setDoors(world, f.gate(), f.facing(), false);
            light(world, car, f.y(), false);
        }
        setDials(world, car, floors, Math.max(at, 0));
        CARS.put(key, car);
        LOGGER.debug("Lift at {} {}: car starts at y {} ({} floors, dial reading {})", key.world().getValue(), key.shaft(),
                car.atY, floors.size(), reading);
        return car;
    }

    /** 召唤按钮被按了：找它叫的那一部（{@link Rules#callGate}），当作按了那一层。 */
    static void press(ServerPlayerEntity player, ServerWorld world, BlockPos button, BlockState state) {
        BlockPos gate = Rules.callGate(button, state.get(FACING), world::getBlockState);
        if (gate == null) {
            if (!player.isSpectator() && pressAllowed(player, world)) {
                player.sendMessage(Text.translatable("heavyseas.lift.no_lift"), true);
            }
            return;
        }
        summonFrom(player, world, gate);
    }

    /** 有人在 {@code gate} 那一层叫车（按召唤按钮、或右键栅门）。 */
    static void summonFrom(ServerPlayerEntity player, ServerWorld world, BlockPos gate) {
        if (player.isSpectator()) {
            return;
        }
        if (inVoyage(player)) {
            player.sendMessage(Text.translatable("heavyseas.lift.in_voyage"), true);
            return;
        }
        if (!pressAllowed(player, world)) {
            return;
        }
        Car car = car(world, gate);
        if (Rules.indexOf(car.floors, gate.getY()) < 0) {
            return;
        }
        if (!summon(world, car, gate.getY(), world.getServer().getTicks())) {
            player.sendMessage(Text.translatable("heavyseas.lift.coming"), true);
        }
    }

    /**
     * 叫车到 {@code y} 那一层：在这一层就开门（回 true），否则记下这一层、点亮按钮，门合着停着就当场出发（回 false = 「正在过来」）。
     * 门开在别的层时不当场走：{@link #tick} 等门开满 {@link #MIN_OPEN_TICKS}、里面没人再合上去接（刚叫来车的人还没走进去）。
     */
    static boolean summon(ServerWorld world, Car car, int y, long now) {
        if (car.phase != Phase.MOVING && car.atY == y) {
            openHere(world, car, now);
            return true;
        }
        car.calls.add(y);
        light(world, car, y, true);
        if (car.phase == Phase.IDLE) {
            depart(world, car, y, List.of(), now);
        }
        return false;
    }

    /** 轿厢里的按钮面板被按了：轿厢就在这一层（停着）→ 弹楼层面板；不在 → 当作叫车。 */
    static void panel(ServerPlayerEntity player, ServerWorld world, BlockPos panelPos) {
        if (player.isSpectator()) {
            return;
        }
        if (inVoyage(player)) {
            player.sendMessage(Text.translatable("heavyseas.lift.in_voyage"), true);
            return;
        }
        if (!pressAllowed(player, world)) {
            return;
        }
        BlockPos gate = Rules.panelGate(panelPos, world::getBlockState);
        if (gate == null) {
            player.sendMessage(Text.translatable("heavyseas.lift.no_lift"), true);
            return;
        }
        Car car = car(world, gate);
        int here = Rules.indexOf(car.floors, gate.getY());
        if (here < 0) {
            return;
        }
        if (car.phase == Phase.MOVING || car.atY != gate.getY()) {
            summon(world, car, gate.getY(), world.getServer().getTicks());
            player.sendMessage(Text.translatable("heavyseas.lift.coming"), true);
            return;
        }
        if (car.floors.size() > LiftFloorsS2C.MAX_STOPS) {
            // 造包这一刻就拒：编码在网络线程上做，超了上限的包编码失败会把人踢下线（与 CatalogS2C 同一个道理）
            player.sendMessage(Text.translatable("heavyseas.lift.unavailable"), true);
            return;
        }
        if (!ServerPlayNetworking.canSend(player, LiftFloorsS2C.ID)) {
            return;                                                 // 没装模组的客户端（服务端照样起得来）
        }
        List<LiftFloorsS2C.Stop> stops = new ArrayList<>();
        for (Floor f : car.floors) {
            stops.add(new LiftFloorsS2C.Stop(f.y(), f.deck().asString()));
        }
        ServerPlayNetworking.send(player, new LiftFloorsS2C(gate.asLong(), here, stops));
    }

    /** 面板上点了一层：服务端从头核对，只认「同一列里第 n 扇门」，不认客户端给的坐标。 */
    public static void travel(ServerPlayerEntity player, LiftTravelC2S request) {
        ServerWorld world = player.getServerWorld();
        long now = world.getServer().getTicks();
        BlockPos gate = BlockPos.fromLong(request.gate());
        BlockState at = world.getBlockState(gate);
        boolean gateHere = Rules.isLeftLower(at);
        Car car = gateHere ? car(world, gate) : null;
        List<Floor> floors = car == null ? List.of() : car.floors;
        boolean inCar = gateHere && Rules.carZone(gate, at.get(FACING)).contains(player.getPos());
        boolean carHere = car != null && car.phase != Phase.MOVING && car.atY == gate.getY();
        Long last = LAST.get(player.getUuid());
        Rules.Verdict verdict = Rules.check(new Rules.Request(player.isSpectator(), inVoyage(player), now,
                last == null ? Long.MIN_VALUE : last, gateHere, inCar, carHere, floors, gate.getY(), request.target(), request.targetY()));
        if (verdict != Rules.Verdict.TOO_SOON) {
            LAST.put(player.getUuid(), now);
        }
        switch (verdict) {
            case OK -> {
                Box zone = Rules.carZone(gate, at.get(FACING));
                List<ServerPlayerEntity> riders = new ArrayList<>();
                for (ServerPlayerEntity p : world.getPlayers()) {
                    if (!p.isSpectator() && zone.contains(p.getPos()) && !inVoyage(p)) {
                        riders.add(p);
                    }
                }
                depart(world, car, floors.get(request.target()).y(), riders, now);
                LOGGER.info("电梯：{} 带 {} 人从 y {} 去 {} 层（y {}）", player.getName().getString(), riders.size(), gate.getY(),
                        request.target() + 1, floors.get(request.target()).y());
                return;
            }
            case IN_VOYAGE -> player.sendMessage(Text.translatable("heavyseas.lift.in_voyage"), true);
            case CAR_AWAY -> player.sendMessage(Text.translatable("heavyseas.lift.coming"), true);
            case NOT_A_GATE, NOT_IN_CAR, NO_SUCH_FLOOR, SAME_FLOOR ->
                    player.sendMessage(Text.translatable("heavyseas.lift.unavailable"), true);
            default -> {
                // 旁观者、太快：不回话（旁观者本来就点不了面板；连点的第二下与第一下是同一件事）
            }
        }
        LOGGER.debug("电梯：{} 的请求被拒（{}）", player.getName().getString(), verdict);
    }

    /** 关上这一层的门，出发去 {@code toY}（带着 {@code riders}；叫车时是空的）。 */
    static void depart(ServerWorld world, Car car, int toY, List<ServerPlayerEntity> riders, long now) {
        Floor here = Rules.floorAt(car.floors, car.atY);
        if (here != null) {
            setDoors(world, here.gate(), here.facing(), false);
            // 声音接口点：关门（本模组自己的一套，用户定不用游戏自带的音效）
        }
        car.route = car.floors;
        car.toY = toY;
        car.phase = Phase.MOVING;
        car.since = now;
        car.riders.clear();
        int ticks = Rules.travelTicks(Rules.indexOf(car.route, car.atY), Rules.indexOf(car.route, toY));
        for (ServerPlayerEntity p : riders) {
            car.riders.add(p.getUuid());
            if (ServerPlayNetworking.canSend(p, LiftRideS2C.ID)) {
                ServerPlayNetworking.send(p, new LiftRideS2C(ticks));
            }
        }
        // 声音接口点：出发
    }

    /** 每 tick：走着的往前走一格指针、到站；开着的门看要不要合上；停着的去接下一个叫车的。 */
    public static void tick(MinecraftServer server) {
        if (CARS.isEmpty()) {
            return;
        }
        long now = server.getTicks();
        for (Car car : CARS.values()) {
            ServerWorld world = server.getWorld(car.key.world());
            if (world == null) {
                continue;
            }
            switch (car.phase) {
                case MOVING -> move(world, car, now);
                case OPEN -> {
                    boolean busy = occupied(world, car, car.atY);
                    if (busy) {
                        car.since = now;
                    }
                    boolean wanted = car.calls.stream().anyMatch(y -> y != car.atY);
                    if (!busy && Rules.shouldClose(car.since, now, wanted)) {
                        Floor f = Rules.floorAt(car.floors, car.atY);
                        if (f != null) {
                            setDoors(world, f.gate(), f.facing(), false);
                            // 声音接口点：关门
                        }
                        car.phase = Phase.IDLE;
                    }
                }
                default -> {
                    if (!car.calls.isEmpty()) {
                        int next = Rules.nextCall(car.floors, car.atY, car.calls);
                        if (next == Integer.MIN_VALUE) {
                            car.calls.clear();                       // 叫车的那几层都没了（门被拆了）
                        } else if (next == car.atY) {
                            openHere(world, car, now);
                        } else {
                            depart(world, car, next, List.of(), now);
                        }
                    }
                }
            }
        }
    }

    private static void move(ServerWorld world, Car car, long now) {
        int from = Rules.indexOf(car.route, car.atY);
        int to = Rules.indexOf(car.route, car.toY);
        if (from < 0 || to < 0) {
            car.phase = Phase.IDLE;                                 // 不会发生：route 是出发时那一份
            return;
        }
        long elapsed = now - car.since;
        int pos = Rules.positionAt(from, to, elapsed);
        if (Rules.dial(pos) != car.shown) {
            setDials(world, car, car.route, pos);
            // 声音接口点：指针每走一格
        }
        if (elapsed >= Rules.travelTicks(from, to)) {
            arrive(world, car, now);
        }
    }

    /** 到站：先开目标层的门（门洞里的人才站得下），再把带着的人送到同一个相对位置、同一个朝向。 */
    private static void arrive(ServerWorld world, Car car, long now) {
        Floor origin = Rules.floorAt(car.route, car.atY);
        Floor target = Rules.floorAt(car.route, car.toY);
        car.atY = car.toY;
        car.phase = Phase.OPEN;
        car.since = now;
        car.calls.remove(car.toY);
        light(world, car, car.toY, false);
        if (target != null) {
            setDoors(world, target.gate(), target.facing(), true);
            // 声音接口点：到站铃 · 开门
        }
        if (origin == null || target == null) {
            car.riders.clear();
            return;
        }
        BlockPos og = origin.gate();
        BlockPos tg = target.gate();
        Box zone = Rules.carZone(og, origin.facing());
        Text name = Rules.name(car.route, Rules.indexOf(car.route, car.toY));
        for (UUID id : car.riders) {
            ServerPlayerEntity p = world.getServer().getPlayerManager().getPlayer(id);
            if (p == null || p.getServerWorld() != world || p.isSpectator() || !zone.contains(p.getPos())) {
                continue;                                           // 下线了、换了世界、半路离开了轿厢
            }
            Vec3d dest = Rules.carry(p.getPos(), og, origin.facing(), tg, target.facing());
            float yaw = Rules.carryYaw(p.getYaw(), origin.facing(), target.facing());
            if (!world.isSpaceEmpty(p, p.getDimensions(p.getPose()).getBoxAt(dest))) {
                p.sendMessage(Text.translatable("heavyseas.lift.blocked"), true);
                continue;
            }
            p.stopRiding();
            p.teleport(world, dest.x, dest.y, dest.z, yaw, p.getPitch());
            p.sendMessage(Text.translatable("heavyseas.lift.arrived", name), true);
        }
        LOGGER.debug("Lift {} arrived at y {} with {} rider(s)", car.key.shaft(), car.toY, car.riders.size());
        car.riders.clear();
    }

    private static void openHere(ServerWorld world, Car car, long now) {
        Floor f = Rules.floorAt(car.floors, car.atY);
        car.calls.remove(car.atY);
        light(world, car, car.atY, false);
        if (f == null) {
            return;
        }
        if (car.phase != Phase.OPEN) {
            setDoors(world, f.gate(), f.facing(), true);
            // 声音接口点：开门
        }
        car.phase = Phase.OPEN;
        car.since = now;
    }

    /** 这一层的轿厢里（含门洞那一排）有没有人（旁观者不算）。 */
    static boolean occupied(ServerWorld world, Car car, int y) {
        Floor f = Rules.floorAt(car.floors, y);
        if (f == null) {
            return false;
        }
        Box zone = Rules.carZone(f.gate(), f.facing());
        for (ServerPlayerEntity p : world.getPlayers()) {
            if (!p.isSpectator() && zone.contains(p.getPos())) {
                return true;
            }
        }
        return false;
    }

    /** 开 / 合一层的两扇门（四块）。区块没载入就不碰（不为了开一扇门去载区块）。 */
    static void setDoors(ServerWorld world, BlockPos gate, Direction facing, boolean open) {
        BlockPos right = gate.offset(Rules.rightOf(facing));
        for (BlockPos p : List.of(gate, gate.up(), right, right.up())) {
            if (!loaded(world, p)) {
                continue;
            }
            BlockState s = world.getBlockState(p);
            if (s.isOf(GATE) && s.get(OPEN) != open) {
                world.setBlockState(p, s.with(OPEN, open), Block.NOTIFY_ALL);
            }
        }
    }

    /** 把这一部每一层的指针盘拨到第 {@code index} 层。 */
    static void setDials(ServerWorld world, Car car, List<Floor> floors, int index) {
        int value = Rules.dial(index);
        car.shown = value;
        for (Floor f : floors) {
            for (BlockPos p : Rules.dialPositions(f.gate(), f.facing())) {
                if (!loaded(world, p)) {
                    continue;
                }
                BlockState s = world.getBlockState(p);
                if (s.isOf(DIAL) && s.get(FLOOR) != value) {
                    world.setBlockState(p, s.with(FLOOR, value), Block.NOTIFY_LISTENERS);
                }
            }
        }
    }

    /** 点亮 / 熄掉 {@code y} 那一层叫这一部的召唤按钮（反过来找：候选格里的按钮按 {@link Rules#callGate} 认得出这扇门才算）。 */
    static void light(ServerWorld world, Car car, int y, boolean lit) {
        Floor f = Rules.floorAt(car.floors, y);
        if (f == null) {
            return;
        }
        BlockPos gate = f.gate();
        for (BlockPos p : Rules.callCandidates(gate, f.facing())) {
            if (!loaded(world, p)) {
                continue;
            }
            BlockState s = world.getBlockState(p);
            if (s.isOf(CALL) && s.get(LIT) != lit && gate.equals(Rules.callGate(p, s.get(FACING), world::getBlockState))) {
                world.setBlockState(p, s.with(LIT, lit), Block.NOTIFY_ALL);
            }
        }
    }

    private static boolean loaded(ServerWorld world, BlockPos p) {
        return world.isChunkLoaded(ChunkSectionPos.getSectionCoord(p.getX()), ChunkSectionPos.getSectionCoord(p.getZ()));
    }

    /** 同一人按按钮（召唤按钮 · 栅门 · 面板）1 秒一次；放行就记下这一次。 */
    private static boolean pressAllowed(ServerPlayerEntity player, ServerWorld world) {
        long now = world.getServer().getTicks();
        Long last = PRESSED.get(player.getUuid());
        if (last != null && now - last < COOLDOWN_TICKS) {
            return false;
        }
        PRESSED.put(player.getUuid(), now);
        return true;
    }

    /** 这个人此刻坐在某一局的座位上（与各阶段认「你是不是这一局的人」同一个判法：哪个世界有对局、座位上是他）。 */
    static boolean inVoyage(ServerPlayerEntity player) {
        for (ServerWorld w : player.server.getWorlds()) {
            var component = GameComponents.of(w);
            if (component.session().isPresent() && component.seatOf(player.getUuid()).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /** 停服时清掉频率记录与虚拟轿厢（单人游戏同一个进程里再开一个世界，不带过去）。 */
    public static void forget() {
        LAST.clear();
        PRESSED.clear();
        CARS.clear();
    }

    /** 下线的人的频率记录（只在服务端线程上调）。 */
    public static void forget(UUID player) {
        LAST.remove(player);
        PRESSED.remove(player);
    }

    /** 一层：左扇下半块的 y · 哪一层甲板 · 门正面朝哪（朝电梯厅）· 左扇下半块的 x · z（同一条井道里门朝向不同的层，左扇落在不同的那一格）。 */
    public record Floor(int y, Deck deck, Direction facing, int x, int z) {
        public BlockPos gate() {
            return new BlockPos(x, y, z);
        }
    }

    /** 一条井道：两扇门占的那两格 —— 坐标小的那一格 {@code (x, z)} 与它沿 {@code axis} 正向的下一格。 */
    public record Shaft(int x, int z, Direction.Axis axis) {
        public BlockPos first(int y) {
            return new BlockPos(x, y, z);
        }

        public BlockPos second(int y) {
            return first(y).offset(Direction.from(axis, Direction.AxisDirection.POSITIVE));
        }
    }

    // ================================================================ 纯规则

    /** 单测 {@code LinerLiftRulesTest}。 */
    public static final class Rules {

        private Rules() {
        }

        // ---------------------------------------------------------------- 左右与几格

        /**
         * 观者左手那一边：门正面朝 {@code facing}，观者站在门前面朝门（看的方向是 {@code facing} 的反向），他的左手 = {@code facing} 顺时针转 90°。
         * 例：门朝东（人往西看）→ 左手是南（z 大）；门朝南（人往北看）→ 左手是西（x 小）。
         */
        public static Direction leftOf(Direction facing) {
            return facing.rotateYClockwise();
        }

        /** 观者右手：右扇在左扇的这一边。 */
        public static Direction rightOf(Direction facing) {
            return facing.rotateYCounterclockwise();
        }

        /** 这块栅门属于哪一部电梯在这一层的「列」：左扇下半块那一格。 */
        public static BlockPos columnOf(BlockPos pos, BlockState gate) {
            BlockPos lower = gate.get(HALF) == DoubleBlockHalf.LOWER ? pos : pos.down();
            return gate.get(SIDE) == Side.LEFT ? lower : lower.offset(leftOf(gate.get(FACING)));
        }

        /** 左扇下半块在 {@code leftLower}、门朝 {@code facing} 的那一对门占哪条井道（左右两格里坐标小的那一格 + 方向）。 */
        public static Shaft shaftOf(BlockPos leftLower, Direction facing) {
            BlockPos right = leftLower.offset(rightOf(facing));
            return new Shaft(Math.min(leftLower.getX(), right.getX()), Math.min(leftLower.getZ(), right.getZ()), rightOf(facing).getAxis());
        }

        public static boolean isLeftLower(BlockState s) {
            return s.getBlock() instanceof LiftGate && s.get(HALF) == DoubleBlockHalf.LOWER && s.get(SIDE) == Side.LEFT;
        }

        /**
         * 轿厢范围（乘梯带走谁、门口有没有人）：两扇门那一排 + 背后两排，两格宽、三格深、三格高。
         * 门洞那一排也算：合着的栅格贴在电梯厅那一侧，门洞那一格大半在门里；不算的话站在门洞里的人会被合上的门关在外面一半。
         */
        public static Box carZone(BlockPos gate, Direction facing) {
            // 对角那一格：右扇往门里退两排
            BlockPos far = gate.offset(rightOf(facing)).offset(facing.getOpposite(), 2);
            return new Box(Math.min(gate.getX(), far.getX()), gate.getY(), Math.min(gate.getZ(), far.getZ()),
                    Math.max(gate.getX(), far.getX()) + 1, gate.getY() + 3, Math.max(gate.getZ(), far.getZ()) + 1);
        }

        /**
         * 乘梯：人从 {@code from} 那一层的轿厢里同一个相对位置到 {@code to} 那一层。两层的门朝向可以不同（ADR-0105：艇甲板那一站开进
         * 穹顶厅，门朝船尾，轿厢在门的另一侧）—— 按「往门里几格 · 往右扇那边几格」换算：以左扇那一格的中心为原点，进深沿门朝向的反向，
         * 横向沿 {@link #rightOf}。朝向相同时就是平移。
         */
        public static Vec3d carry(Vec3d feet, BlockPos from, Direction fromFacing, BlockPos to, Direction toFacing) {
            double dx = feet.x - (from.getX() + 0.5);
            double dz = feet.z - (from.getZ() + 0.5);
            Direction inF = fromFacing.getOpposite();
            Direction rF = rightOf(fromFacing);
            double depth = dx * inF.getOffsetX() + dz * inF.getOffsetZ();
            double across = dx * rF.getOffsetX() + dz * rF.getOffsetZ();
            Direction inT = toFacing.getOpposite();
            Direction rT = rightOf(toFacing);
            return new Vec3d(to.getX() + 0.5 + depth * inT.getOffsetX() + across * rT.getOffsetX(),
                    feet.y + (to.getY() - from.getY()),
                    to.getZ() + 0.5 + depth * inT.getOffsetZ() + across * rT.getOffsetZ());
        }

        /** 视角跟着门转：两层门朝向差多少度，人就转多少度（朝向相同不转）。 */
        public static float carryYaw(float yaw, Direction fromFacing, Direction toFacing) {
            return yaw + (toFacing.asRotation() - fromFacing.asRotation());
        }

        /** 一层的指针盘：左块在左扇正上方两格（门下半 + 2），右块在右扇正上方。 */
        public static List<BlockPos> dialPositions(BlockPos gate, Direction facing) {
            return List.of(gate.up(2), gate.offset(rightOf(facing)).up(2));
        }

        /**
         * 召唤按钮叫的是哪一部：按钮贴在栅门左手边或右手边那一列墙上（墙面前一格，朝向 = 门的朝向）。从按钮背后那一格墙起，
         * 先沿墙往观者右手走 1 · 2 格找左扇，找不到再往左手走 1 · 2 格找右扇；每一格问同一高度、低一格、高一格，
         * 第一扇朝向相同的就是（落在上半块认下半块，找到右扇认它那一对的左扇）。找不到回 {@code null}。
         * 两边都认是为了左右舷两部对称（ADR-0105：按钮都贴在靠船中线那一侧，一部在门左手、一部在门右手）。
         */
        public static BlockPos callGate(BlockPos button, Direction facing, Function<BlockPos, BlockState> at) {
            BlockPos wall = button.offset(facing.getOpposite());
            for (Side want : Side.values()) {
                Direction walk = want == Side.LEFT ? rightOf(facing) : leftOf(facing);
                for (int step = 1; step <= 2; step++) {
                    BlockPos p = wall.offset(walk, step);
                    for (int dy : new int[]{0, -1, 1}) {
                        BlockPos q = p.up(dy);
                        BlockState s = at.apply(q);
                        if (s.getBlock() instanceof LiftGate && s.get(SIDE) == want && s.get(FACING) == facing) {
                            BlockPos lower = s.get(HALF) == DoubleBlockHalf.LOWER ? q : q.down();
                            return want == Side.LEFT ? lower : lower.offset(leftOf(facing));
                        }
                    }
                }
            }
            return null;
        }

        /** {@link #callGate} 反过来：哪几格的按钮可能叫这扇门（还要拿 {@link #callGate} 核一遍：中间那一格也可能有别的门先被认出来）。 */
        public static List<BlockPos> callCandidates(BlockPos gate, Direction facing) {
            List<BlockPos> out = new ArrayList<>();
            for (int step = 1; step <= 2; step++) {
                for (BlockPos p : List.of(gate.offset(leftOf(facing), step), gate.offset(rightOf(facing), 1 + step))) {
                    for (int dy = -1; dy <= 2; dy++) {
                        out.add(p.offset(facing).up(dy));
                    }
                }
            }
            return out;
        }

        /**
         * 轿厢面板属于哪一部：它所在那一格落在哪一部某一层的轿厢范围（{@link #carZone}）里。从面板往下 0–2 格、水平 ±3 格找左扇下半块，
         * 次序固定（先低后高、先西后东、先北后南），第一个包住它的就是。找不到回 {@code null}。
         */
        public static BlockPos panelGate(BlockPos panel, Function<BlockPos, BlockState> at) {
            Vec3d c = Vec3d.ofCenter(panel);
            for (int dy = 2; dy >= 0; dy--) {
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        BlockPos q = panel.add(dx, -dy, dz);
                        BlockState s = at.apply(q);
                        if (isLeftLower(s) && carZone(q, s.get(FACING)).contains(c)) {
                            return q;
                        }
                    }
                }
            }
            return null;
        }

        // ---------------------------------------------------------------- 时间表

        /** 从第 {@code from} 层到第 {@code to} 层要多少 tick（每过一层 {@link #TICKS_PER_FLOOR}）。 */
        public static int travelTicks(int from, int to) {
            return Math.abs(to - from) * TICKS_PER_FLOOR;
        }

        /** 出发后 {@code elapsed} tick 时轿厢「在」第几层：每满 {@link #TICKS_PER_FLOOR} 往目标挪一层，到了就停在目标。 */
        public static int positionAt(int from, int to, long elapsed) {
            long steps = Math.max(0, elapsed) / TICKS_PER_FLOOR;
            int span = Math.abs(to - from);
            return from + Integer.signum(to - from) * (int) Math.min(span, steps);
        }

        /** 第几层 → 指针盘的档（0–7；层数多于 8 时高出去的都指在 7）。 */
        public static int dial(int index) {
            return Math.max(0, Math.min(DIAL_STOPS - 1, index));
        }

        /** 指针盘的样子：指针在它指向的那一块里（160° → 20°，前四档在左块、后四档在右块；与模板的生成脚本同一条）。 */
        public static String dialTemplate(Side side, int floor) {
            boolean needleLeft = floor < DIAL_STOPS / 2;
            if (side == Side.LEFT) {
                return needleLeft ? "lift/dial_left_" + floor : "lift/dial_left";
            }
            return needleLeft ? "lift/dial_right" : "lift/dial_right_" + floor;
        }

        /** 门开着的这一层：上一次有人（或刚开）是 {@code since}，此刻 {@code now}；别的层有人叫车（{@code wanted}）时等得短些。 */
        public static boolean shouldClose(long since, long now, boolean wanted) {
            return now - since >= (wanted ? MIN_OPEN_TICKS : DOOR_HOLD_TICKS);
        }

        /** 下一个去接哪一层：离 {@code atY} 那一层最近的叫车层（一样近取低的）；叫车的层都不在楼层表里了回 {@link Integer#MIN_VALUE}。 */
        public static int nextCall(List<Floor> floors, int atY, Set<Integer> calls) {
            int at = indexOf(floors, atY);
            int best = Integer.MIN_VALUE;
            int bestDist = Integer.MAX_VALUE;
            for (int y : calls) {
                int i = indexOf(floors, y);
                if (i < 0) {
                    continue;
                }
                int d = at < 0 ? i : Math.abs(i - at);
                if (d < bestDist) {
                    best = y;
                    bestDist = d;
                }
            }
            return best;
        }

        // ---------------------------------------------------------------- 楼层表

        /**
         * 扫一条井道：{@code [bottom, top)} 每一高度问井道那两格，认左扇的下半块、且它那一对门正好占这条井道（右扇落在另一格），从低到高。
         * 门朝向不同的层（艇甲板那一站门朝船尾）左扇落在另一格，照样是这一部的一层。
         */
        public static List<Floor> scan(Shaft shaft, int bottom, int top, Function<BlockPos, BlockState> at) {
            List<Floor> out = new ArrayList<>();
            for (int y = bottom; y < top; y++) {
                for (BlockPos p : List.of(shaft.first(y), shaft.second(y))) {
                    BlockState s = at.apply(p);
                    if (isLeftLower(s) && shaftOf(p, s.get(FACING)).equals(shaft)) {
                        out.add(new Floor(y, s.get(DECK), s.get(FACING), p.getX(), p.getZ()));
                        break;
                    }
                }
            }
            return List.copyOf(out);
        }

        /** 这一列里 y 那一层是第几层（从 0 数）；没有是 -1。 */
        public static int indexOf(List<Floor> floors, int y) {
            for (int i = 0; i < floors.size(); i++) {
                if (floors.get(i).y() == y) {
                    return i;
                }
            }
            return -1;
        }

        static Floor floorAt(List<Floor> floors, int y) {
            int i = indexOf(floors, y);
            return i < 0 ? null : floors.get(i);
        }

        /** 第几层叫什么：与面板上同一个函数（{@link LiftPanel#label}），到站那一句与按钮上写的一样。 */
        static Text name(List<Floor> floors, int index) {
            return LiftPanel.label(floors.get(index).deck().asString(), index);
        }

        // ---------------------------------------------------------------- 乘梯请求的核对

        /** 核对的结果。 */
        public enum Verdict {
            OK, SPECTATOR, IN_VOYAGE, TOO_SOON, NOT_A_GATE, NOT_IN_CAR, CAR_AWAY, NO_SUCH_FLOOR, SAME_FLOOR
        }

        /**
         * 一次乘梯请求里服务端要核对的全部事实。
         *
         * @param gateHere 请求里那一格此刻确实是电梯左扇的下半块
         * @param inCar    人此刻在那一层的轿厢范围里（{@link #carZone}）
         * @param carHere  虚拟轿厢此刻停在那一层（没在走）
         * @param floors   那一列此刻的楼层表（门不在时为空）
         * @param fromY    请求里那扇门的 y
         * @param target   要去第几层（从 0 数，下标进 {@code floors}）
         * @param targetY  客户端以为那一层在哪个 y：只用来认出「面板开着时楼层表变了」，不用来定落点
         */
        public record Request(boolean spectator, boolean inVoyage, long now, long last, boolean gateHere, boolean inCar,
                              boolean carHere, List<Floor> floors, int fromY, int target, int targetY) {
        }

        public static Verdict check(Request r) {
            if (r.spectator()) {
                return Verdict.SPECTATOR;
            }
            if (r.inVoyage()) {
                return Verdict.IN_VOYAGE;
            }
            if (r.last() != Long.MIN_VALUE && r.now() - r.last() < COOLDOWN_TICKS) {
                return Verdict.TOO_SOON;
            }
            if (!r.gateHere()) {
                return Verdict.NOT_A_GATE;
            }
            if (!r.inCar()) {
                return Verdict.NOT_IN_CAR;
            }
            if (!r.carHere()) {
                return Verdict.CAR_AWAY;
            }
            if (r.target() < 0 || r.target() >= r.floors().size() || r.floors().get(r.target()).y() != r.targetY()) {
                return Verdict.NO_SUCH_FLOOR;
            }
            if (r.floors().get(r.target()).y() == r.fromY()) {
                return Verdict.SAME_FLOOR;
            }
            return Verdict.OK;
        }

        // ---------------------------------------------------------------- 样子与轮廓

        /** 朝向 → y 旋转：模板正面朝南（大邮轮那一族墙面件的约定；与 {@link LinerLooks#rotateY} 同一个方向）。 */
        public static int yaw(Direction facing) {
            return switch (facing) {
                case WEST -> 90;
                case NORTH -> 180;
                case EAST -> 270;
                default -> 0;
            };
        }

        /** 栅门的模板名：{@code lift/gate_<left|right>_<closed|open>_<lower|upper>}。 */
        public static String gateTemplate(Side side, boolean open, DoubleBlockHalf half) {
            return "lift/gate_" + side.asString() + "_" + (open ? "open" : "closed") + "_"
                    + (half == DoubleBlockHalf.LOWER ? "lower" : "upper");
        }

        /**
         * 轮廓（模板坐标，正面朝南，左扇 = 外侧在 x 0；右扇照 x → 16 − x 镜像）。单测拿模板文件核对每个元件都包在里面。
         * 合着：门框到栅格那一层（上半连门楣，到 z 15.5）；开着：外侧那一叠 + 下半的轨 / 上半的门楣。
         */
        static final double[][] CLOSED_LOWER = {{0, 0, 9, 16, 16, 15}};
        static final double[][] CLOSED_UPPER = {{0, 0, 9, 16, 16, 15.5}};
        static final double[][] OPEN_LOWER = {{0, 0, 9, 4, 16, 15}, {0, 0, 9, 16, 1.5, 15}};
        static final double[][] OPEN_UPPER = {{0, 0, 9, 4, 16, 15}, {0, 13.5, 9, 16, 16, 15.5}};
        /** 开着时的碰撞箱只剩外侧那一叠：轨（1.5 像素高的一道槛）与门楣（下沿离地 1.84 格）都不挡人。 */
        static final double[][] OPEN_COLLISION = {{0, 0, 9, 4, 16, 15}};

        /** 召唤按钮与面板的轮廓（模板坐标，背面贴墙在 z 0）。 */
        static final double[][] CALL_BOX = {{5, 3, 0, 11, 13, 2.25}};
        static final double[][] PANEL_BOX = {{4, 2, 0, 12, 14, 1.5}};

        static double[][] gateBoxes(DoubleBlockHalf half, boolean open, boolean collision) {
            if (open && collision) {
                return OPEN_COLLISION;
            }
            if (half == DoubleBlockHalf.LOWER) {
                return open ? OPEN_LOWER : CLOSED_LOWER;
            }
            return open ? OPEN_UPPER : CLOSED_UPPER;
        }

        /** 右扇：x → 16 − x。 */
        static double[][] mirrored(double[][] boxes) {
            double[][] out = new double[boxes.length][];
            for (int i = 0; i < boxes.length; i++) {
                double[] b = boxes[i];
                out[i] = new double[]{16 - b[3], b[1], b[2], 16 - b[0], b[4], b[5]};
            }
            return out;
        }

        private static final Map<String, VoxelShape> SHAPES = new HashMap<>();

        static {
            for (Direction f : Direction.Type.HORIZONTAL) {
                for (DoubleBlockHalf half : DoubleBlockHalf.values()) {
                    for (Side side : Side.values()) {
                        for (boolean open : new boolean[]{false, true}) {
                            for (boolean collision : new boolean[]{false, true}) {
                                double[][] boxes = gateBoxes(half, open, collision);
                                SHAPES.put(gateKey(f, half, side, open, collision), shape(side == Side.LEFT ? boxes : mirrored(boxes), f));
                            }
                        }
                    }
                }
                SHAPES.put("call/" + f, shape(CALL_BOX, f));
                SHAPES.put("panel/" + f, shape(PANEL_BOX, f));
            }
        }

        private static String gateKey(Direction f, DoubleBlockHalf half, Side side, boolean open, boolean collision) {
            return f + "/" + half + "/" + side + "/" + open + "/" + collision;
        }

        /** 几个模板坐标的盒子转到 {@code facing} 合成一个形状（服务端线程与渲染线程都会来问，类载入时一次算好）。 */
        static VoxelShape shape(double[][] boxes, Direction facing) {
            VoxelShape shape = VoxelShapes.empty();
            for (double[] b : boxes) {
                double[] lo = LinerLooks.rotateY(b[0], b[2], yaw(facing));
                double[] hi = LinerLooks.rotateY(b[3], b[5], yaw(facing));
                shape = VoxelShapes.union(shape, VoxelShapes.cuboid(new Box(
                        Math.min(lo[0], hi[0]) / 16, b[1] / 16, Math.min(lo[1], hi[1]) / 16,
                        Math.max(lo[0], hi[0]) / 16, b[4] / 16, Math.max(lo[1], hi[1]) / 16)));
            }
            return shape.simplify();
        }

        static VoxelShape gateOutline(Direction facing, DoubleBlockHalf half, Side side, boolean open) {
            return SHAPES.get(gateKey(facing, half, side, open, false));
        }

        static VoxelShape gateCollision(Direction facing, DoubleBlockHalf half, Side side, boolean open) {
            return SHAPES.get(gateKey(facing, half, side, open, true));
        }

        static VoxelShape wallShape(double[][] boxes, Direction facing) {
            return SHAPES.get((boxes == CALL_BOX ? "call/" : "panel/") + facing);
        }

        /** 贴墙的件放在哪一面墙上：点的那一面（或附近）是一整面实心墙；朝向 = 背离墙。没有墙回 {@code null}（与壁灯同一个放法）。 */
        static Direction wallFacing(ItemPlacementContext ctx) {
            World world = ctx.getWorld();
            BlockPos pos = ctx.getBlockPos();
            for (Direction d : ctx.getPlacementDirections()) {
                if (d.getAxis().isHorizontal()) {
                    Direction facing = d.getOpposite();
                    BlockPos host = pos.offset(d);
                    if (world.getBlockState(host).isSideSolidFullSquare(world, host, facing)) {
                        return facing;
                    }
                }
            }
            return null;
        }
    }
}
