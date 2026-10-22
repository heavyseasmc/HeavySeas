package io.github.heavyseasmc.mod.world.liner;

import io.github.heavyseasmc.mod.HeavySeasMod;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.math.Direction;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import static io.github.heavyseasmc.mod.world.liner.LinerLooks.look;
import static io.github.heavyseasmc.mod.world.liner.LinerLooks.tex;

/**
 * 大邮轮那一族装饰方块（ADR-0062；样子 = ADR-0061 §3 定稿：墙第四轮、地毯第四轮格纹 + 点缀 B）。
 *
 * <p>通用件：不带船名、贴图上没有字，任何船、任何房子都能用。只在创造模式里拿：挖不动、不掉东西、没有配方
 * （与救生艇那一族相同，ADR-0058 Q6）。分三层：底面（墙、平顶、甲板、地毯）·
 * 结构（大框、护墙、腰线、檐口、壁柱、顶帽；贴附件第一组 ADR-0069 §2 ②：踢脚条、矮层薄檐口、门套、柱脚）·
 * 点缀（金线都画在大框的贴图里，没有单独的方块）。
 */
public final class LinerBlocks {

    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final EnumProperty<Direction.Axis> AXIS = Properties.HORIZONTAL_AXIS;
    /** 大框：上下左右接不接同一种、同一朝向的框（左右按站在正面看的人算）。 */
    public static final BooleanProperty UP = Properties.UP;
    public static final BooleanProperty DOWN = Properties.DOWN;
    public static final BooleanProperty LEFT = BooleanProperty.of("left");
    public static final BooleanProperty RIGHT = BooleanProperty.of("right");
    /**
     * 墙面类整块的背面朝着船外（ADR-0093 B9，用户 2026-10-07 定）：一格厚的墙一边是屋子、一边是露天甲板 / 散步廊时，
     * 背面、两个侧面与顶底画外墙白（就是改之前的样子）；不打开就四个侧面都画饰面。手放默认不打开，生成器按背后那一格在不在屋里打开。
     */
    public static final BooleanProperty EXTERIOR = BooleanProperty.of("exterior");
    /** 居中整根的壁柱是不是这一根最上面那一格（长柱头）：看上面那一格是不是同一根（ADR-0093 B7；左右半根照旧不长，B4 再说）。 */
    public static final BooleanProperty CAPITAL = BooleanProperty.of("capital");
    /** 天花梁（A8）：这一格是十字（两个方向都有梁接着）。 */
    public static final BooleanProperty CROSS = BooleanProperty.of("cross");
    /** 顶帽是高的那一种（整套檐部：楣板 · 珠线 · 横带 · 挑檐，原「主景横楣」，ADR-0093 B7）；手放默认普通顶帽。 */
    public static final BooleanProperty TALL = BooleanProperty.of("tall");
    /** 地毯：四边接不接地毯。 */
    public static final BooleanProperty NORTH = Properties.NORTH;
    public static final BooleanProperty EAST = Properties.EAST;
    public static final BooleanProperty SOUTH = Properties.SOUTH;
    public static final BooleanProperty WEST = Properties.WEST;
    /** 地毯花纹的第几格（{@link LinerConnect.Rules#cell}）。 */
    public static final IntProperty CELL = IntProperty.of("cell", 0, LinerConnect.CARPET_PERIOD * LinerConnect.CARPET_PERIOD - 1);
    public static final EnumProperty<WainscotPart> PART = EnumProperty.of("part", WainscotPart.class);
    public static final EnumProperty<PilasterSide> SIDE = EnumProperty.of("side", PilasterSide.class);
    public static final EnumProperty<CappingEnd> END = EnumProperty.of("end", CappingEnd.class);
    /** 檐口、腰线拐不拐角（{@link LinerConnect.Rules#cornerShape}）。 */
    public static final EnumProperty<CornerShape> SHAPE = EnumProperty.of("shape", CornerShape.class);
    /** 门套的哪一块（ADR-0069 §2 ②）：放的时候按点在那一格的哪儿定（{@link LinerConnect.Rules#casingPart}）。 */
    public static final EnumProperty<CasingPart> CASING_PART = EnumProperty.of("part", CasingPart.class);
    /** 门套那一格顺带画的那条线（{@link LinerConnect.Rules#casingTrim}，看左右邻居自动算）。 */
    public static final EnumProperty<Trim> TRIM = EnumProperty.of("trim", Trim.class);
    /** 壁柱最下一格的柱脚（{@link LinerConnect.Rules#pilasterBase}，看下面与左右自动算）。 */
    public static final EnumProperty<PilasterBase> BASE = EnumProperty.of("base", PilasterBase.class);
    // ---- A 甲板新贴附件（ADR-0080 §7）：格架 · 拱（宽门套沿用门套的 part / trim）
    /** 格架的内角（{@link LinerConnect.Rules#trellisCorner}）：屋角那一格在旁边那面墙上补一片。框条照大框用 up / down / left / right。 */
    public static final EnumProperty<TrellisCorner> CORNER = EnumProperty.of("corner", TrellisCorner.class);
    /** 格架上的常春藤：没有 · 中段（上面那一格也有）· 藤梢（{@link LinerConnect.Rules#ivyState}；有没有藤是摆的人定的，中段 / 藤梢自动算）。 */
    public static final EnumProperty<Ivy> IVY = EnumProperty.of("ivy", Ivy.class);
    /** 这一列茎的布局（{@link LinerConnect.Rules#ivyLayout}，按位置算）。 */
    public static final EnumProperty<IvyLayout> LAYOUT = EnumProperty.of("layout", IvyLayout.class);
    /** 这一格茎的拐法与叶子（{@link LinerConnect.Rules#ivyVariant}，按位置算）。 */
    public static final IntProperty V = IntProperty.of("v", 0, LinerConnect.Rules.IVY_PATHS - 1);
    /** 拱是一排三格里的哪一格（{@link LinerConnect.Rules#archPart}）。 */
    public static final EnumProperty<ArchPart> ARCH_PART = EnumProperty.of("part", ArchPart.class);
    /** 半圆拱的上下两排（{@link LinerConnect.Rules#archRow}）。 */
    public static final EnumProperty<ArchRow> ROW = EnumProperty.of("row", ArchRow.class);

