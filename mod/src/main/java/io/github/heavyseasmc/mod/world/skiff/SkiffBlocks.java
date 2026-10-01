package io.github.heavyseasmc.mod.world.skiff;

import io.github.heavyseasmc.mod.HeavySeasMod;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.block.piston.PistonBehavior;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import static io.github.heavyseasmc.mod.world.skiff.SkiffLooks.look;
import static io.github.heavyseasmc.mod.world.skiff.SkiffLooks.tex;

/**
 * 救生艇第一套的 26 种方块（ADR-0053 §4 · ADR-0056 · ADR-0057）。
 *
 * <p>全部挖不动（硬度 −1，与基岩同款）、不掉东西：它们是船体结构的一部分，开局随结构模板放下、结束时清回海水
 * （{@code Hull}）。「形状 × 材质」：船壳一块模板配三种外观（普通 · 水线 · 镶板），压条两种（普通 · 镶板）。
 *
 * <p>朝向 {@code facing} 一律是<b>船头朝哪</b>；左右舷用 {@link Side}。船体模板整体旋转时只转朝向，左右不变。
 */
public final class SkiffBlocks {

    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    public static final EnumProperty<Side> SIDE = EnumProperty.of("side", Side.class);
    public static final IntProperty SEAT = IntProperty.of("seat", 0, 8);
    public static final IntProperty OIL = IntProperty.of("oil", 0, 4);
    public static final BooleanProperty HANGING = Properties.HANGING;
    public static final BooleanProperty OAR = BooleanProperty.of("oar");
    public static final BooleanProperty FURLED = BooleanProperty.of("furled");
    public static final BooleanProperty RAISED = BooleanProperty.of("raised");
    /** 划一下桨：插着的桨往船尾扫一下，片刻后回位（ADR-0057 §4）。 */
    public static final BooleanProperty STROKE = BooleanProperty.of("stroke");
    /** 舵手挑牌时舵往一边偏一下。 */
    public static final EnumProperty<SkiffTurn> TURN = EnumProperty.of("turn", SkiffTurn.class);
    /**
     * 泡在水里的那几格（舵的下段在水线那一层）。❗不加它不行：没有碰撞箱的方块放进水里，结构刚放下去就被水当成
     * 火把、花那样冲掉 —— 不报错、不掉东西（2026-10-02 雾海实测：舵下段那一格是水，舵手偏舵只动了 3 格）。
     */
    public static final BooleanProperty WATERLOGGED = Properties.WATERLOGGED;
    public static final IntProperty ROW = IntProperty.of("row", 0, 2);
    public static final IntProperty COL = IntProperty.of("col", 0, 3);
    public static final IntProperty BELLY = IntProperty.of("belly", 0, 2);
    public static final EnumProperty<Part> OAR_PART = EnumProperty.of("part", Part.class, Part.BLADE, Part.HANDLE);
    public static final EnumProperty<Part> RUDDER_PART = EnumProperty.of("part", Part.class, Part.LOWER, Part.BLADE, Part.HEAD);
    public static final EnumProperty<Part> TILLER_PART = EnumProperty.of("part", Part.class, Part.MID, Part.END);
    public static final EnumProperty<Part> MAST_PART = EnumProperty.of("part", Part.class, Part.POLE, Part.ARM, Part.TOP);
    public static final EnumProperty<Part> YARD_PART = EnumProperty.of("part", Part.class, Part.YARD, Part.FORE);
    public static final EnumProperty<Part> END = EnumProperty.of("end", Part.class, Part.BOW, Part.STERN);
    public static final EnumProperty<Part> CHAR = EnumProperty.of("char", Part.class, Part.BEI, Part.CHEN, Part.HAO);

    /** 灯油 0–4 档 → 光照等级（ADR-0057：满油 15，快烧干 6，0 = 灭）。 */
    public static final int[] OIL_LIGHT = {0, 6, 10, 13, 15};

    /** 右舷 = 面朝船头时的右手边，模板原样；左舷转 180°。 */
    public enum Side implements StringIdentifiable {
        RIGHT, LEFT;

