package io.github.heavyseasmc.mod.world.liner;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.util.ActionResult;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static io.github.heavyseasmc.mod.world.liner.LinerLooks.tex;

/**
 * 大邮轮的灯与家具（ADR-0063；样子 = ADR-0061 §4–§5 的细模定稿，模型与贴图由 {@code liner_props.py --write} 画好入库）。
 *
 * <p>跨几格的件（两格高的灯、2 × 2 的大桌、两格的沙发）像床、门那样：<b>一件物品摆出整件</b>，空间不够就摆不下；
 * <b>拆掉任何一格，整件一起没</b>（每一格都认得自己的搭档该在哪、是哪一块，搭档不在了自己就变成空气，一格传一格）。
 * 灯<b>右键开关</b>，亮着时发光（灯柱 15 · 落地灯 14 · 台灯 12；两格高的灯只有上面那一格发光）。
 * 顶灯与吊灯（ADR-0066）<b>只能挂在天花下</b>（摆的时候看正上方），整件往下长；吊灯只有灯身那几格发光（15）。
 * 轮廓与碰撞箱都照外形拼几个盒子（{@link LinerPropShapes}），没有一件是整块的。
 * 台灯放在大桌上时整件下沉 3 像素落在桌布上（{@code on_table}，看正下方那一格）。
 *
 * <p>模型一律<b>正面朝北</b>作画（与游戏自带方块的约定一致，物品栏里才看得到正面），与 {@link LinerBlock} 的「朝南作画」不同：
 * 这里的 y 旋转是 北 0 · 东 90 · 南 180 · 西 270。
 *
 * <p>纯规则（格怎么随朝向转、镜像换哪一块、整件从哪一格往哪边长）在 {@link Rules}，单测不用起游戏。
 */
public final class LinerProp extends Block implements LinerLooks.Styled {

    /** 一件里的哪一块；(x, z) = 它在模型里的格（模型正面朝北：x 往东、z 往南），y = 第几层。 */
    public enum Part implements StringIdentifiable {
        LOWER(0, 0, 0), UPPER(0, 1, 0),
        NW(0, 0, 0), NE(1, 0, 0), SW(0, 0, 1), SE(1, 0, 1),
        WEST(0, 0, 0), EAST(1, 0, 0),
        /** 水晶大吊灯（ADR-0066）：贴天花的吊杆那一格，正中 3 × 3 那一层的上面。 */
        CROWN(1, 1, 1),
        RING_NW(0, 0, 0), RING_N(1, 0, 0), RING_NE(2, 0, 0),
        RING_W(0, 0, 1), RING_C(1, 0, 1), RING_E(2, 0, 1),
        RING_SW(0, 0, 2), RING_S(1, 0, 2), RING_SE(2, 0, 2);

        final int x;
        final int y;
        final int z;

