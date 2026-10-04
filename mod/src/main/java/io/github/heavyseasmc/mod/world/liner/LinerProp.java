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
 * 骑缝的吸顶灯（ADR-0068）两格或 2 × 2 一件，灯身落在接缝上，像沙发、大桌那样往人的右手与远处长。
 * 轮廓与碰撞箱都照外形拼几个盒子（{@link LinerPropShapes}），没有一件是整块的。
 * 台灯放在大桌上时整件下沉 3 像素落在桌布上（{@code on_table}，看正下方那一格），并往桌子正中斜挪 3 像素（{@code table_corner}，ADR-0071）。
 * 客房与阅览室的家具（ADR 草稿 furniture）：黄铜床 1 × 2（床头往远处长）· 衣柜与盥洗台一格宽两格高 · 书柜 2 × 2 竖着两格高 ·
 * 写字台两格宽 · 写字椅一格；壁灯一格、背贴墙（朝向从点中的墙定，墙拆了不掉，没有碰撞箱）。
 * A 甲板新家具（ADR 草稿 furnish）：壁炉与炉上件 3 宽 × 2 高（炉火右键开关、只有正中下面那一格发光）· 棕榈两格高 / 大棵三格高
 * （碰撞只算盆）· 藤编扶手椅 · 小圆桌一格、长椅两格 · 吧台 4 长 × 2 高（带台后酒架）。
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
        RING_SW(0, 0, 2), RING_S(1, 0, 2), RING_SE(2, 0, 2),
        /** 床（1 × 2）：床尾那一格（离摆的人近）与床头那一格（往远处长）。 */
        FOOT(0, 0, 0), HEAD(0, 0, 1),
        /** 书柜（2 × 2，竖着两格高）：下面一层用 {@link #WEST} · {@link #EAST}，上面一层是这两块。 */
        UPPER_WEST(0, 1, 0), UPPER_EAST(1, 1, 0),
        /** 三格高的件（大棵棕榈 · 高通风筒）：下面两格用 {@link #LOWER} · {@link #UPPER}，最上面一格是它。 */
        TOP(0, 2, 0),
        /**
         * 3 宽 × 2 高、背贴墙的件（壁炉 · 炉上件）：下面一层西 · 中 · 东（模型里由西往东），上面一层同样三块。
         * 不借 {@link #WEST} · {@link #EAST}：那两块是两格宽的件的第 0 · 1 格，镜像时也只对调这两块；3 宽的东在第 2 格、镜像是 0 ↔ 2。
         */
        WIDE_WEST(0, 0, 0), WIDE_MID(1, 0, 0), WIDE_EAST(2, 0, 0),
        WIDE_UPPER_WEST(0, 1, 0), WIDE_UPPER_MID(1, 1, 0), WIDE_UPPER_EAST(2, 1, 0),
        /** 3 宽 × 3 高的件（魔镜，2026-10-04 放大）：下面两层同 3 宽 × 2 高那一族，最上面一层是这三块；镜像是西 ↔ 东。 */
        WIDE_TOP_WEST(0, 2, 0), WIDE_TOP_MID(1, 2, 0), WIDE_TOP_EAST(2, 2, 0),
        /** 吧台（4 长 × 2 高）：下面一层第 1–4 格（模型里由西往东），上面一层（台后酒架）同样四块；镜像是 1 ↔ 4 · 2 ↔ 3。 */
        BAY_1(0, 0, 0), BAY_2(1, 0, 0), BAY_3(2, 0, 0), BAY_4(3, 0, 0),
        UPPER_BAY_1(0, 1, 0), UPPER_BAY_2(1, 1, 0), UPPER_BAY_3(2, 1, 0), UPPER_BAY_4(3, 1, 0),
        /**
         * 吊艇架（艇甲板设备，ADR 草稿 deckgear）：铁座两格（锚点 {@link #BASE} · 往舷内一格），其余顺着斜臂往上、往舷外一格不落 ——
         * 每一格都与上一格面贴面挨着，「拆一格整件没」（一格传一格）才传得到头。模型正面朝北 = 舷外，所以往舷外是 −z。
         */
        BASE(0, 0, 0), BASE_IN(0, 0, 1), QUADRANT(0, 1, 0), ARM_A(0, 2, 0), ARM_B(0, 3, 0), ARM_C(0, 3, -1),
        ARM_D(0, 4, -1), ARM_E(0, 5, -1), ARM_F(0, 5, -2), ARM_HEAD(0, 6, -2),
        /**
         * 开局的钟（门形钟架，2 宽 × 6 高，ADR-0084 第三轮）：西一列（模型 x 0）与东一列（x 1），由下往上 0–5；钟挂在横梁正中（两列的接缝上）。
         * 镜像是西 ↔ 东。
         */
        BELL_W0(0, 0, 0), BELL_W1(0, 1, 0), BELL_W2(0, 2, 0), BELL_W3(0, 3, 0), BELL_W4(0, 4, 0), BELL_W5(0, 5, 0),
        BELL_E0(1, 0, 0), BELL_E1(1, 1, 0), BELL_E2(1, 2, 0), BELL_E3(1, 3, 0), BELL_E4(1, 4, 0), BELL_E5(1, 5, 0);

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
        GRAND_CHANDELIER,
        /** 骑缝的吸顶灯（ADR-0068）：两格一件，灯身正落在两格的接缝上 —— 双数宽的走廊、客房才挂得到正中。两格都发光。 */
        CEILING_PAIR,
        /** 骑缝的吸顶灯，2 × 2 一件：灯身落在四格的交点上（两个方向都是双数宽的小屋）。四格都发光。 */
        CEILING_QUAD,
        /** 黄铜床（1 × 2）：床尾在点中的那一格，床头往远处长（同游戏自带的床）。 */
        BED,
        /** 衣柜（一格宽、两格高）：背贴墙。 */
        WARDROBE,
        /** 盥洗台（一格宽、两格高，连镜子）：背贴墙。 */
        WASHSTAND,
        /**
         * 魔镜（ADR-0065 · ADR-0083；2026-10-04 放大到两格宽、三格高，嵌进墙里的那一版）：占 3 宽 × 3 高，框在正中两格、
         * 两侧那两列各带半边框（主景正中是一格，两格宽的东西只能这样居中）；背贴墙、镜面朝摆它的人。
         * 点中的那一格是下面一层正中（{@link Part#WIDE_MID}），往人的左右手各长一格、往上长两层。右键穿过去（{@code MagicMirror}）。
         */
        MIRROR,
        /** 书柜（2 × 2，竖着两格高）：往人的右手与上面长。 */
        BOOKCASE,
        /** 写字台（两格宽、两个座位）：同沙发，往人的右手长。 */
        WRITING_TABLE,
        /** 写字椅（一格）。 */
        WRITING_CHAIR,
        /**
         * 壁灯（一格，ADR-0069 §4 倾向 A）：贴在墙前那一格、背贴墙，朝向就是离墙的方向；摆的时候点中的那一面墙要是实的，
         * 摆好之后墙拆了灯也不掉（与檐口、腰线这些挂墙件相同）。右键开关；没有碰撞箱（同游戏自带的墙上火把：走廊两格宽，不碰头）。
         */
        SCONCE,
        /**
         * 壁炉（3 宽 × 2 高、背贴墙，ADR 草稿 furnish）：往人的右手与上面长；炉火右键开关（默认亮），只有正中下面那一格发光
         * （炭与火苗都在那一格的模型里）。灭着时模型里没有火苗、只剩冷炭。
         */
        FIREPLACE,
        /** 炉上件（描金框镜 · 桃花心木框油画；3 宽 × 2 高、背贴墙）：挂在壁炉上面两排，底框往下伸进壁炉那两排 4.5 像素、坐在壁炉台上。 */
        OVERMANTEL,
        /** 盆栽棕榈（两格高）：碰撞只算盆（叶子伸出这一格、穿得过去）。 */
        PALM,
        /** 大棵棕榈（三格高）：同上。 */
        PALM_TALL,
        /** 藤编扶手椅（一格）。 */
        WICKER_CHAIR,
        /** 藤编小圆桌（一格）。 */
        WICKER_TABLE,
        /** 藤编长椅（两格）：同沙发，往人的右手长。 */
        WICKER_SETTEE,
        /** 吧台（4 长 × 2 高，带台后酒架、背贴墙）：往人的右手与上面长。 */
        BAR_COUNTER,
        /**
         * 吊艇架（四分圆摇臂式，ADR-0080 §7 甲）：10 格，正面（模型北）朝舷外，臂倒 22.5° 伸出去，臂头在铁座外 2.5 格、上 6.9 格。
         * 艇在哪一边由 {@link #BOAT_SIDE} 定（右手是画的那一份，左手是镜像那一份）。
         */
        DAVIT,
        /** 喇叭口通风筒（高，三格）：喇叭口朝正面。 */
        VENTILATOR,
        /** 喇叭口通风筒（矮，两格）。 */
        VENTILATOR_SHORT,
        /**
         * 开局的钟（门形钟架：两根白漆柱、柚木横梁、黄铜船钟挂在正中；2 宽 × 6 高，ADR-0084 第三轮）：正面（模型北）朝演习艇，
         * 往人的右手与上面长（同书柜）。右键敲钟：演习艇旁那一口交给 {@link DrillSkiff#ringBell}（坐在艇里 = 开阵容面板）。
         */
        DRILL_BELL
    }

    /** 吊艇架的艇在哪一边：站在吊艇架后面、面朝舷外（模型的正面）看，艇在右手（模型 +x，画的那一份）还是左手（镜像那一份）。 */
    public enum BoatSide implements StringIdentifiable {
        LEFT, RIGHT;

        @Override
        public String asString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static final net.minecraft.state.property.DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final BooleanProperty LIT = Properties.LIT;
    /** 台灯：正下方是大桌（整件下沉 3 像素落在桌布上）。 */
    public static final BooleanProperty ON_TABLE = BooleanProperty.of("on_table");
    /**
     * 台灯在大桌上往哪边挪（ADR-0071）：大桌 2 × 2、桌布是圆的，台灯总落在桌子的某一个角格上，摆在格子正中底座就有一角伸出布边
     * （用户 2026-10-03「桌子上台灯的脚有一部分悬空了」）—— 于是往桌子正中斜挪 3 像素。值是<b>台灯自己模型里</b>的方向
     * （NW = 往模型的 −x −z），由正下方那块桌子是哪一块、朝哪算出来（{@link Rules#tableCorner}）；不在桌上时不起作用。
     */
    public static final EnumProperty<Part> TABLE_CORNER = EnumProperty.of("table_corner", Part.class, Part.NW, Part.NE, Part.SW, Part.SE);
    /** 台灯在桌上斜挪多少像素（每个方向）：3 —— 底座 10 × 6、整件仍在这一格里，四角都落在 30 像素的圆桌布上（liner_props.py 有判据）。 */
    static final int TABLE_SHIFT = 3;
    public static final EnumProperty<Part> TALL = EnumProperty.of("part", Part.class, Part.LOWER, Part.UPPER);
    public static final EnumProperty<Part> QUAD = EnumProperty.of("part", Part.class, Part.NW, Part.NE, Part.SW, Part.SE);
    public static final EnumProperty<Part> PAIR = EnumProperty.of("part", Part.class, Part.WEST, Part.EAST);
    public static final EnumProperty<Part> GRAND = EnumProperty.of("part", Part.class, Part.CROWN,
            Part.RING_NW, Part.RING_N, Part.RING_NE, Part.RING_W, Part.RING_C, Part.RING_E, Part.RING_SW, Part.RING_S, Part.RING_SE);
    public static final EnumProperty<Part> BED_PART = EnumProperty.of("part", Part.class, Part.FOOT, Part.HEAD);
    public static final EnumProperty<Part> SHELF = EnumProperty.of("part", Part.class, Part.WEST, Part.EAST, Part.UPPER_WEST, Part.UPPER_EAST);
    public static final EnumProperty<Part> TALL3 = EnumProperty.of("part", Part.class, Part.LOWER, Part.UPPER, Part.TOP);
    public static final EnumProperty<Part> WIDE = EnumProperty.of("part", Part.class, Part.WIDE_WEST, Part.WIDE_MID, Part.WIDE_EAST,
            Part.WIDE_UPPER_WEST, Part.WIDE_UPPER_MID, Part.WIDE_UPPER_EAST);
    public static final EnumProperty<Part> WIDE3 = EnumProperty.of("part", Part.class, Part.WIDE_WEST, Part.WIDE_MID, Part.WIDE_EAST,
            Part.WIDE_UPPER_WEST, Part.WIDE_UPPER_MID, Part.WIDE_UPPER_EAST, Part.WIDE_TOP_WEST, Part.WIDE_TOP_MID, Part.WIDE_TOP_EAST);
    public static final EnumProperty<Part> BAR = EnumProperty.of("part", Part.class, Part.BAY_1, Part.BAY_2, Part.BAY_3, Part.BAY_4,
            Part.UPPER_BAY_1, Part.UPPER_BAY_2, Part.UPPER_BAY_3, Part.UPPER_BAY_4);
    public static final EnumProperty<Part> DAVIT_PART = EnumProperty.of("part", Part.class, Part.BASE, Part.BASE_IN, Part.QUADRANT,
            Part.ARM_A, Part.ARM_B, Part.ARM_C, Part.ARM_D, Part.ARM_E, Part.ARM_F, Part.ARM_HEAD);
    /** 吊艇架：艇在哪一边（见 {@link BoatSide}）。结构照镜子时跟着换（{@link #mirror}）。 */
    public static final EnumProperty<BoatSide> BOAT_SIDE = EnumProperty.of("boat_side", BoatSide.class);
    public static final EnumProperty<Part> BELL_PART = EnumProperty.of("part", Part.class, Part.BELL_W0, Part.BELL_W1, Part.BELL_W2,
            Part.BELL_W3, Part.BELL_W4, Part.BELL_W5, Part.BELL_E0, Part.BELL_E1, Part.BELL_E2, Part.BELL_E3, Part.BELL_E4, Part.BELL_E5);

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
    private final Map<BlockState, VoxelShape> collisions = new ConcurrentHashMap<>();

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
            s = s.with(ON_TABLE, false).with(TABLE_CORNER, Part.NW);
        }
        if (s.contains(BOAT_SIDE)) {
            s = s.with(BOAT_SIDE, BoatSide.RIGHT);
        }
        setDefaultState(s);
    }

    private static EnumProperty<Part> partProperty(Kind kind) {
        return switch (kind) {
            case TALL_LAMP, CHANDELIER, WARDROBE, WASHSTAND, PALM, VENTILATOR_SHORT -> TALL;
            case MIRROR -> WIDE3;
            case GRAND_TABLE, CEILING_QUAD -> QUAD;
            case SOFA, CEILING_PAIR, WRITING_TABLE, WICKER_SETTEE -> PAIR;
            case GRAND_CHANDELIER -> GRAND;
            case BED -> BED_PART;
            case BOOKCASE -> SHELF;
            case PALM_TALL, VENTILATOR -> TALL3;
            case FIREPLACE, OVERMANTEL -> WIDE;
            case BAR_COUNTER -> BAR;
            case DAVIT -> DAVIT_PART;
            case DRILL_BELL -> BELL_PART;
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
            builder.add(ON_TABLE, TABLE_CORNER);
        }
        if (s.kind() == Kind.DAVIT) {
            builder.add(BOAT_SIDE);
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
            case TABLE_LAMP -> LinerLooks.look("prop/" + spec.model()
                            + (s.get(ON_TABLE) ? "_sunk_" + s.get(TABLE_CORNER).asString() + "_" : "_") + st,
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
            case CEILING_PAIR -> LinerLooks.look("prop/" + spec.model() + "_" + s.get(PAIR).asString() + "_" + st,
                    tex("b", "prop/" + spec.texture(), "g", "prop/" + spec.glow() + "_" + st));
            case CEILING_QUAD -> LinerLooks.look("prop/" + spec.model() + "_" + s.get(QUAD).asString() + "_" + st,
                    tex("b", "prop/" + spec.texture(), "g", "prop/" + spec.glow() + "_" + st));
            // 客房与阅览室的家具（ADR 草稿 furniture）：每一块一个模板，贴图整件一张（书柜的书架里面另一张 #k）
            case WARDROBE, WASHSTAND -> LinerLooks.look("prop/" + spec.model() + "_" + s.get(TALL).asString(), tex("b", "prop/" + spec.texture()));
            case MIRROR -> LinerLooks.look("prop/" + spec.model() + "_" + s.get(WIDE3).asString(), tex("b", "prop/" + spec.texture()));
            case BED -> LinerLooks.look("prop/" + spec.model() + "_" + s.get(BED_PART).asString(), tex("b", "prop/" + spec.texture()));
            case BOOKCASE -> LinerLooks.look("prop/" + spec.model() + "_" + s.get(SHELF).asString(),
                    tex("b", "prop/" + spec.texture(), "k", "prop/" + spec.texture() + "_shelves"));
            case WRITING_TABLE -> LinerLooks.look("prop/" + spec.model() + "_" + s.get(PAIR).asString(), tex("b", "prop/" + spec.texture()));
            case WRITING_CHAIR -> LinerLooks.look("prop/" + spec.model(), tex("b", "prop/" + spec.texture()));
            case SCONCE -> LinerLooks.look("prop/" + spec.model() + "_" + st, tex("b", "prop/" + spec.texture(),
                    "g", "prop/" + spec.glow() + "_" + st));
            // A 甲板新家具（ADR 草稿 furnish）：大件一张主贴图（#b）+ 细节一张（#k）；壁炉的炭与火在发光那一张（#g，亮 / 灭两张），
            //   灭着那一份的模板里没有火苗（模板名带 _off / _lit）；吧台的酒瓶另一张（#w）
            case FIREPLACE -> LinerLooks.look("prop/" + spec.model() + "_" + s.get(WIDE).asString() + "_" + st, tex("b", "prop/" + spec.texture(),
                    "k", "prop/" + spec.texture() + "_detail", "g", "prop/" + spec.glow() + "_" + st));
            case OVERMANTEL -> LinerLooks.look("prop/" + spec.model() + "_" + s.get(WIDE).asString(), tex("b", "prop/" + spec.texture(),
                    "k", "prop/" + spec.texture() + "_detail"));
            case PALM -> LinerLooks.look("prop/" + spec.model() + "_" + s.get(TALL).asString(), tex("b", "prop/" + spec.texture()));
            case PALM_TALL -> LinerLooks.look("prop/" + spec.model() + "_" + s.get(TALL3).asString(), tex("b", "prop/" + spec.texture()));
            case WICKER_CHAIR, WICKER_TABLE -> LinerLooks.look("prop/" + spec.model(), tex("b", "prop/" + spec.texture()));
            case WICKER_SETTEE -> LinerLooks.look("prop/" + spec.model() + "_" + s.get(PAIR).asString(), tex("b", "prop/" + spec.texture()));
            case BAR_COUNTER -> LinerLooks.look("prop/" + spec.model() + "_" + s.get(BAR).asString(), tex("b", "prop/" + spec.texture(),
                    "k", "prop/" + spec.texture() + "_detail", "w", "prop/" + spec.texture() + "_bottles"));
            // 艇甲板设备（ADR 草稿 deckgear）：白漆一张 #b、铁与吊索 / 喇叭与口一张 #k；吊艇架艇在左手时用镜像那一份模板（_m）
            case DAVIT -> LinerLooks.look("prop/" + spec.model() + "_" + s.get(DAVIT_PART).asString()
                            + (s.get(BOAT_SIDE) == BoatSide.LEFT ? "_m" : ""),
                    tex("b", "prop/" + spec.texture(), "k", "prop/" + spec.texture() + "_iron"));
            case VENTILATOR -> LinerLooks.look("prop/" + spec.model() + "_" + s.get(TALL3).asString(),
                    tex("b", "prop/" + spec.texture(), "k", "prop/" + spec.texture() + "_mouth"));
            case VENTILATOR_SHORT -> LinerLooks.look("prop/" + spec.model() + "_" + s.get(TALL).asString(),
                    tex("b", "prop/" + spec.texture(), "k", "prop/" + spec.texture() + "_mouth"));
            // 开局的钟：白漆柱与柚木横梁一张 #b、黄铜钟与铁件、钟绳一张 #k
            case DRILL_BELL -> LinerLooks.look("prop/" + spec.model() + "_" + s.get(BELL_PART).asString(),
                    tex("b", "prop/" + spec.texture(), "k", "prop/" + spec.texture() + "_brass"));
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
            // 骑缝灯在物品栏里就是那一盏灯（整件只是挪了半格）
            case CEILING_PAIR, CEILING_QUAD -> LinerLooks.look("prop/" + spec.model() + "_item", tex("b", "prop/" + spec.texture(),
                    "g", "prop/" + spec.glow() + "_lit"));
            case BED, WARDROBE, WASHSTAND, MIRROR, WRITING_TABLE -> LinerLooks.look("prop/" + spec.model() + "_item", tex("b", "prop/" + spec.texture()));
            case BOOKCASE -> LinerLooks.look("prop/" + spec.model() + "_item", tex("b", "prop/" + spec.texture(),
                    "k", "prop/" + spec.texture() + "_shelves"));
            // A 甲板新家具：整件缩小（吧台 4 格长，物品是整件再缩一半的那一份）
            case FIREPLACE -> LinerLooks.look("prop/" + spec.model() + "_item", tex("b", "prop/" + spec.texture(),
                    "k", "prop/" + spec.texture() + "_detail", "g", "prop/" + spec.glow() + "_lit"));
            case OVERMANTEL -> LinerLooks.look("prop/" + spec.model() + "_item", tex("b", "prop/" + spec.texture(),
                    "k", "prop/" + spec.texture() + "_detail"));
            case PALM, PALM_TALL, WICKER_SETTEE -> LinerLooks.look("prop/" + spec.model() + "_item", tex("b", "prop/" + spec.texture()));
            case BAR_COUNTER -> LinerLooks.look("prop/" + spec.model() + "_item", tex("b", "prop/" + spec.texture(),
                    "k", "prop/" + spec.texture() + "_detail", "w", "prop/" + spec.texture() + "_bottles"));
            case DAVIT -> LinerLooks.look("prop/" + spec.model() + "_item", tex("b", "prop/" + spec.texture(),
                    "k", "prop/" + spec.texture() + "_iron"));
            case VENTILATOR, VENTILATOR_SHORT -> LinerLooks.look("prop/" + spec.model() + "_item", tex("b", "prop/" + spec.texture(),
                    "k", "prop/" + spec.texture() + "_mouth"));
            case DRILL_BELL -> LinerLooks.look("prop/" + spec.model() + "_item", tex("b", "prop/" + spec.texture(),
                    "k", "prop/" + spec.texture() + "_brass"));
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
        if (spec.kind() == Kind.SCONCE) {
            return sconcePlacement(ctx);
        }
        Direction facing = ctx.getHorizontalPlayerFacing().getOpposite();           // 正面朝着摆它的人
        BlockState s = getDefaultState().with(FACING, facing);
        BlockPos anchorPos = ctx.getBlockPos();
        World world = ctx.getWorld();
        // 顶灯与吊灯只能挂在天花下：贴天花的那几格（整件最上面一层；骑缝灯是每一格）正上方的底面中间那一块要是实的
        //   （与灯笼挂着时同一个判据）。只在摆的时候看；摆好之后天花被拆了灯也不掉（挖不动、不掉东西的装饰件，
        //   结构里放下时也不该一块块碎掉）
        if (Rules.hanging(spec.kind())) {
            for (BlockPos top : topLayer(anchorPos, facing)) {
                if (!Block.sideCoversSmallSquare(world, top.up(), Direction.DOWN)) {
                    return null;
                }
            }
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
            s = onTable(s, world.getBlockState(anchorPos.down()));
        }
        return s;
    }

    /**
     * 壁灯贴到哪面墙上（照游戏自带的墙上火把）：按人看的方向依次试四个水平方向，那一边的那一格朝着灯的那一面是整面实的，
     * 灯就背贴着它、朝向离墙的那一边。点中地面或天花时也照这个顺序找身边的墙；四面都没有墙就不摆。
     */
    private BlockState sconcePlacement(ItemPlacementContext ctx) {
        World world = ctx.getWorld();
        BlockPos pos = ctx.getBlockPos();
        for (Direction d : ctx.getPlacementDirections()) {
            if (d.getAxis().isHorizontal()) {
                Direction facing = d.getOpposite();
                BlockPos host = pos.offset(d);
                if (world.getBlockState(host).isSideSolidFullSquare(world, host, facing)) {
                    return getDefaultState().with(FACING, facing);
                }
            }
        }
        return null;
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
            return onTable(state, neighborState);
        }
        return state;
    }

    /** 台灯按正下方那一格定：是不是在大桌上、在桌上的话往桌子正中哪边挪。 */
    private static BlockState onTable(BlockState lamp, BlockState below) {
        if (!isTable(below)) {
            return lamp.with(ON_TABLE, false);
        }
        return lamp.with(ON_TABLE, true)
                .with(TABLE_CORNER, Rules.tableCorner(below.get(QUAD), below.get(FACING), lamp.get(FACING)));
    }

    /** 整件最上面那一层的每一格（单格的件就是点中的那一格）。 */
    private List<BlockPos> topLayer(BlockPos anchorPos, Direction facing) {
        List<BlockPos> out = new ArrayList<>();
        if (part == null) {
            out.add(anchorPos);
            return out;
        }
        Part a = Rules.anchor(spec.kind());
        int top = part.getValues().stream().mapToInt(p -> p.y).max().orElse(0);
        for (Part p : part.getValues()) {
            if (p.y == top) {
                out.add(Rules.offset(anchorPos, a, p, facing));
            }
        }
        return out;
    }

    private static boolean isTable(BlockState s) {
        return s.getBlock() instanceof LinerProp p && p.spec.kind() == Kind.GRAND_TABLE;
    }

    /**
     * 灯：右键开关（整件一起）。魔镜：右键穿过去（{@link io.github.heavyseasmc.mod.world.MagicMirror}）。
     * 开局的钟：右键敲钟（{@link DrillSkiff#ringBell}：响一声；演习艇旁那一口、坐在艇里的人敲 = 开阵容面板）。别的件右键没有反应。
     */
    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (spec.kind() == Kind.DRILL_BELL) {
            return DrillSkiff.ringBell(world, pos, player);
        }
        if (spec.kind() == Kind.MIRROR) {
            // 点中哪一格都一样：交给 MagicMirror 的是下面一层正中那一格（镜面正中的正下方）
            BlockPos centre = Rules.offset(pos, state.get(WIDE3), Part.WIDE_MID, state.get(FACING));
            return io.github.heavyseasmc.mod.world.MagicMirror.use(world, centre, state.get(FACING), player);
        }
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
        if (s.contains(TABLE_CORNER)) {
            s = s.with(TABLE_CORNER, Rules.mirrored(state.get(TABLE_CORNER)));          // 模型里的方向，照镜子同样是左右对调
        }
        if (s.contains(BOAT_SIDE)) {
            s = s.with(BOAT_SIDE, Rules.mirrored(state.get(BOAT_SIDE)));                // 吊艇架：艇换到另一只手（模板换成镜像那一份）
        }
        return part == null ? s : s.with(part, Rules.mirrored(state.get(part)));
    }

    // ---------------------------------------------------------------- 轮廓（也是碰撞箱）

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return shapes.computeIfAbsent(state, this::shapeOf);
    }

    /**
     * 碰撞箱就是轮廓（Minecraft 默认），只有壁灯没有：同游戏自带的墙上火把，两格宽的走廊里走过去不碰头。
     * 棕榈另算（{@link LinerPropShapes#collision}）：碰撞只算盆，叶子点得中（轮廓有）但穿得过去。
     */
    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        if (spec.kind() == Kind.SCONCE) {
            return VoxelShapes.empty();
        }
        if (LinerPropShapes.collision(spec.kind(), part == null ? null : state.get(part)) != null) {
            return collisions.computeIfAbsent(state, s -> shapeOf(s, LinerPropShapes.collision(spec.kind(), part == null ? null : s.get(part))));
        }
        return super.getCollisionShape(state, world, pos, context);
    }

    private VoxelShape shapeOf(BlockState s) {
        return shapeOf(s, LinerPropShapes.boxes(spec.kind(), part == null ? null : s.get(part)));
    }

    private VoxelShape shapeOf(BlockState s, List<double[]> boxes) {
        boolean onTable = s.contains(ON_TABLE) && s.get(ON_TABLE);
        double sink = onTable ? 3 : 0;
        // 在桌上的台灯往桌子正中斜挪（模型里的方向，转朝向之前挪）
        double sx = onTable ? TABLE_SHIFT * (s.get(TABLE_CORNER).x == 0 ? -1 : 1) : 0;
        double sz = onTable ? TABLE_SHIFT * (s.get(TABLE_CORNER).z == 0 ? -1 : 1) : 0;
        boolean flip = s.contains(BOAT_SIDE) && s.get(BOAT_SIDE) == BoatSide.LEFT;      // 吊艇架镜像那一份：盒子也左右对调
        VoxelShape shape = VoxelShapes.empty();
        for (double[] b0 : boxes) {
            double[] b = flip ? new double[]{16 - b0[3], b0[1], b0[2], 16 - b0[0], b0[4], b0[5]} : b0;
            double[] lo = LinerLooks.rotateY(b[0] + sx, b[2] + sz, yawOf(s.get(FACING)));
            double[] hi = LinerLooks.rotateY(b[3] + sx, b[5] + sz, yawOf(s.get(FACING)));
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
                case TALL_LAMP, WARDROBE, WASHSTAND, PALM, PALM_TALL, VENTILATOR, VENTILATOR_SHORT -> Part.LOWER;
                case MIRROR -> Part.WIDE_MID;                                       // 左右对称：点中的是正中，镜子立在人面前（不偏向一侧）
                case DRILL_BELL -> Part.BELL_E0;                                     // 同书柜：点中的是下面一层人左手那一块，往人的右手与上面长
                case DAVIT -> Part.BASE;                                            // 铁座靠舷外那一格；往舷内一格、往上、往舷外（正面）长
                case CHANDELIER -> Part.UPPER;
                case GRAND_CHANDELIER -> Part.CROWN;
                case GRAND_TABLE, CEILING_QUAD -> Part.NE;
                case SOFA, CEILING_PAIR, WRITING_TABLE, BOOKCASE, WICKER_SETTEE -> Part.EAST;      // 书柜：点中的是下面一层人左手那一块
                case BED -> Part.FOOT;
                case FIREPLACE, OVERMANTEL -> Part.WIDE_EAST;                       // 下面一层人左手那一块，往人的右手（模型的西）与上面长
                case BAR_COUNTER -> Part.BAY_4;
                default -> null;
            };
        }

        /** 一件里有哪几块（与方块状态的 part 属性同一份清单；单格的件是空集）。单测按它把每一种、每一块都过一遍。 */
        public static Set<Part> parts(Kind kind) {
            return switch (kind) {
                case TALL_LAMP, CHANDELIER, WARDROBE, WASHSTAND, PALM, VENTILATOR_SHORT -> EnumSet.of(Part.LOWER, Part.UPPER);
                case MIRROR -> EnumSet.of(Part.WIDE_WEST, Part.WIDE_MID, Part.WIDE_EAST, Part.WIDE_UPPER_WEST, Part.WIDE_UPPER_MID,
                        Part.WIDE_UPPER_EAST, Part.WIDE_TOP_WEST, Part.WIDE_TOP_MID, Part.WIDE_TOP_EAST);
                case PALM_TALL, VENTILATOR -> EnumSet.of(Part.LOWER, Part.UPPER, Part.TOP);
                case FIREPLACE, OVERMANTEL -> EnumSet.of(Part.WIDE_WEST, Part.WIDE_MID, Part.WIDE_EAST,
                        Part.WIDE_UPPER_WEST, Part.WIDE_UPPER_MID, Part.WIDE_UPPER_EAST);
                case BAR_COUNTER -> EnumSet.of(Part.BAY_1, Part.BAY_2, Part.BAY_3, Part.BAY_4,
                        Part.UPPER_BAY_1, Part.UPPER_BAY_2, Part.UPPER_BAY_3, Part.UPPER_BAY_4);
                case DAVIT -> EnumSet.of(Part.BASE, Part.BASE_IN, Part.QUADRANT, Part.ARM_A, Part.ARM_B, Part.ARM_C, Part.ARM_D,
                        Part.ARM_E, Part.ARM_F, Part.ARM_HEAD);
                case DRILL_BELL -> EnumSet.of(Part.BELL_W0, Part.BELL_W1, Part.BELL_W2, Part.BELL_W3, Part.BELL_W4, Part.BELL_W5,
                        Part.BELL_E0, Part.BELL_E1, Part.BELL_E2, Part.BELL_E3, Part.BELL_E4, Part.BELL_E5);
                case GRAND_TABLE, CEILING_QUAD -> EnumSet.of(Part.NW, Part.NE, Part.SW, Part.SE);
                case SOFA, CEILING_PAIR, WRITING_TABLE, WICKER_SETTEE -> EnumSet.of(Part.WEST, Part.EAST);
                case BED -> EnumSet.of(Part.FOOT, Part.HEAD);
                case BOOKCASE -> EnumSet.of(Part.WEST, Part.EAST, Part.UPPER_WEST, Part.UPPER_EAST);
                case GRAND_CHANDELIER -> EnumSet.of(Part.CROWN, Part.RING_NW, Part.RING_N, Part.RING_NE, Part.RING_W, Part.RING_C,
                        Part.RING_E, Part.RING_SW, Part.RING_S, Part.RING_SE);
                default -> EnumSet.noneOf(Part.class);
            };
        }

        /**
         * 台灯在大桌上该往哪边挪（{@link #TABLE_CORNER} 的值，台灯自己模型里的方向）：先在桌子的模型里看这一块离桌子正中是哪个方向
         * （NW 那一块往 +x +z，SE 那一块往 −x −z …），按桌子的朝向转到世界，再按台灯的朝向倒转回台灯的模型里。
         */
        public static Part tableCorner(Part tablePart, Direction tableFacing, Direction lampFacing) {
            int[] w = toWorld(tablePart.x == 0 ? 1 : -1, tablePart.z == 0 ? 1 : -1, LinerConnect.index(tableFacing));
            int[] m = toWorld(w[0], w[1], (4 - LinerConnect.index(lampFacing)) & 3);
            return m[0] < 0 ? (m[1] < 0 ? Part.NW : Part.SW) : (m[1] < 0 ? Part.NE : Part.SE);
        }

        /** 有开关、会发光的那几种（壁炉的炉火也是：右键开关，默认亮）。 */
        public static boolean isLamp(Kind kind) {
            return switch (kind) {
                case TALL_LAMP, TABLE_LAMP, CEILING_LAMP, CHANDELIER, GRAND_CHANDELIER, CEILING_PAIR, CEILING_QUAD, SCONCE, FIREPLACE -> true;
                default -> false;
            };
        }

        /** 只能挂在天花下的那几种。 */
        public static boolean hanging(Kind kind) {
            return switch (kind) {
                case CEILING_LAMP, CHANDELIER, GRAND_CHANDELIER, CEILING_PAIR, CEILING_QUAD -> true;
                default -> false;
            };
        }

        /** 一件灯里亮着时发光的那几格：落地灯的灯头、吊灯的灯身、大吊灯那一层正中的十字五格（蜡烛灯都在这五格的模型里）。 */
        public static boolean glows(Kind kind, Part p) {
            return switch (kind) {
                case TALL_LAMP -> p == Part.UPPER;
                case CHANDELIER -> p == Part.LOWER;
                case GRAND_CHANDELIER -> p == Part.RING_N || p == Part.RING_W || p == Part.RING_C || p == Part.RING_E
                        || p == Part.RING_S;
                case FIREPLACE -> p == Part.WIDE_MID;                              // 炭与火苗都在正中下面那一格
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
                case UPPER_WEST -> Part.UPPER_EAST;
                case UPPER_EAST -> Part.UPPER_WEST;
                case WIDE_WEST -> Part.WIDE_EAST;
                case WIDE_EAST -> Part.WIDE_WEST;
                case WIDE_UPPER_WEST -> Part.WIDE_UPPER_EAST;
                case WIDE_UPPER_EAST -> Part.WIDE_UPPER_WEST;
                case WIDE_TOP_WEST -> Part.WIDE_TOP_EAST;
                case WIDE_TOP_EAST -> Part.WIDE_TOP_WEST;
                case BAY_1 -> Part.BAY_4;
                case BAY_2 -> Part.BAY_3;
                case BAY_3 -> Part.BAY_2;
                case BAY_4 -> Part.BAY_1;
                case UPPER_BAY_1 -> Part.UPPER_BAY_4;
                case UPPER_BAY_2 -> Part.UPPER_BAY_3;
                case UPPER_BAY_3 -> Part.UPPER_BAY_2;
                case UPPER_BAY_4 -> Part.UPPER_BAY_1;
                case BELL_W0 -> Part.BELL_E0;
                case BELL_W1 -> Part.BELL_E1;
                case BELL_W2 -> Part.BELL_E2;
                case BELL_W3 -> Part.BELL_E3;
                case BELL_W4 -> Part.BELL_E4;
                case BELL_W5 -> Part.BELL_E5;
                case BELL_E0 -> Part.BELL_W0;
                case BELL_E1 -> Part.BELL_W1;
                case BELL_E2 -> Part.BELL_W2;
                case BELL_E3 -> Part.BELL_W3;
                case BELL_E4 -> Part.BELL_W4;
                case BELL_E5 -> Part.BELL_W5;
                default -> p;
            };
        }

        /** 吊艇架照镜子：艇换到另一只手（各块都在 x 0 那一列，块本身不换）。 */
        public static BoatSide mirrored(BoatSide side) {
            return side == BoatSide.LEFT ? BoatSide.RIGHT : BoatSide.LEFT;
        }
    }
}