        @Override
        public String asString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 多段件的段名（各个方块只用其中几个）。 */
    public enum Part implements StringIdentifiable {
        BLADE, HANDLE, LOWER, HEAD, MID, END, POLE, ARM, TOP, YARD, FORE, BOW, STERN, BEI, CHEN, HAO;

        @Override
        public String asString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private static final Map<String, SkiffBlock> BLOCKS = new LinkedHashMap<>();

    private static final Map<String, String> HULL_TEX = tex("out", "hull_outer", "in", "hull_inner", "top", "hull_top");

    // ---------------------------------------------------------------- 船壳 · 压条 · 踏板 · 座板

    public static final SkiffBlock HULL = add("skiff_hull", solid(MapColor.WHITE),
            s -> sided(s, look("hull_block", HULL_TEX)), FACING, SIDE);
    public static final SkiffBlock HULL_WATERLINE = add("skiff_hull_waterline", solid(MapColor.BLACK),
            s -> sided(s, look("hull_block", with(HULL_TEX, "out", "hull_boot"))), FACING, SIDE);
    public static final SkiffBlock HULL_PANELED = add("skiff_hull_paneled", solid(MapColor.WHITE),
            s -> sided(s, look("hull_block", with(HULL_TEX, "in", "panel_mahogany"))), FACING, SIDE);
    public static final SkiffBlock STEM = add("skiff_stem", solid(MapColor.WHITE).nonOpaque(),
            s -> look("hull_stairs", HULL_TEX, 0, s.get(END) == Part.BOW ? 270 : 90), FACING, END);
    public static final SkiffBlock GUNWALE = add("skiff_gunwale", solid(MapColor.WHITE).nonOpaque(),
            s -> sided(s, look("gunwale", tex("out", "hull_sheer", "in", "hull_inner", "rail", "teak_rail"))), FACING, SIDE);
    public static final SkiffBlock GUNWALE_PANELED = add("skiff_gunwale_paneled", solid(MapColor.WHITE).nonOpaque(),
            s -> sided(s, look("gunwale", tex("out", "hull_sheer", "in", "panel_mahogany", "rail", "teak_rail"))), FACING, SIDE);
    public static final SkiffBlock FLOORBOARDS = add("skiff_floorboards", solid(MapColor.OAK_TAN),
            s -> look("floor", tex("top", "floorboards", "side", "hull_inner")), FACING);
    public static final SkiffBlock THWART = add("skiff_thwart", solid(MapColor.OAK_TAN).nonOpaque(),
            s -> look("thwart", tex("top", s.get(SEAT) == 0 ? "thwart" : "thwart_" + s.get(SEAT))), FACING, SEAT);

    // ---------------------------------------------------------------- 桨 · 桨架 · 舵 · 舵柄

    public static final SkiffBlock OAR_BLOCK = add("skiff_oar", loose(MapColor.OAK_TAN),
            s -> sided(s, look((s.get(OAR_PART) == Part.BLADE ? "oar_blade" : "oar_handle") + stroke(s), tex("oar", "oar"))),
            FACING, SIDE, OAR_PART, STROKE);
    public static final SkiffBlock ROWLOCK = add("skiff_rowlock", loose(MapColor.GOLD).sounds(BlockSoundGroup.METAL),
            s -> sided(s, look(s.get(OAR) ? "rowlock_oar" + stroke(s) : "rowlock", tex("brass", "brass", "oar", "oar"))),
            FACING, SIDE, OAR, STROKE);
    public static final SkiffBlock RUDDER = add("skiff_rudder", loose(MapColor.WHITE),
            s -> look("rudder_" + s.get(RUDDER_PART).asString() + (s.get(TURN) == SkiffTurn.NONE ? "" : "_" + s.get(TURN).asString()),
                    tex("blade", "rudder", "tiller", "tiller", "brass", "brass")),
            FACING, RUDDER_PART, TURN, WATERLOGGED);
    public static final SkiffBlock TILLER = add("skiff_tiller", loose(MapColor.OAK_TAN),
            s -> look("tiller_" + s.get(TILLER_PART).asString(), tex("tiller", "tiller")), FACING, TILLER_PART);