    /** 格架的内角：没有 · 补在左手那面墙上 · 右手那面（站在正面看）。 */
    public enum TrellisCorner implements StringIdentifiable {
        NONE, INNER_LEFT, INNER_RIGHT;

        static TrellisCorner of(String code) {
            return valueOf(code.toUpperCase(Locale.ROOT));
        }

        /** 镜像之后左右对调。 */
        TrellisCorner mirrored() {
            return this == INNER_LEFT ? INNER_RIGHT : this == INNER_RIGHT ? INNER_LEFT : this;
        }

        @Override
        public String asString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 常春藤：没有 · 中段 · 藤梢。 */
    public enum Ivy implements StringIdentifiable {
        NONE, MID, TIP;

        static Ivy of(String code) {
            return valueOf(code.toUpperCase(Locale.ROOT));
        }

        @Override
        public String asString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 常春藤茎的三种布局：偏左一根 · 两根 · 偏右一根（{@code liner_decor.py} 的 IVY_LAYOUTS）。 */
    public enum IvyLayout implements StringIdentifiable {
        A, B, C;

        static IvyLayout of(String code) {
            return valueOf(code.toUpperCase(Locale.ROOT));
        }

        @Override
        public String asString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 拱的左 · 中 · 右（站在正面看）。 */
    public enum ArchPart implements StringIdentifiable {
        L, C, R;

        static ArchPart of(String code) {
            return valueOf(code.toUpperCase(Locale.ROOT));
        }

        /** 镜像之后左右对调。 */
        ArchPart mirrored() {
            return this == L ? R : this == R ? L : this;
        }

        @Override
        public String asString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 半圆拱的下面那一排 · 上面那一排。 */
    public enum ArchRow implements StringIdentifiable {
        LOWER, UPPER;

        static ArchRow of(String code) {
            return valueOf(code.toUpperCase(Locale.ROOT));
        }

        @Override
        public String asString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 护墙这一段：素段 · 框左半 · 框中段 · 框右半 · 单格框（贴图后缀 c / l / m / r / s）。 */
    public enum WainscotPart implements StringIdentifiable {
        PLAIN("c"), LEFT("l"), MIDDLE("m"), RIGHT("r"), SINGLE("s");

        final String code;

        WainscotPart(String code) {
            this.code = code;
        }

        static WainscotPart of(String code) {
            for (WainscotPart p : values()) {
                if (p.code.equals(code)) {
                    return p;
                }
            }
            throw new IllegalArgumentException(code);
        }

        @Override
        public String asString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 木壁柱靠这一格的左边还是右边（站在正面看）。 */
    public enum PilasterSide implements StringIdentifiable {
        /** 贴着格子左 / 右边的半根（护墙板接缝那种）· 居中整根（台座 · 柱身 · 柱头，原「主景立框」，ADR-0093 B7）。 */
        LEFT, RIGHT, CENTER;

        @Override
        public String asString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 顶帽的哪一头与壁柱合成一件。 */
    public enum CappingEnd implements StringIdentifiable {
        NONE, LEFT, RIGHT, BOTH;

        static CappingEnd of(String code) {
            return valueOf(code.toUpperCase(Locale.ROOT));
        }

        @Override
        public String asString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 直 · 内角（屋角）· 外角（凸角）；左右按站在正面看的人算。 */
    public enum CornerShape implements StringIdentifiable {
        STRAIGHT, INNER_LEFT, INNER_RIGHT, OUTER_LEFT, OUTER_RIGHT;

        static CornerShape of(String code) {
            return valueOf(code.toUpperCase(Locale.ROOT));
        }

        /** 镜像之后左右对调。 */
        CornerShape mirrored() {
            return switch (this) {
                case INNER_LEFT -> INNER_RIGHT;
                case INNER_RIGHT -> INNER_LEFT;
                case OUTER_LEFT -> OUTER_RIGHT;
                case OUTER_RIGHT -> OUTER_LEFT;
                default -> this;
            };
        }

