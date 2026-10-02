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
 * （与救生艇那一族相同，ADR-0058 Q6）。分三层：底面（墙、平顶、甲板、地毯）· 结构（大框、护墙、腰线、檐口、壁柱、顶帽）·
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
    public static final LinerBlock CARPET = add("liner_carpet", solid(MapColor.RED).sounds(BlockSoundGroup.WOOL), LinerBlock.Kind.CARPET,
            s -> look("floor", tex("top", carpetTexture(s), "side", "mahogany")), NORTH, EAST, SOUTH, WEST, CELL);

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
    public static final LinerBlock PILASTER = add("liner_pilaster", piece(MapColor.OFF_WHITE), LinerBlock.Kind.WALL_PIECE,
            s -> look("pilaster", tex("p", "pilaster")), FACING);
    public static final LinerBlock PILASTER_MAHOGANY = add("liner_pilaster_mahogany", piece(MapColor.DARK_RED), LinerBlock.Kind.PILASTER_SIDE,
            s -> look(s.get(SIDE) == PilasterSide.LEFT ? "pilaster_left" : "pilaster_right", tex("p", "pilaster_mahogany")),
            FACING, SIDE);
    public static final LinerBlock CAPPING_MAHOGANY = add("liner_capping_mahogany", piece(MapColor.DARK_RED), LinerBlock.Kind.CAPPING,
            s -> s.get(END) == CappingEnd.NONE
                    ? look("capping", tex("c", "capping_mahogany"))
                    : look("capping_" + s.get(END).asString(), tex("c", "capping_mahogany", "p", "pilaster_mahogany")),
            FACING, END);

    private LinerBlocks() {
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

    /** 地毯这一格的那一张：四边都接着走花纹（第几格），否则画不接的那几边的毯边。 */
    static String carpetTexture(BlockState s) {
        String mask = LinerConnect.Rules.carpetMask(s.get(NORTH), s.get(EAST), s.get(SOUTH), s.get(WEST));
        if (!mask.isEmpty()) {
            return "carpet_border_" + mask;
        }
        int cell = s.get(CELL);
        return "carpet_cell_" + cell % LinerConnect.CARPET_PERIOD + cell / LinerConnect.CARPET_PERIOD;
    }

    /** 地毯四边那几个属性按方向取（结构旋转时用）。 */
    static BooleanProperty side(Direction d) {
        return switch (d) {
            case NORTH -> NORTH;
            case EAST -> EAST;
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            default -> throw new IllegalArgumentException(d.toString());
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