        Part(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public String asString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 摆法。 */
    public enum Kind {
        /** 两格高的灯（灯柱、落地灯）：下面一格 + 上面一格，上面那一格发光。 */
        TALL_LAMP,
        /** 一格的台灯：放在大桌上时下沉。 */
        TABLE_LAMP,
        /** 2 × 2 的大桌。 */
        GRAND_TABLE,
        /** 两格的沙发。 */
        SOFA,
        /** 一格的椅子。 */
        CHAIR,
        /** 一格的吸顶灯（ADR-0066）：只能挂在天花下。 */
        CEILING_LAMP,
        /** 两格高的吊灯：上面一格贴天花（摆的时候点中的那一格），下面一格是灯身、发光。 */
        CHANDELIER,
        /** 水晶大吊灯：贴天花的吊杆一格 + 下面 3 × 3 一层，共 10 格；那一层正中的十字五格发光。 */
        GRAND_CHANDELIER
    }

    public static final net.minecraft.state.property.DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final BooleanProperty LIT = Properties.LIT;
    /** 台灯：正下方是大桌（整件下沉 3 像素落在桌布上）。 */
    public static final BooleanProperty ON_TABLE = BooleanProperty.of("on_table");
    public static final EnumProperty<Part> TALL = EnumProperty.of("part", Part.class, Part.LOWER, Part.UPPER);
    public static final EnumProperty<Part> QUAD = EnumProperty.of("part", Part.class, Part.NW, Part.NE, Part.SW, Part.SE);
    public static final EnumProperty<Part> PAIR = EnumProperty.of("part", Part.class, Part.WEST, Part.EAST);
    public static final EnumProperty<Part> GRAND = EnumProperty.of("part", Part.class, Part.CROWN,
            Part.RING_NW, Part.RING_N, Part.RING_NE, Part.RING_W, Part.RING_C, Part.RING_E, Part.RING_SW, Part.RING_S, Part.RING_SE);

    /**
     * 一件道具的说明。
     *
     * @param model   模板名的词干（{@code template/prop/<词干>_…}）
     * @param texture 主贴图名（{@code prop/<名>}）；布不同的两件（米色 / 绿丝绒）只差这一张
     * @param glow    发光那一张贴图的词干（灯：{@code prop/<词干>_off|_lit}），别的件为 {@code null}
     * @param light   亮着时的光照等级
     */
    public record Spec(Kind kind, String model, String texture, String glow, int light) {
    }

    private static final ThreadLocal<Spec> PENDING = new ThreadLocal<>();

    private final Spec spec;
    private final EnumProperty<Part> part;
    private final Map<BlockState, VoxelShape> shapes = new ConcurrentHashMap<>();

    static LinerProp create(AbstractBlock.Settings settings, Spec spec) {
        PENDING.set(spec);
        try {
            return new LinerProp(settings, spec);
        } finally {
            PENDING.remove();
        }
    }

    private LinerProp(AbstractBlock.Settings settings, Spec spec) {
        super(settings);
        this.spec = spec;
        this.part = partProperty(spec.kind());
        BlockState s = getStateManager().getDefaultState().with(FACING, Direction.NORTH);
        if (part != null) {
            s = s.with(part, Rules.anchor(spec.kind()));
        }
        if (s.contains(LIT)) {
            s = s.with(LIT, true);
        }
        if (s.contains(ON_TABLE)) {
            s = s.with(ON_TABLE, false);
        }
        setDefaultState(s);
    }

    private static EnumProperty<Part> partProperty(Kind kind) {
        return switch (kind) {
            case TALL_LAMP, CHANDELIER -> TALL;
            case GRAND_TABLE -> QUAD;
            case SOFA -> PAIR;
            case GRAND_CHANDELIER -> GRAND;
            default -> null;
        };
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        Spec s = PENDING.get();
        builder.add(FACING);
        EnumProperty<Part> p = partProperty(s.kind());
        if (p != null) {
            builder.add(p);
        }
        if (Rules.isLamp(s.kind())) {
            builder.add(LIT);
        }
        if (s.kind() == Kind.TABLE_LAMP) {
            builder.add(ON_TABLE);
        }
    }

    public Spec spec() {
        return spec;
    }

    /**
     * 亮着时的光照：跨几格的灯只有灯身那几格发光（{@link Rules#glows}）。给方块设置的 {@code luminance} 用 ——
     * 方块状态在方块构造时就把光照算好存起来了，那时这个方块的 {@link #spec} 还没赋值，所以摆法由登记处直接传进来。
     */
    static int lightOf(Kind kind, BlockState s, int level) {
        if (!s.contains(LIT) || !s.get(LIT)) {
            return 0;
        }
        EnumProperty<Part> p = partProperty(kind);
        return p == null || Rules.glows(kind, s.get(p)) ? level : 0;
    }

    // ---------------------------------------------------------------- 样子

    @Override
    public LinerLooks.Look look(BlockState s) {
        String st = s.contains(LIT) && s.get(LIT) ? "lit" : "off";
        LinerLooks.Look base = switch (spec.kind()) {
            case TALL_LAMP -> s.get(TALL) == Part.LOWER
                    ? LinerLooks.look("prop/" + spec.model() + "_lower", tex("b", "prop/" + spec.model() + "_lower"))
                    : LinerLooks.look("prop/" + spec.model() + "_upper_" + st, tex("b", "prop/" + spec.model() + "_upper",
                    "g", "prop/" + spec.glow() + "_" + st));
            case TABLE_LAMP -> LinerLooks.look("prop/" + spec.model() + (s.get(ON_TABLE) ? "_sunk_" : "_") + st,
                    tex("b", "prop/" + spec.texture(), "g", "prop/" + spec.glow() + "_" + st));
            case GRAND_TABLE -> LinerLooks.look("prop/" + spec.model() + "_" + s.get(QUAD).asString(), tex("b", "prop/" + spec.texture()));
            case SOFA -> LinerLooks.look("prop/" + spec.model() + "_" + s.get(PAIR).asString(), tex("b", "prop/" + spec.texture()));
            case CHAIR -> LinerLooks.look("prop/" + spec.model(), tex("b", "prop/" + spec.texture()));
            case CEILING_LAMP -> LinerLooks.look("prop/" + spec.model() + "_" + st, tex("b", "prop/" + spec.texture(),
                    "g", "prop/" + spec.glow() + "_" + st));
            case CHANDELIER -> s.get(TALL) == Part.UPPER
                    ? LinerLooks.look("prop/" + spec.model() + "_upper", tex("b", "prop/" + spec.model() + "_upper"))
                    : LinerLooks.look("prop/" + spec.model() + "_lower_" + st, tex("b", "prop/" + spec.texture(),
                    "g", "prop/" + spec.glow() + "_" + st));
            case GRAND_CHANDELIER -> grandLook(s.get(GRAND), st);
        };
        return base.turned(yawOf(s.get(FACING)));
    }

    /** 水晶大吊灯的一格：吊杆 · 那一层正中的十字五格（斜件都在正中那一格的模型里）· 四个角（空模型，只占位）。 */
    private LinerLooks.Look grandLook(Part p, String st) {
        String m = "prop/" + spec.model();
        return switch (p) {
            case CROWN -> LinerLooks.look(m + "_top_" + st, tex("b", m + "_top", "g", m + "_top_glow_" + st));
            case RING_NW, RING_NE, RING_SW, RING_SE -> LinerLooks.look(m + "_corner", tex("b", "prop/" + spec.texture()));
            default -> LinerLooks.look(m + "_" + p.asString().substring("ring_".length()) + "_" + st,
                    tex("b", "prop/" + spec.texture(), "g", "prop/" + spec.glow() + "_" + st));
        };
    }

    @Override
    public LinerLooks.Look itemLook() {
        return switch (spec.kind()) {
            case TALL_LAMP -> LinerLooks.look("prop/" + spec.model() + "_item", tex("b", "prop/" + spec.model() + "_lower",
                    "u", "prop/" + spec.model() + "_upper", "g", "prop/" + spec.glow() + "_lit"));
            case CHANDELIER -> LinerLooks.look("prop/" + spec.model() + "_item", tex("b", "prop/" + spec.texture(),
                    "u", "prop/" + spec.model() + "_upper", "g", "prop/" + spec.glow() + "_lit"));
            case GRAND_CHANDELIER -> LinerLooks.look("prop/" + spec.model() + "_item", tex("b", "prop/" + spec.texture(),
                    "g", "prop/" + spec.glow() + "_lit", "u", "prop/" + spec.model() + "_top",
                    "h", "prop/" + spec.model() + "_top_glow_lit"));
            case GRAND_TABLE, SOFA -> LinerLooks.look("prop/" + spec.model() + "_item", tex("b", "prop/" + spec.texture()));
            default -> null;
        };
    }

    /** 朝向 → y 旋转：模型正面朝北；Minecraft 的 y 旋转从上往下看是顺时针（北 → 东 → 南 → 西）。 */
    static int yawOf(Direction facing) {
        return LinerConnect.index(facing) * 90;
    }

    // ---------------------------------------------------------------- 摆 · 拆 · 开关

    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        Direction facing = ctx.getHorizontalPlayerFacing().getOpposite();           // 正面朝着摆它的人
        BlockState s = getDefaultState().with(FACING, facing);
        BlockPos anchorPos = ctx.getBlockPos();
        World world = ctx.getWorld();
        // 顶灯与吊灯只能挂在天花下：点中的那一格（贴天花的那一块）正上方的底面中间那一块要是实的（与灯笼挂着时同一个判据）。
        //   只在摆的时候看；摆好之后天花被拆了灯也不掉（挖不动、不掉东西的装饰件，结构里放下时也不该一块块碎掉）
        if (Rules.hanging(spec.kind()) && !Block.sideCoversSmallSquare(world, anchorPos.up(), Direction.DOWN)) {
            return null;
        }
        if (part != null) {
            Part a = Rules.anchor(spec.kind());
            for (Part p : part.getValues()) {
                if (p == a) {
                    continue;
                }
                BlockPos other = Rules.offset(anchorPos, a, p, facing);
                if (world.isOutOfHeightLimit(other) || !world.getWorldBorder().contains(other)
                        || !world.getBlockState(other).canReplace(ctx)) {
                    return null;                                                    // 整件放不下就不摆（同床、门）
                }
            }
        }
        if (s.contains(ON_TABLE)) {
            s = s.with(ON_TABLE, isTable(world.getBlockState(anchorPos.down())));
        }
        return s;
    }

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.onPlaced(world, pos, state, placer, stack);
        if (part == null || world.isClient) {
            return;
        }
        Part here = state.get(part);
        for (Part p : part.getValues()) {
            if (p != here) {
                world.setBlockState(Rules.offset(pos, here, p, state.get(FACING)), state.with(part, p), Block.NOTIFY_ALL);
            }
        }
    }