        /** 模板名的后缀：直的那一块没有后缀。 */
        String suffix() {
            return this == STRAIGHT ? "" : "_" + asString();
        }

        @Override
        public String asString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * 门套的五块，左右按站在正面看的人算、名字按<b>门洞</b>说：门洞左边那一侧（竖条贴着这一格的右沿）· 右边那一侧 ·
     * 门楣（门洞正上方那一格的下沿）· 左上角（门洞左上方那一格，门套在它的右下角）· 右上角。
     */
    public enum CasingPart implements StringIdentifiable {
        JAMB_LEFT, JAMB_RIGHT, HEAD, CORNER_LEFT, CORNER_RIGHT;

        static CasingPart of(String code) {
            return valueOf(code.toUpperCase(Locale.ROOT));
        }

        /** 镜像之后左右对调。 */
        CasingPart mirrored() {
            return switch (this) {
                case JAMB_LEFT -> JAMB_RIGHT;
                case JAMB_RIGHT -> JAMB_LEFT;
                case CORNER_LEFT -> CORNER_RIGHT;
                case CORNER_RIGHT -> CORNER_LEFT;
                default -> this;
            };
        }

        @Override
        public String asString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 门套那一格顺带画的线：没有 · 踢脚条（最下一格换成门套墩）· 腰线 · 矮层薄檐口。 */
    public enum Trim implements StringIdentifiable {
        NONE, SKIRTING, CHAIR_RAIL, CORNICE;

        static Trim of(String code) {
            return valueOf(code.toUpperCase(Locale.ROOT));
        }

        @Override
        public String asString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 壁柱这一格的柱脚：不是最下一格 · 最下一格（墩） · 最下一格、两边有踢脚条（墩 + 两侧那一截踢脚）。 */
    public enum PilasterBase implements StringIdentifiable {
        NONE, PLINTH, SKIRTING;

        static PilasterBase of(String code) {
            return valueOf(code.toUpperCase(Locale.ROOT));
        }

        /** 模板名的后缀。 */
        String suffix() {
            return switch (this) {
                case NONE -> "";
                case PLINTH -> "_plinth";
                case SKIRTING -> "_plinth_skirting";
            };
        }

        @Override
        public String asString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final Map<String, LinerBlock> BLOCKS = new LinkedHashMap<>();
    /** 各种地毯 → 贴图名前缀（物品按这张表认「这是地毯，只铺在柚木甲板上」）。要写在所有地毯常量之前：静态字段按书写顺序初始化。 */
    private static final Map<LinerBlock, String> CARPET_PREFIX = new LinkedHashMap<>();

    // ---------------------------------------------------------------- 底面层：墙、平顶、甲板、地毯