    // ---------------------------------------------------------------- 船里散放的东西

    public static final SkiffBlock CASK = add("skiff_cask", solid(MapColor.OAK_TAN).nonOpaque(),
            s -> look("cask", tex("side", "cask_side", "head", "cask_head")), FACING);
    public static final SkiffBlock BAILER = add("skiff_bailer", solid(MapColor.GRAY).nonOpaque().sounds(BlockSoundGroup.METAL),
            s -> look("bailer", tex("t", "bailer", "brass", "brass")), FACING);
    public static final SkiffBlock CANVAS = add("skiff_canvas", solid(MapColor.PALE_YELLOW).nonOpaque().sounds(BlockSoundGroup.WOOL),
            s -> look("canvas", tex("top", "canvas_top", "side", "canvas_side", "r", "rope"), 0, 90), FACING);
    public static final SkiffBlock COIL = add("skiff_coil", solid(MapColor.PALE_YELLOW).nonOpaque().sounds(BlockSoundGroup.WOOL),
            s -> look("coil", tex("t", "coil")), FACING);
    public static final SkiffBlock BINNACLE = add("skiff_binnacle", solid(MapColor.BROWN).nonOpaque(),
            s -> look("binnacle", tex("side", "binnacle_side", "top", "binnacle_top", "brass", "brass")), FACING);
    public static final SkiffBlock FLAREBOX = add("skiff_flarebox", solid(MapColor.RED).nonOpaque().sounds(BlockSoundGroup.METAL),
            s -> look("flarebox", tex("side", "flarebox_side", "top", "flarebox_top"), 0, 90), FACING);

    // ---------------------------------------------------------------- 舷外：救生圈 · 扶手绳 · 名牌 · 星徽

    public static final SkiffBlock LIFEBUOY = add("skiff_lifebuoy", loose(MapColor.RED).sounds(BlockSoundGroup.WOOL),
            s -> s.get(HANGING)
                    ? look("lifebuoy_flat", tex("t", "lifebuoy"), 270, s.get(SIDE) == Side.RIGHT ? 90 : 270)
                    : look("lifebuoy_flat", tex("t", "lifebuoy")),
            FACING, SIDE, HANGING);
    public static final SkiffBlock GRAB_LINE = add("skiff_grab_line", loose(MapColor.PALE_YELLOW).sounds(BlockSoundGroup.WOOL),
            s -> sided(s, look("grab_line", tex("r", "rope"))), FACING, SIDE);
    public static final SkiffBlock NAMEPLATE = add("skiff_nameplate", loose(MapColor.BROWN),
            s -> sided(s, look("nameplate", tex("t", "name_" + s.get(CHAR).asString()))), FACING, SIDE, CHAR);
    public static final SkiffBlock EMBLEM = add("skiff_emblem", loose(MapColor.GOLD),
            s -> sided(s, look("emblem", tex("t", "star_emblem"))), FACING, SIDE);

    // ---------------------------------------------------------------- 灯 · 桅杆 · 横桁 · 帆

