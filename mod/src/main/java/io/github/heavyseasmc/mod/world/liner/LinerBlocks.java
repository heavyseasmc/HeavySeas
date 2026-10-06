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
 * （与救生艇那一族相同，ADR-0058 Q6）。分三层：底面（墙、平顶、甲板、地毯，地板另有底面白平顶的一版）·
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
        LEFT, RIGHT;

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

    // ---------------------------------------------------------------- 底面层：墙、平顶、甲板、地毯

    public static final LinerBlock WALL_WHITE = add("liner_wall_white", solid(MapColor.OFF_WHITE), LinerBlock.Kind.PLAIN,
            s -> look("cube", tex("all", "wall_white")));
    public static final LinerBlock WALL = add("liner_wall", solid(MapColor.OFF_WHITE), LinerBlock.Kind.FRONT,
            s -> look("cube_front", tex("front", "wall", "back", "wall_white")), FACING);
    public static final LinerBlock CEILING = add("liner_ceiling", solid(MapColor.OFF_WHITE), LinerBlock.Kind.PLAIN,
            s -> look("cube", tex("all", "ceiling")));
    public static final LinerBlock MAHOGANY_WALL = add("liner_mahogany_wall", solid(MapColor.DARK_RED), LinerBlock.Kind.FRONT,
            s -> look("cube_front", tex("front", "mahogany", "back", "wall_white")), FACING);
    public static final LinerBlock TEAK_DECK = add("liner_teak_deck", solid(MapColor.OAK_TAN), LinerBlock.Kind.DECK,
            s -> look("floor", tex("top", "teak_deck", "side", "teak_side"), s.get(AXIS) == Direction.Axis.X ? 90 : 0), AXIS);
    // 地毯（ADR-0091，用户 2026-10-06 定「现在的花纹 + 单独的毯边」）：满铺只走花纹、不再自己收边；边是下面几件，由摆的人挑 ——
    //   贴图就是旧地毯外圈那几张、模型不转，船上照旧铺出来逐像素相同
    public static final LinerBlock CARPET = add("liner_carpet", solid(MapColor.RED).sounds(BlockSoundGroup.WOOL), LinerBlock.Kind.CARPET,
            s -> look("floor", tex("top", cellTexture(s), "side", "mahogany")), CELL);
    public static final LinerBlock CARPET_EDGE = carpetPiece("liner_carpet_edge", "edge", false);
    public static final LinerBlock CARPET_CORNER = carpetPiece("liner_carpet_corner", "corner", false);
    public static final LinerBlock CARPET_RUNNER = carpetPiece("liner_carpet_runner", "runner", false);
    public static final LinerBlock CARPET_END = carpetPiece("liner_carpet_end", "end", false);
    public static final LinerBlock CARPET_MAT = carpetPiece("liner_carpet_mat", "mat", false);
    // 地板的「底面白平顶」版（ADR-0067 §7，用户定的倾向）：4 格的层没有自己的平顶，天花就是上一层地板的底面 ——
    //   顶面、侧面照旧，只把底面画成平顶。地毯的每一件都有这一版
    public static final LinerBlock TEAK_DECK_CEILED = add("liner_teak_deck_ceiled", solid(MapColor.OAK_TAN), LinerBlock.Kind.DECK,
            s -> look("floor_ceiled", tex("top", "teak_deck", "side", "teak_side", "bottom", "ceiling"),
                    s.get(AXIS) == Direction.Axis.X ? 90 : 0), AXIS);
    public static final LinerBlock CARPET_CEILED = add("liner_carpet_ceiled", solid(MapColor.RED).sounds(BlockSoundGroup.WOOL),
            LinerBlock.Kind.CARPET, s -> look("floor_ceiled", tex("top", cellTexture(s), "side", "mahogany", "bottom", "ceiling")), CELL);
    public static final LinerBlock CARPET_EDGE_CEILED = carpetPiece("liner_carpet_edge_ceiled", "edge", true);
    public static final LinerBlock CARPET_CORNER_CEILED = carpetPiece("liner_carpet_corner_ceiled", "corner", true);
    public static final LinerBlock CARPET_RUNNER_CEILED = carpetPiece("liner_carpet_runner_ceiled", "runner", true);
    public static final LinerBlock CARPET_END_CEILED = carpetPiece("liner_carpet_end_ceiled", "end", true);
    public static final LinerBlock CARPET_MAT_CEILED = carpetPiece("liner_carpet_mat_ceiled", "mat", true);

    // ---------------------------------------------------------------- 结构层：大框（自动拼）· 护墙 · 腰线 · 檐口 · 壁柱 · 顶帽

    public static final LinerBlock FRAME_WHITE = frame("liner_frame_white", "white", MapColor.OFF_WHITE);
    public static final LinerBlock FRAME_GILT = frame("liner_frame_gilt", "gilt", MapColor.OFF_WHITE);
    public static final LinerBlock FRAME_GILT_WIDE = frame("liner_frame_gilt_wide", "gilt_wide", MapColor.OFF_WHITE);
    public static final LinerBlock FRAME_MAHOGANY = frame("liner_frame_mahogany", "mahogany", MapColor.DARK_RED);
    public static final LinerBlock FRAME_MAHOGANY_GILT = frame("liner_frame_mahogany_gilt", "mahogany_gilt", MapColor.DARK_RED);
    public static final LinerBlock WAINSCOT = add("liner_wainscot", solid(MapColor.OFF_WHITE), LinerBlock.Kind.WAINSCOT,
            s -> look("cube_front", tex("front", "wainscot_" + s.get(PART).code, "back", "wall_white")), FACING, PART);
    public static final LinerBlock WAINSCOT_MAHOGANY = add("liner_wainscot_mahogany", solid(MapColor.DARK_RED), LinerBlock.Kind.WAINSCOT,
            s -> look("cube_front", tex("front", "wainscot_mahogany_" + s.get(PART).code, "back", "wall_white")), FACING, PART);
    public static final LinerBlock CHAIR_RAIL = add("liner_chair_rail", piece(MapColor.OFF_WHITE), LinerBlock.Kind.RUN,
            s -> look("chair_rail" + s.get(SHAPE).suffix(), tex("t", "chair_rail")), FACING, SHAPE);
    public static final LinerBlock CORNICE = add("liner_cornice", piece(MapColor.OFF_WHITE), LinerBlock.Kind.RUN,
            s -> look("cornice" + s.get(SHAPE).suffix(), tex("t", "cornice")), FACING, SHAPE);
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
    /** 白漆壁柱；最下一格自动长出柱脚（base，见 {@link LinerConnect.Rules#pilasterBase}）。 */
    public static final LinerBlock PILASTER = add("liner_pilaster", piece(MapColor.OFF_WHITE), LinerBlock.Kind.WALL_PIECE,
            s -> look("pilaster" + s.get(BASE).suffix(), baseTex(s.get(BASE), "pilaster", "pilaster_base")), FACING, BASE);
    public static final LinerBlock PILASTER_MAHOGANY = add("liner_pilaster_mahogany", piece(MapColor.DARK_RED), LinerBlock.Kind.PILASTER_SIDE,
            s -> look((s.get(SIDE) == PilasterSide.LEFT ? "pilaster_left" : "pilaster_right") + s.get(BASE).suffix(),
                    baseTex(s.get(BASE), "pilaster_mahogany", "pilaster_base_mahogany")),
            FACING, SIDE, BASE);
    public static final LinerBlock CAPPING_MAHOGANY = add("liner_capping_mahogany", piece(MapColor.DARK_RED), LinerBlock.Kind.CAPPING,
            s -> s.get(END) == CappingEnd.NONE
                    ? look("capping", tex("c", "capping_mahogany"))
                    : look("capping_" + s.get(END).asString(), tex("c", "capping_mahogany", "p", "pilaster_mahogany")),
            FACING, END);
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

    /** 方块与物品一起登记；物品只在创造模式里拿得到（方块挖不动、不掉东西、没有配方）。 */
    public static void register() {
        BLOCKS.forEach((name, block) -> {
            Identifier id = Identifier.of(HeavySeasMod.MOD_ID, name);
            Registry.register(Registries.BLOCK, id, block);
            Registry.register(Registries.ITEM, id, new BlockItem(block, new Item.Settings()));
        });
    }

    /** 全部方块，按登记顺序（批量生成工具、物品栏与判据用）。 */
    public static Map<String, LinerBlock> all() {
        return Collections.unmodifiableMap(BLOCKS);
    }

    /** 满铺地毯这一格的花纹：两格一个周期，第几格按位置算。 */
    static String cellTexture(BlockState s) {
        int cell = s.get(CELL);
        return "carpet_cell_" + cell % LinerConnect.CARPET_PERIOD + cell / LinerConnect.CARPET_PERIOD;
    }

    /**
     * 毯边件（ADR-0091）：piece 见 {@link LinerConnect.Rules#carpetBorders}，朝向只挑贴图（{@link LinerBlock.Kind#CARPET_BORDER}）；
     * 单块小方毯四边都画，不要朝向。ceiled = 底面白平顶那一版。
     */
    private static LinerBlock carpetPiece(String name, String piece, boolean ceiled) {
        Function<String, LinerLooks.Look> top = mask -> ceiled
                ? look("floor_ceiled", tex("top", "carpet_border_" + mask, "side", "mahogany", "bottom", "ceiling"))
                : look("floor", tex("top", "carpet_border_" + mask, "side", "mahogany"));
        AbstractBlock.Settings settings = solid(MapColor.RED).sounds(BlockSoundGroup.WOOL);
        if (piece.equals("mat")) {
            return add(name, settings, LinerBlock.Kind.PLAIN, s -> top.apply(LinerConnect.Rules.carpetBorders(piece, 0)));
        }
        return add(name, settings, piece.equals("corner") ? LinerBlock.Kind.CARPET_CORNER : LinerBlock.Kind.CARPET_BORDER,
                s -> top.apply(LinerConnect.Rules.carpetBorders(piece, LinerConnect.index(s.get(FACING)))), FACING);
    }

    /**
     * 门套这一格的样子：模板 {@code casing_<哪一块>[_<顺带的线>]}。门楣与上角只顺带薄檐口；属性里存着别的线时
     * （手动 /setblock 才会出现）画成不带线的那一块，不出紫黑格子。
     */
    static LinerLooks.Look casingLook(BlockState s) {
        CasingPart part = s.get(CASING_PART);
        Trim trim = LinerConnect.Rules.casingAllows(part.asString(), s.get(TRIM).asString()) ? s.get(TRIM) : Trim.NONE;
        Map<String, String> t = tex("c", "door_casing", "h", "door_casing_head");
        switch (trim) {
            case SKIRTING -> t.put("k", "skirting");
            case CHAIR_RAIL -> t.put("r", "chair_rail");
            case CORNICE -> t.put("n", "cornice_thin");
            default -> {
            }
        }
        return look("casing_" + part.asString() + (trim == Trim.NONE ? "" : "_" + trim.asString()), t);
    }

    /** 壁柱的贴图：柱身；有柱脚加柱脚那一张；两边有踢脚再加踢脚条那一张。 */
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
            return look(mask.equals("c") ? "cube_front" : "frame_" + mask,
                    tex("front", "frame_" + style + "_" + mask, "back", "wall_white"));
        }, FACING, UP, DOWN, LEFT, RIGHT);
    }

    private static LinerBlock add(String name, AbstractBlock.Settings settings, LinerBlock.Kind kind,
                                  Function<BlockState, LinerLooks.Look> look, Property<?>... properties) {
        LinerBlock block = LinerBlock.create(settings, kind, look, properties);
        BlockState state = block.getStateManager().getDefaultState();
        if (state.contains(FACING)) {
            state = state.with(FACING, Direction.SOUTH);
        }
        // 连接一律默认「不接」：布尔属性默认取 true，大框默认就成了四边都接着的正中那一格（一条线都没有）——
        //   物品栏里那一格、/setblock 不带属性时，要的是一块完整的单格框 / 一小块带边的地毯（第一次生成就看到物品图标是白板）
        for (BooleanProperty joint : new BooleanProperty[]{UP, DOWN, LEFT, RIGHT, NORTH, EAST, SOUTH, WEST}) {
            if (state.contains(joint)) {
                state = state.with(joint, false);
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