    public static final LinerBlock WALL_WHITE = add("liner_wall_white", solid(MapColor.OFF_WHITE), LinerBlock.Kind.PLAIN,
            s -> look("cube", tex("all", "wall_white")));
    public static final LinerBlock WALL = add("liner_wall", solid(MapColor.OFF_WHITE), LinerBlock.Kind.FRONT,
            s -> s.get(EXTERIOR) ? outward("wall") : look("cube", tex("all", "wall")), FACING, EXTERIOR);
    public static final LinerBlock CEILING = add("liner_ceiling", solid(MapColor.OFF_WHITE), LinerBlock.Kind.PLAIN,
            s -> look("cube", tex("all", "ceiling")));
    /** 奶油平顶（A8，2026-10-19：头等客房 · 走廊 K2、点菜餐厅 P2 的格板）：同白平顶，换一种奶油色。 */
    public static final LinerBlock CEILING_CREAM = add("liner_ceiling_cream", solid(MapColor.PALE_YELLOW), LinerBlock.Kind.PLAIN,
            s -> look("cube", tex("all", "ceiling_cream")));
    /**
     * 白漆描金天花梁（A8 · P2）：挂在天花下面那一格的上半截，梁底两道金线。顺着放的人看的方向；两个方向都有梁接着就成十字，
     * 只有一个方向有就转过去顺着它（{@link LinerConnect.Rules#beamShape}）。
     */
    public static final LinerBlock CEILING_BEAM = add("liner_ceiling_beam", piece(MapColor.OFF_WHITE), LinerBlock.Kind.BEAM,
            s -> s.get(CROSS) ? look("beam_cross", tex("t", "ceiling_beam"))
                    : look("beam", tex("t", "ceiling_beam"), s.get(AXIS) == Direction.Axis.Z ? 90 : 0), AXIS, CROSS);
    /** 桃花心木墙板：第七轮（ADR-0111）起用自己的一张深木墙板 {@code mahogany_wall}，不再借家具 · 台阶共用的 {@code mahogany}。 */
    public static final LinerBlock MAHOGANY_WALL = add("liner_mahogany_wall", solid(MapColor.DARK_RED), LinerBlock.Kind.FRONT,
            s -> s.get(EXTERIOR) ? outward("mahogany_wall") : look("cube", tex("all", "mahogany_wall")), FACING, EXTERIOR);
    public static final LinerBlock TEAK_DECK = add("liner_teak_deck", solid(MapColor.OAK_TAN), LinerBlock.Kind.DECK,
            s -> look("floor", tex("top", "teak_deck", "side", "teak_side"), s.get(AXIS) == Direction.Axis.X ? 90 : 0), AXIS);
    // 浴室（客房第二版，2026-10-15）：白釉地铁砖墙（六面同图）· 马赛克地砖（顶面黑白小砖，四侧与底面同柚木甲板 —— 铺在楼板那一层）。
    //   贴图由 liner_props.py 画（与浴室道具同一批，写在 prop/ 下）
    public static final LinerBlock TILE_WALL = add("liner_tile_wall", solid(MapColor.WHITE).sounds(BlockSoundGroup.STONE), LinerBlock.Kind.PLAIN,
            s -> look("cube", tex("all", "prop/tile_wall")));
    public static final LinerBlock TILE_FLOOR = add("liner_tile_floor", solid(MapColor.WHITE).sounds(BlockSoundGroup.STONE), LinerBlock.Kind.PLAIN,
            s -> look("floor", tex("top", "prop/tile_floor", "side", "teak_side")));
    /**
     * 土耳其浴室热室的大理石台（2026-10-22，用户「未决定的全部按倾向」）：白大理石整块，六面同图；台面上偶尔冒一缕蒸汽
     * （{@link LinerAmbience#steam}，客户端粒子）。
     */
    public static final LinerBlock HAMMAM_SLAB = add("liner_hammam_slab", solid(MapColor.WHITE).sounds(BlockSoundGroup.STONE),
            LinerBlock.Kind.PLAIN, s -> look("cube", tex("all", "prop/hammam_slab")));
    // 地毯（ADR-0093 B3 · B14，用户 2026-10-07「按倾向」）：铺了毯的柚木地板 —— 拿地毯点一格柚木甲板就铺上（{@link CarpetItem}），
    //   剪刀揭掉还原成柚木（axis 记着底下那层板的走向）。四边收不收口看邻居是不是地毯：单独一格就是四边收口的一块小方毯，
    //   四边都接着的那几格走花纹（两格一个周期，按位置）。毯面与地板齐平，家具不悬空。ADR-0091 的毯边五件退役
    //   第七轮（ADR-0111 §7）：按舱等分色 —— 酒红（头等，沿用 liner_carpet 这个名字）· 海军蓝（二等）· 墨绿（吸烟室）；
    //   深底暗铜花、铺满无中心。不同颜色的两块挨着各自收边（连接只认同一种）
    public static final LinerBlock CARPET = carpet("liner_carpet", "carpet", MapColor.RED);
    public static final LinerBlock CARPET_NAVY = carpet("liner_carpet_navy", "carpet_navy", MapColor.BLUE);
    public static final LinerBlock CARPET_GREEN = carpet("liner_carpet_green", "carpet_green", MapColor.DARK_GREEN);
    /** 深色平顶（ADR-0111：客房吊顶压到净高 4）：深桃花心木横板。 */
    public static final LinerBlock CEILING_MAHOGANY = add("liner_ceiling_mahogany", solid(MapColor.DARK_RED), LinerBlock.Kind.PLAIN,
            s -> look("cube", tex("all", "ceiling_mahogany")));
    // 地板的「底面白平顶」那 7 种已退（ADR-0093 B6）：四层客用甲板之后各层都有自己的平顶（ADR-0092），船上一格都没用

    // ---------------------------------------------------------------- 结构层：大框（自动拼）· 护墙 · 腰线 · 檐口 · 壁柱 · 顶帽