    /** 搭档不在了（被拆、被 /setblock 换掉、被炸）这一格就跟着变成空气；台灯看正下方是不是大桌。 */
    @Override
    protected BlockState getStateForNeighborUpdate(BlockState state, Direction direction, BlockState neighborState,
                                                   WorldAccess world, BlockPos pos, BlockPos neighborPos) {
        if (part != null) {
            Part partner = Rules.partnerAt(state.get(part), direction, state.get(FACING), part.getValues());
            if (partner != null && !(neighborState.isOf(this) && neighborState.get(FACING) == state.get(FACING)
                    && neighborState.get(part) == partner)) {
                return net.minecraft.block.Blocks.AIR.getDefaultState();
            }
        }
        if (state.contains(ON_TABLE) && direction == Direction.DOWN) {
            return state.with(ON_TABLE, isTable(neighborState));
        }
        return state;
    }

    private static boolean isTable(BlockState s) {
        return s.getBlock() instanceof LinerProp p && p.spec.kind() == Kind.GRAND_TABLE;
    }

    /** 灯：右键开关（整件一起）。别的件右键没有反应。 */
    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (!state.contains(LIT)) {
            return ActionResult.PASS;
        }
        if (!world.isClient) {
            boolean lit = !state.get(LIT);
            for (BlockPos p : piece(state, pos)) {
                BlockState s = world.getBlockState(p);
                if (s.isOf(this)) {
                    world.setBlockState(p, s.with(LIT, lit), Block.NOTIFY_ALL);
                }
            }
            world.playSound(null, pos, SoundEvents.BLOCK_STONE_BUTTON_CLICK_ON, SoundCategory.BLOCKS, 0.3f, lit ? 0.65f : 0.5f);
        }
        return ActionResult.success(world.isClient);
    }

    /** 这一格所在的整件的每一格（单格的件就是它自己）。 */
    List<BlockPos> piece(BlockState state, BlockPos pos) {
        List<BlockPos> out = new ArrayList<>();
        if (part == null) {
            out.add(pos);
            return out;
        }
        Part here = state.get(part);
        for (Part p : part.getValues()) {
            out.add(Rules.offset(pos, here, p, state.get(FACING)));
        }
        return out;
    }

    // ---------------------------------------------------------------- 结构转动与镜像

    @Override
    protected BlockState rotate(BlockState state, BlockRotation rotation) {
        return state.with(FACING, rotation.rotate(state.get(FACING)));          // 整件一起转，每一块还是那一块
    }

    /** 镜像：整件照镜子 = 模型里左右对调（西 ↔ 东），朝向照游戏自带的做法转。推导见 {@link Rules#mirrored}。 */
    @Override
    protected BlockState mirror(BlockState state, BlockMirror mirror) {
        if (mirror == BlockMirror.NONE) {
            return state;
        }
        BlockState s = state.rotate(mirror.getRotation(state.get(FACING)));
        return part == null ? s : s.with(part, Rules.mirrored(state.get(part)));
    }

    // ---------------------------------------------------------------- 轮廓（也是碰撞箱）

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return shapes.computeIfAbsent(state, this::shapeOf);
    }

    private VoxelShape shapeOf(BlockState s) {
        Part p = part == null ? null : s.get(part);
        double sink = s.contains(ON_TABLE) && s.get(ON_TABLE) ? 3 : 0;
        VoxelShape shape = VoxelShapes.empty();
        for (double[] b : LinerPropShapes.boxes(spec.kind(), p)) {
            double[] lo = LinerLooks.rotateY(b[0], b[2], yawOf(s.get(FACING)));
            double[] hi = LinerLooks.rotateY(b[3], b[5], yawOf(s.get(FACING)));
            double y0 = Math.max(0, b[1] - sink);
            double y1 = Math.max(y0 + 0.5, b[4] - sink);
            shape = VoxelShapes.union(shape, VoxelShapes.cuboid(new Box(
                    Math.min(lo[0], hi[0]) / 16, y0 / 16, Math.min(lo[1], hi[1]) / 16,
                    Math.max(lo[0], hi[0]) / 16, y1 / 16, Math.max(lo[1], hi[1]) / 16)));
        }
        return shape.simplify();
    }

    // ---------------------------------------------------------------- 纯规则

    /** 不碰世界的那一半：单测直接测这里（{@code LinerPropRulesTest}）。 */
    public static final class Rules {

        private Rules() {
        }

        /**
         * 摆的时候点中的那一格放哪一块：落地的灯是下面那一格；吊灯是贴天花的那一格（整件往下长）；
         * 沙发、大桌是「离人近、在人左手」的那一块，整件往人的右手、往远处长。
         */
        public static Part anchor(Kind kind) {
            return switch (kind) {
                case TALL_LAMP -> Part.LOWER;
                case CHANDELIER -> Part.UPPER;
                case GRAND_CHANDELIER -> Part.CROWN;
                case GRAND_TABLE -> Part.NE;
                case SOFA -> Part.EAST;
                default -> null;
            };
        }

        /** 一件里有哪几块（与方块状态的 part 属性同一份清单；单格的件是空集）。单测按它把每一种、每一块都过一遍。 */
        public static Set<Part> parts(Kind kind) {
            return switch (kind) {
                case TALL_LAMP, CHANDELIER -> EnumSet.of(Part.LOWER, Part.UPPER);
                case GRAND_TABLE -> EnumSet.of(Part.NW, Part.NE, Part.SW, Part.SE);
                case SOFA -> EnumSet.of(Part.WEST, Part.EAST);
                case GRAND_CHANDELIER -> EnumSet.of(Part.CROWN, Part.RING_NW, Part.RING_N, Part.RING_NE, Part.RING_W, Part.RING_C,
                        Part.RING_E, Part.RING_SW, Part.RING_S, Part.RING_SE);
                default -> EnumSet.noneOf(Part.class);
            };
        }

        /** 有开关、会发光的那几种。 */
        public static boolean isLamp(Kind kind) {
            return switch (kind) {
                case TALL_LAMP, TABLE_LAMP, CEILING_LAMP, CHANDELIER, GRAND_CHANDELIER -> true;
                default -> false;
            };
        }

        /** 只能挂在天花下的那几种。 */
        public static boolean hanging(Kind kind) {
            return kind == Kind.CEILING_LAMP || kind == Kind.CHANDELIER || kind == Kind.GRAND_CHANDELIER;
        }

        /** 一件灯里亮着时发光的那几格：落地灯的灯头、吊灯的灯身、大吊灯那一层正中的十字五格（蜡烛灯都在这五格的模型里）。 */
        public static boolean glows(Kind kind, Part p) {
            return switch (kind) {
                case TALL_LAMP -> p == Part.UPPER;
                case CHANDELIER -> p == Part.LOWER;
                case GRAND_CHANDELIER -> p == Part.RING_N || p == Part.RING_W || p == Part.RING_C || p == Part.RING_E
                        || p == Part.RING_S;
                default -> true;
            };
        }

        /**
         * 模型里的格偏移 (dx, dz) 转到世界：正面朝北时不转；朝向每顺时针一格，(x, z) → (−z, x)
         * （北 (0, −1) → 东 (1, 0)，与方块状态 y 旋转同一个方向）。
         */
        public static int[] toWorld(int dx, int dz, int facingIndex) {
            int x = dx;
            int z = dz;
            for (int i = 0; i < (facingIndex & 3); i++) {
                int nx = -z;
                z = x;
                x = nx;
            }
            return new int[]{x, z};
        }

        /** 从 from 那一块所在的位置，到 to 那一块所在的位置。 */
        public static BlockPos offset(BlockPos pos, Part from, Part to, Direction facing) {
            int[] w = toWorld(to.x - from.x, to.z - from.z, LinerConnect.index(facing));
            return pos.add(w[0], to.y - from.y, w[1]);
        }

        /** 站在 here 那一块上，朝 direction 那一格应该是哪一块；那一格不在这一件里就是 {@code null}。 */
        public static Part partnerAt(Part here, Direction direction, Direction facing, java.util.Collection<Part> parts) {
            for (Part p : parts) {
                if (p == here) {
                    continue;
                }
                int[] w = toWorld(p.x - here.x, p.z - here.z, LinerConnect.index(facing));
                if (w[0] == direction.getOffsetX() && p.y - here.y == direction.getOffsetY() && w[1] == direction.getOffsetZ()) {
                    return p;
                }
            }
            return null;
        }

        /**
         * 镜像之后这一块变成哪一块。整件照镜子，世界里的偏移被翻了一下，朝向也被翻过去；换回模型里看，
         * 对哪一种朝向、哪一种镜子都是同一件事：<b>左右对调</b>（x → 宽 − 1 − x）—— 镜子里的沙发就是左右反过来的沙发。
         */
        public static Part mirrored(Part p) {
            return switch (p) {
                case NW -> Part.NE;
                case NE -> Part.NW;
                case SW -> Part.SE;
                case SE -> Part.SW;
                case WEST -> Part.EAST;
                case EAST -> Part.WEST;
                case RING_NW -> Part.RING_NE;
                case RING_NE -> Part.RING_NW;
                case RING_W -> Part.RING_E;
                case RING_E -> Part.RING_W;
                case RING_SW -> Part.RING_SE;
                case RING_SE -> Part.RING_SW;
                default -> p;
            };
        }
    }
}