    public static final SkiffBlock LANTERN = add("skiff_lantern",
            loose(MapColor.GOLD).sounds(BlockSoundGroup.LANTERN).luminance(s -> OIL_LIGHT[s.get(OIL)]),
            s -> look(s.get(HANGING) ? "lantern_hanging" : "lantern_standing", tex("l", lanternTexture(s.get(OIL)))),
            HANGING, OIL);
    public static final SkiffBlock MAST = add("skiff_mast", solid(MapColor.OAK_TAN).nonOpaque(),
            s -> look(switch (s.get(MAST_PART)) {
                case ARM -> "mast_arm";
                case TOP -> "mast_top";
                default -> "pole";
            }, tex("p", "pole", "brass", "brass")), FACING, MAST_PART);
    public static final SkiffBlock YARD = add("skiff_yard", loose(MapColor.OAK_TAN),
            s -> look((s.get(FURLED) ? "yard_furled" : "yard") + (s.get(YARD_PART) == Part.FORE ? "_fore" : ""),
                    tex("r", "teak_rail", "f", "sail_furled")), FACING, YARD_PART, FURLED);
    public static final SkiffBlock SAIL = add("skiff_sail", loose(MapColor.PALE_YELLOW).sounds(BlockSoundGroup.WOOL),
            s -> s.get(RAISED)
                    ? look("sail_panel_x" + s.get(BELLY), tex("t", "sail_" + s.get(ROW) + s.get(COL)))
                    : look("empty", tex("particle", "sail_" + s.get(ROW) + s.get(COL))),
            FACING, ROW, COL, BELLY, RAISED);

    private SkiffBlocks() {
    }

    public static void register() {
        BLOCKS.forEach((name, block) -> Registry.register(Registries.BLOCK, Identifier.of(HeavySeasMod.MOD_ID, name), block));
    }

    /** 全部方块，按登记顺序（批量生成工具与判据用）。 */
    public static Map<String, SkiffBlock> all() {
        return Collections.unmodifiableMap(BLOCKS);
    }

    /** 划一下桨的那几块模板：左右舷各一块，扫的方向相反，才都是往船尾去。 */
    private static String stroke(BlockState s) {
        return s.get(STROKE) ? "_stroke_" + (s.get(SIDE) == Side.RIGHT ? "r" : "l") : "";
    }

    static String lanternTexture(int oil) {
        return switch (oil) {
            case 0 -> "lantern";
            case 4 -> "lantern_lit";
            default -> "lantern_lit_o" + oil;
        };
    }

    // ---------------------------------------------------------------- 构造用的小工具

    private static SkiffBlock add(String name, AbstractBlock.Settings settings,
                                     Function<BlockState, SkiffLooks.Look> look, Property<?>... properties) {
        SkiffBlock block = SkiffBlock.create(settings, look, properties);
        BlockState state = block.getStateManager().getDefaultState();
        if (state.contains(FACING)) {
            state = state.with(FACING, net.minecraft.util.math.Direction.SOUTH);
        }
        block.defaultTo(state);
        BLOCKS.put(name, block);
        return block;
    }

    /** 右舷原样，左舷在模板的旋转上再转 180°。 */
    private static SkiffLooks.Look sided(BlockState s, SkiffLooks.Look base) {
        return s.get(SIDE) == Side.RIGHT ? base
                : new SkiffLooks.Look(base.template(), base.textures(), base.x(), Math.floorMod(base.y() + 180, 360));
    }

    private static Map<String, String> with(Map<String, String> base, String key, String value) {
        Map<String, String> copy = new LinkedHashMap<>(base);
        copy.put(key, value);
        return copy;
    }

    /** 挖不动、不掉东西、活塞推不动：船体结构的一部分。 */
    private static AbstractBlock.Settings fixed(MapColor color) {
        return AbstractBlock.Settings.create().mapColor(color).strength(-1.0f, 3_600_000.0f).dropsNothing()
                .pistonBehavior(PistonBehavior.BLOCK).sounds(BlockSoundGroup.WOOD);
    }

    /** 有碰撞箱：人会撞上它。 */
    private static AbstractBlock.Settings solid(MapColor color) {
        return fixed(color);
    }

    /** 没有碰撞箱（桨、绳、帆这类细东西）：轮廓照样点得中。 */
    private static AbstractBlock.Settings loose(MapColor color) {
        return fixed(color).nonOpaque().noCollision();
    }

    /** 判据用：方块名 → 全部状态的样子（去重前）。 */
    public static List<SkiffLooks.Look> looksOf(SkiffBlock block) {
        List<SkiffLooks.Look> out = new ArrayList<>();
        for (BlockState s : block.getStateManager().getStates()) {
            out.add(block.look(s));
        }
        return out;
    }
}