    public static final LinerBlock FRAME_WHITE = frame("liner_frame_white", "white", MapColor.OFF_WHITE);
    public static final LinerBlock FRAME_GILT = frame("liner_frame_gilt", "gilt", MapColor.OFF_WHITE);
    public static final LinerBlock FRAME_GILT_WIDE = frame("liner_frame_gilt_wide", "gilt_wide", MapColor.OFF_WHITE);
    public static final LinerBlock FRAME_MAHOGANY = frame("liner_frame_mahogany", "mahogany", MapColor.DARK_RED);
    public static final LinerBlock FRAME_MAHOGANY_GILT = frame("liner_frame_mahogany_gilt", "mahogany_gilt", MapColor.DARK_RED);
    // 第七轮（ADR-0111 §7）：亮金框条的三种框里 —— 暗花墙布（头等客房 · 休息室）· 火焰纹（吸烟室 · 写字室 · 图书室，一框一条中轴）·
    //   人字拼（二等客房）。框外与护墙仍是桃花心木墙板
    public static final LinerBlock FRAME_DAMASK_GILT = frame("liner_frame_damask_gilt", "damask_gilt", MapColor.DARK_RED);
    public static final LinerBlock FRAME_FLAME_GILT = frame("liner_frame_flame_gilt", "flame_gilt", MapColor.DARK_RED);
    public static final LinerBlock FRAME_PARQUET_GILT = frame("liner_frame_parquet_gilt", "parquet_gilt", MapColor.DARK_RED);
    public static final LinerBlock WAINSCOT = add("liner_wainscot", solid(MapColor.OFF_WHITE), LinerBlock.Kind.WAINSCOT,
            s -> faced("wainscot_", s.get(PART).code, "wall", s.get(EXTERIOR)), FACING, PART, EXTERIOR);
    public static final LinerBlock WAINSCOT_MAHOGANY = add("liner_wainscot_mahogany", solid(MapColor.DARK_RED), LinerBlock.Kind.WAINSCOT,
            s -> faced("wainscot_mahogany_", s.get(PART).code, "mahogany", s.get(EXTERIOR)), FACING, PART, EXTERIOR);
    public static final LinerBlock CHAIR_RAIL = add("liner_chair_rail", piece(MapColor.OFF_WHITE), LinerBlock.Kind.RUN,
            s -> look("chair_rail" + s.get(SHAPE).suffix(), tex("t", "chair_rail")), FACING, SHAPE);
    /** 描金腰线（ADR-0111：深木墙的屋子与走廊）：同腰线（拐角一样），亮金三道、沿线流光。只与同一种接拐角。 */
    public static final LinerBlock CHAIR_RAIL_GILT = add("liner_chair_rail_gilt", piece(MapColor.GOLD), LinerBlock.Kind.RUN,
            s -> look("chair_rail" + s.get(SHAPE).suffix(), tex("t", "chair_rail_gilt")), FACING, SHAPE);
    public static final LinerBlock CORNICE = add("liner_cornice", piece(MapColor.OFF_WHITE), LinerBlock.Kind.RUN,
            s -> s.get(CAPITAL) && s.get(SHAPE) == CornerShape.STRAIGHT
                    // 5e（ADR-0093 B4 · A11）：正下方是同朝向的白漆壁柱 —— 檐口下面那 8 像素里接上柱身、长柱头（LinerConnect.Rules#corniceCapital）
                    ? look("cornice_capital", tex("t", "cornice", "p", "pilaster"))
                    : look("cornice" + s.get(SHAPE).suffix(), tex("t", "cornice")), FACING, SHAPE, CAPITAL);
    /** 描金檐口（A8 · K2：头等客房与走廊）：同凸出檐口（拐角 · 柱头都一样），小线脚上沿一道金线。只与同一种接拐角。 */
    public static final LinerBlock CORNICE_GILT = add("liner_cornice_gilt", piece(MapColor.OFF_WHITE), LinerBlock.Kind.RUN,
            s -> s.get(CAPITAL) && s.get(SHAPE) == CornerShape.STRAIGHT
                    ? look("cornice_capital", tex("t", "cornice_gilt", "p", "pilaster"))
                    : look("cornice" + s.get(SHAPE).suffix(), tex("t", "cornice_gilt")), FACING, SHAPE, CAPITAL);
    // ---- 贴附件第一组（ADR-0069 §2 ②；二评 r2「矮空间缺交接件：墙顶薄檐口、踢脚条、门套」）：摆法同檐口
    /** 矮层（室内 3 格）的薄檐口：总下垂 3.5 像素、最大出挑 1.5（凸出檐口是 8 与 2.5）；拐角同檐口。 */
    public static final LinerBlock CORNICE_THIN = add("liner_cornice_thin", piece(MapColor.OFF_WHITE), LinerBlock.Kind.RUN,
            s -> look("cornice_thin" + s.get(SHAPE).suffix(), tex("n", "cornice_thin")), FACING, SHAPE);
    /** 踢脚条（主景木色，沿墙脚走）：板 3 高前出 1、顶上一道 1 高的小线脚；拐角同檐口。 */
    public static final LinerBlock SKIRTING = add("liner_skirting", piece(MapColor.DARK_RED), LinerBlock.Kind.RUN,
            s -> look("skirting" + s.get(SHAPE).suffix(), tex("k", "skirting")), FACING, SHAPE);
    /** 白漆门套：门洞两侧与上方一圈（每格一块），顺带画旁边的踢脚 / 腰线 / 薄檐口。 */
    public static final LinerBlock DOOR_CASING = add("liner_door_casing", piece(MapColor.OFF_WHITE), LinerBlock.Kind.CASING,
            LinerBlocks::casingLook, FACING, CASING_PART, TRIM);
    /**
     * 桃花心木门套（2026-10-19 用户标注「门套搭配不上」：深木墙的屋子与走廊，ADR-0111）：同白漆门套（模板 · 部位 · 顺带的线都一样），
     * 换成深木那两张；顺带的腰线画描金腰线。
     */
    public static final LinerBlock DOOR_CASING_MAHOGANY = add("liner_door_casing_mahogany", piece(MapColor.DARK_RED), LinerBlock.Kind.CASING,
            s -> casingLook(s, "door_casing_mahogany", "door_casing_mahogany_head", "chair_rail_gilt"), FACING, CASING_PART, TRIM);
    /**
     * 白漆壁柱；最下一格自动长出柱脚（base，见 {@link LinerConnect.Rules#pilasterBase}）；别的格看左右穿过的那条线（trim，5e，
     * {@link LinerConnect.Rules#pilasterTrim}）：腰线两边各补一段 · 薄檐口那一格长柱头、檐口整格穿过（ADR-0093 B4 · A11）。
     */
    public static final LinerBlock PILASTER = add("liner_pilaster", piece(MapColor.OFF_WHITE), LinerBlock.Kind.WALL_PIECE,
            LinerBlocks::pilasterLook, FACING, BASE, TRIM);
    public static final LinerBlock PILASTER_MAHOGANY = add("liner_pilaster_mahogany", piece(MapColor.DARK_RED), LinerBlock.Kind.PILASTER_SIDE,
            s -> s.get(SIDE) == PilasterSide.CENTER
                    // 居中整根：就是原来的主景立框那四段（底段台座 · 中段 · 顶段柱头 · 单独一格两头都有），模板与贴图照用
                    ? look("stairs/feature_post_" + columnPart(s.get(BASE) != PilasterBase.NONE, s.get(CAPITAL)), tex("w", "stairs/feature_post"))
                    : look((s.get(SIDE) == PilasterSide.LEFT ? "pilaster_left" : "pilaster_right") + s.get(BASE).suffix(),
                    baseTex(s.get(BASE), "pilaster_mahogany", "pilaster_base_mahogany")),
            FACING, SIDE, BASE, CAPITAL);
    public static final LinerBlock CAPPING_MAHOGANY = add("liner_capping_mahogany", piece(MapColor.DARK_RED), LinerBlock.Kind.CAPPING,
            s -> s.get(TALL)
                    // 高的那一种：原来的主景横楣（哪一头出端面同顶帽的 end），模板与贴图照用
                    ? look("stairs/feature_lintel_" + s.get(END).asString(), tex("l", "stairs/feature_lintel"))
                    : s.get(END) == CappingEnd.NONE
                    ? look("capping", tex("c", "capping_mahogany"))
                    : look("capping_" + s.get(END).asString(), tex("c", "capping_mahogany", "p", "pilaster_mahogany")),
            FACING, END, TALL);
    // ---- A 甲板新贴附件（ADR-0080 §7，用户 2026-10-04「全按倾向」：格架 A 菱格 + 常春藤 + 内角 · 拱 C 半圆 + 拱心石 · 宽门套 A 檐式门头）
    /** 宽门套（高层的大门、双开门）：门套那一族放大到 7 宽，门楣上一道檐；part / trim 与门套同一套规则，两种门套互相跨过去找线。 */
    public static final LinerBlock DOOR_CASING_TALL = add("liner_door_casing_tall", piece(MapColor.OFF_WHITE), LinerBlock.Kind.CASING,
            LinerBlocks::casingTallLook, FACING, CASING_PART, TRIM);
    /** 白漆菱格格架：框条看上下左右（同大框）· 屋角补旁边那面墙（corner）· 常春藤叠在上面（多部件模型，见 {@link LinerBlock#layers}）。 */
    public static final LinerBlock TRELLIS = add("liner_trellis", piece(MapColor.OFF_WHITE), LinerBlock.Kind.TRELLIS,
            LinerBlocks::trellisLook, FACING, UP, DOWN, LEFT, RIGHT, CORNER, IVY, LAYOUT, V);
    /** 半圆拱（墙那一格本身，屋里一面墙面、外面一面外墙白）：一排三格（part）· 上下两排（row），看邻居自动算。 */
    public static final LinerBlock ARCH = add("liner_arch", piece(MapColor.OFF_WHITE), LinerBlock.Kind.ARCH,
            s -> look("arch_" + s.get(ARCH_PART).asString() + "_" + s.get(ROW).asString(),
                    tex("f", "wall", "w", "wall_white", "c", "door_casing")), FACING, ARCH_PART, ROW);

    private LinerBlocks() {
    }

    /** 宽门套：模板 {@code casing_tall_<哪一块>[_<顺带的线>]}。只有两侧顺带踢脚 / 腰线；门楣、上角与别的线（薄檐口）画成不带线的那一块。 */
    static LinerLooks.Look casingTallLook(BlockState s) {
        CasingPart part = s.get(CASING_PART);
        Trim trim = s.get(TRIM);
        boolean side = part == CasingPart.JAMB_LEFT || part == CasingPart.JAMB_RIGHT;
        if (!side || trim == Trim.CORNICE) {
            trim = Trim.NONE;
        }
        Map<String, String> t = tex("c", "door_casing", "h", "door_casing_head", "e", "door_casing_tall");
        switch (trim) {
            case SKIRTING -> t.put("k", "skirting");
            case CHAIR_RAIL -> t.put("r", "chair_rail");
            default -> {
            }
        }
        return look("casing_tall_" + part.asString() + (trim == Trim.NONE ? "" : "_" + trim.asString()), t);
    }

    /**
     * 格架本体：模板 {@code trellis/base_<框条>[_<内角>]}（框条照大框 frameMask；内角只在屋角那一边画着竖框条时才有 ——
     * 属性里存着别的组合（手动 /setblock 才会出现）画成不带内角的那一块）。常春藤是另一层（{@link #ivyLook}）。
     */
    static LinerLooks.Look trellisLook(BlockState s) {
        String mask = LinerConnect.Rules.frameMask(s.get(UP), s.get(DOWN), s.get(LEFT), s.get(RIGHT));
        TrellisCorner c = s.get(CORNER);
        boolean ok = c == TrellisCorner.INNER_RIGHT ? mask.contains("r") : c == TrellisCorner.INNER_LEFT && mask.contains("l");
        return look("trellis/base_" + mask + (ok ? "_" + c.asString() : ""), tex("t", "trellis"));
    }

    /** 常春藤那一层：模板 {@code trellis/ivy_<mid|tip>_<布局><拐法>}；没有藤 = {@code null}（这一层不画）。 */
    static LinerLooks.Look ivyLook(BlockState s) {
        if (s.get(IVY) == Ivy.NONE) {
            return null;
        }
        return look("trellis/ivy_" + s.get(IVY).asString() + "_" + s.get(LAYOUT).asString() + s.get(V), tex("v", "ivy"));
    }

    /** 方块与物品一起登记；物品只在创造模式里拿得到（方块挖不动、不掉东西、没有配方）。地毯的物品只铺在柚木甲板上（{@link CarpetItem}）。 */
    public static void register() {
        BLOCKS.forEach((name, block) -> {
            Identifier id = Identifier.of(HeavySeasMod.MOD_ID, name);
            Registry.register(Registries.BLOCK, id, block);
            Registry.register(Registries.ITEM, id, CARPET_PREFIX.containsKey(block) ? new CarpetItem(block, new Item.Settings())
                    : new BlockItem(block, new Item.Settings()));
        });
        DecorHammer.register();                        // 装修锤（ADR-0093 B15）：放下以后再改，右键轮换形态
    }

    /** 全部方块，按登记顺序（批量生成工具、物品栏与判据用）。 */
    public static Map<String, LinerBlock> all() {
        return Collections.unmodifiableMap(BLOCKS);
    }

    /**
     * 地毯这一格的顶面：不接地毯的那几边画毯边（{@code carpet_border_<边>}，贴图就是旧毯边件那几张）；四边都接着走花纹，
     * 两格一个周期、第几格按位置算（{@code carpet_cell_<i><j>}）。
     */
    static String carpetTop(BlockState s, String prefix) {
        String mask = LinerConnect.Rules.carpetMask(s.get(NORTH), s.get(EAST), s.get(SOUTH), s.get(WEST));
        if (!mask.isEmpty()) {
            return prefix + "_border_" + mask;
        }
        int cell = s.get(CELL);
        return prefix + "_cell_" + cell % LinerConnect.CARPET_PERIOD + cell / LinerConnect.CARPET_PERIOD;
    }

    /** 一种颜色的地毯：铺了毯的柚木地板（同 {@link #CARPET} 的做法），顶面贴图 {@code <prefix>_border_<边>} / {@code <prefix>_cell_<i><j>}。 */
    private static LinerBlock carpet(String name, String prefix, MapColor color) {
        LinerBlock block = add(name, solid(color).sounds(BlockSoundGroup.WOOL), LinerBlock.Kind.CARPET,
                s -> look("floor", tex("top", carpetTop(s, prefix), "side", "mahogany")), AXIS, NORTH, EAST, SOUTH, WEST, CELL);
        CARPET_PREFIX.put(block, prefix);
        return block;
    }

    /**
     * 门套这一格的样子：模板 {@code casing_<哪一块>[_<顺带的线>]}。门楣与上角只顺带薄檐口；属性里存着别的线时
     * （手动 /setblock 才会出现）画成不带线的那一块，不出紫黑格子。
     */
    static LinerLooks.Look casingLook(BlockState s) {
        return casingLook(s, "door_casing", "door_casing_head", "chair_rail");
    }

    /** 门套：竖条 / 横条的贴图与顺带的腰线那一张可换（白漆一套、桃花心木一套），模板同一套。 */
    static LinerLooks.Look casingLook(BlockState s, String jamb, String head, String rail) {
        CasingPart part = s.get(CASING_PART);
        Trim trim = LinerConnect.Rules.casingAllows(part.asString(), s.get(TRIM).asString()) ? s.get(TRIM) : Trim.NONE;
        Map<String, String> t = tex("c", jamb, "h", head);
        switch (trim) {
            case SKIRTING -> t.put("k", "skirting");
            case CHAIR_RAIL -> t.put("r", rail);
            case CORNICE -> t.put("n", "cornice_thin");
            default -> {
            }
        }
        return look("casing_" + part.asString() + (trim == Trim.NONE ? "" : "_" + trim.asString()), t);
    }

    /** 居中整根壁柱这一格是哪一段（原主景立框的 part）：最下一格（有柱脚）· 最上一格（长柱头）。 */
    static String columnPart(boolean bottom, boolean top) {
        return bottom ? (top ? "single" : "bottom") : (top ? "top" : "middle");
    }

    /** 壁柱的贴图：柱身；有柱脚加柱脚那一张；两边有踢脚再加踢脚条那一张。 */
    /** 白漆壁柱这一格的样子：柱脚那一格照旧；别的格按穿过的那条线（5e）。 */
    static LinerLooks.Look pilasterLook(BlockState s) {
        Trim trim = s.get(TRIM);
        if (s.get(BASE) != PilasterBase.NONE || trim == Trim.NONE || trim == Trim.SKIRTING) {
            return look("pilaster" + s.get(BASE).suffix(), baseTex(s.get(BASE), "pilaster", "pilaster_base"));
        }
        return trim == Trim.CHAIR_RAIL ? look("pilaster_chair_rail", tex("p", "pilaster", "r", "chair_rail"))
                : look("pilaster_cornice", tex("p", "pilaster", "n", "cornice_thin"));
    }

    private static Map<String, String> baseTex(PilasterBase base, String shaft, String plinth) {
        return switch (base) {
            case NONE -> tex("p", shaft);
            case PLINTH -> tex("p", shaft, "b", plinth);
            case SKIRTING -> tex("p", shaft, "b", plinth, "k", "skirting");
        };
    }

    // ---------------------------------------------------------------- 构造用的小工具

    /** 大框：画线的那几边用前出 1 像素框条的模板 {@code frame_<哪几边>}（ADR-0069 §1a），四边都接着的正中那一格用整块。 */
    private static LinerBlock frame(String name, String style, MapColor color) {
        return add(name, solid(color), LinerBlock.Kind.FRAME, s -> {
            String mask = LinerConnect.Rules.frameMask(s.get(UP), s.get(DOWN), s.get(LEFT), s.get(RIGHT));
            LinerLooks.Look faced = faced("frame_" + style + "_", mask, "frame_" + style + "_c", s.get(EXTERIOR));
            return mask.equals("c") ? faced : look("frame_" + mask, faced.textures());
        }, FACING, UP, DOWN, LEFT, RIGHT, EXTERIOR);
    }

    /**
     * 有正面的整块四面都画（ADR-0093 B9）：背面画正面左右对调的那一张 —— 从背后看，正面那一格框的左半落在右手边，
     * 所以换成「右半」那一张（不是把贴图翻过来：翻过来的高光会落到另一侧）；两个侧面画素段 {@code c}，顶底素色。
     * 背面朝船外（{@link #EXTERIOR}）时只有正面画饰面，其余五面外墙白。
     */
    private static LinerLooks.Look faced(String prefix, String code, String end, boolean exterior) {
        if (exterior) {
            return outward(prefix + code);
        }
        return look("cube_front", tex("front", prefix + code, "back", prefix + mirrored(code), "side", prefix + "c", "end", end));
    }

    /** 背面朝船外的墙：正面一张，其余五面外墙白（与 B9 之前所有墙面类整块的样子相同）。 */
    private static LinerLooks.Look outward(String front) {
        return look("cube_front", tex("front", front, "back", "wall_white", "side", "wall_white", "end", "wall_white"));
    }

    /** 框的 mask / 护墙的段名左右对调（l ↔ r），其余不变；mask 照 t · b · l · r 的次序重排。 */
    static String mirrored(String code) {
        StringBuilder out = new StringBuilder();
        for (char c : "tblrcms".toCharArray()) {
            char from = c == 'l' ? 'r' : c == 'r' ? 'l' : c;
            if (code.indexOf(from) >= 0) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static LinerBlock add(String name, AbstractBlock.Settings settings, LinerBlock.Kind kind,
                                  Function<BlockState, LinerLooks.Look> look, Property<?>... properties) {
        LinerBlock block = LinerBlock.create(settings, kind, look, properties);
        BlockState state = block.getStateManager().getDefaultState();
        if (state.contains(FACING)) {
            state = state.with(FACING, Direction.SOUTH);
        }
        // 布尔属性一律默认 false：游戏给布尔属性的默认值是 true —— 大框默认就成了四边都接着的正中那一格（一条线都没有，
        //   第一次生成就看到物品图标是白板）；B9 的 exterior 没进当时那份「连接」名单，手放的墙默认成了背面外墙白（2026-10-07，
        //   与用户定的「手放默认四面饰面」相反，物品模型指着外墙白那一块）。不再列名单：这一族的布尔属性全部默认 false
        for (Property<?> p : state.getProperties()) {
            if (p instanceof BooleanProperty b) {
                state = state.with(b, false);
            }
        }
        block.defaultTo(state);
        BLOCKS.put(name, block);
        return block;
    }

    /** 挖不动、不掉东西、活塞推不动（与救生艇那一族相同）。 */
    private static AbstractBlock.Settings solid(MapColor color) {
        return AbstractBlock.Settings.create().mapColor(color).strength(-1.0f, 3_600_000.0f).dropsNothing()
                .pistonBehavior(PistonBehavior.BLOCK).sounds(BlockSoundGroup.WOOD);
    }

    /** 挂在墙上的件：不满一格、不挡光、不剔相邻的面。 */
    private static AbstractBlock.Settings piece(MapColor color) {
        return solid(color).nonOpaque();
    }
}
