package io.github.heavyseasmc.mod.world.liner;

import io.github.heavyseasmc.mod.HeavySeasMod;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.MapColor;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 大邮轮的灯与家具（ADR-0063）：ADR-0061 §5 第二轮定稿的六件 —— 甲板灯柱 · 黄铜落地灯 · 绿罩台灯 · 2 × 2 大桌 ·
 * 两格切斯特菲尔德沙发 · 俱乐部扶手椅（后两件各有米色与绿丝绒两种布）；ADR-0066 加了挂在天花下的三种灯
 * （吸顶花玻璃 · 两格高的黄铜小吊灯 · 3 × 3 加吊杆共 10 格的水晶大吊灯）；客房与阅览室的家具（黄铜床 · 衣柜 · 盥洗台 ·
 * 书柜 · 写字台 · 写字椅）与壁灯（ADR 草稿 furniture）。
 *
 * <p>通用件，不带船名；只在创造模式里拿（挖不动、不掉东西、没有配方，与大邮轮那一族的装饰方块相同），进「灯与家具」页。
 * 判据 {@code checkLinerBlocks} 与装饰方块共用一段（登记写法 {@code prop("liner_…")}）。
 */
public final class LinerProps {

    private static final Map<String, LinerProp> PROPS = new LinkedHashMap<>();

    public static final LinerProp LAMPPOST = prop("liner_lamppost",
            new LinerProp.Spec(LinerProp.Kind.TALL_LAMP, "lamppost", "lamppost_lower", "lamppost_glass", 15),
            MapColor.BLACK, BlockSoundGroup.LANTERN);
    public static final LinerProp FLOOR_LAMP = prop("liner_floor_lamp",
            new LinerProp.Spec(LinerProp.Kind.TALL_LAMP, "floor_lamp", "floor_lamp_lower", "floor_lamp_shade", 14),
            MapColor.GOLD, BlockSoundGroup.LANTERN);
    public static final LinerProp TABLE_LAMP = prop("liner_table_lamp",
            new LinerProp.Spec(LinerProp.Kind.TABLE_LAMP, "table_lamp", "table_lamp", "table_lamp_shade", 12),
            MapColor.EMERALD_GREEN, BlockSoundGroup.LANTERN);
    public static final LinerProp GRAND_TABLE = prop("liner_grand_table",
            new LinerProp.Spec(LinerProp.Kind.GRAND_TABLE, "grand_table", "grand_table", null, 0),
            MapColor.OFF_WHITE, BlockSoundGroup.WOOD);
    public static final LinerProp SOFA_CREAM = prop("liner_sofa_cream",
            new LinerProp.Spec(LinerProp.Kind.SOFA, "sofa", "sofa_cream", null, 0),
            MapColor.PALE_YELLOW, BlockSoundGroup.WOOL);
    public static final LinerProp SOFA_GREEN = prop("liner_sofa_green",
            new LinerProp.Spec(LinerProp.Kind.SOFA, "sofa", "sofa_green", null, 0),
            MapColor.DARK_GREEN, BlockSoundGroup.WOOL);
    public static final LinerProp CLUB_CHAIR_CREAM = prop("liner_club_chair_cream",
            new LinerProp.Spec(LinerProp.Kind.CHAIR, "club_chair", "club_chair_cream", null, 0),
            MapColor.PALE_YELLOW, BlockSoundGroup.WOOL);
    public static final LinerProp CLUB_CHAIR_GREEN = prop("liner_club_chair_green",
            new LinerProp.Spec(LinerProp.Kind.CHAIR, "club_chair", "club_chair_green", null, 0),
            MapColor.DARK_GREEN, BlockSoundGroup.WOOL);
    // 顶灯与吊灯（ADR-0066）：大房间用黄铜小吊灯与水晶大吊灯，走廊、客房这类矮房间用吸顶花玻璃
    public static final LinerProp CEILING_LIGHT = prop("liner_ceiling_light",
            new LinerProp.Spec(LinerProp.Kind.CEILING_LAMP, "ceiling_light", "ceiling_light", "ceiling_light_glass", 15),
            MapColor.GOLD, BlockSoundGroup.LANTERN);
    public static final LinerProp BRASS_CHANDELIER = prop("liner_brass_chandelier",
            new LinerProp.Spec(LinerProp.Kind.CHANDELIER, "brass_chandelier", "brass_chandelier_lower", "brass_chandelier_glow", 15),
            MapColor.GOLD, BlockSoundGroup.LANTERN);
    public static final LinerProp CRYSTAL_CHANDELIER = prop("liner_crystal_chandelier",
            new LinerProp.Spec(LinerProp.Kind.GRAND_CHANDELIER, "crystal_chandelier", "crystal_chandelier", "crystal_chandelier_glow", 15),
            MapColor.GOLD, BlockSoundGroup.GLASS);
    // 骑缝的吸顶灯（ADR-0068；用户看实拍「客房走廊的灯具不居中」）：同一盏花玻璃吸顶灯，两格 / 2 × 2 一件，灯身落在接缝上
    public static final LinerProp CEILING_LIGHT_PAIR = prop("liner_ceiling_light_pair",
            new LinerProp.Spec(LinerProp.Kind.CEILING_PAIR, "ceiling_light_pair", "ceiling_light", "ceiling_light_glass", 15),
            MapColor.GOLD, BlockSoundGroup.LANTERN);
    public static final LinerProp CEILING_LIGHT_QUAD = prop("liner_ceiling_light_quad",
            new LinerProp.Spec(LinerProp.Kind.CEILING_QUAD, "ceiling_light_quad", "ceiling_light", "ceiling_light_glass", 15),
            MapColor.GOLD, BlockSoundGroup.LANTERN);
    // 客房与阅览室的家具（ADR 草稿 furniture；ADR-0069 §2 第 ⑥ 批）：客房按真人尺寸，阅览室的件往大里做
    public static final LinerProp BED = prop("liner_bed",
            new LinerProp.Spec(LinerProp.Kind.BED, "bed", "bed", null, 0),
            MapColor.DARK_GREEN, BlockSoundGroup.WOOL);
    public static final LinerProp WARDROBE = prop("liner_wardrobe",
            new LinerProp.Spec(LinerProp.Kind.WARDROBE, "wardrobe", "wardrobe", null, 0),
            MapColor.BROWN, BlockSoundGroup.WOOD);
    public static final LinerProp WASHSTAND = prop("liner_washstand",
            new LinerProp.Spec(LinerProp.Kind.WASHSTAND, "washstand", "washstand", null, 0),
            MapColor.OFF_WHITE, BlockSoundGroup.WOOD);
    public static final LinerProp BOOKCASE = prop("liner_bookcase",
            new LinerProp.Spec(LinerProp.Kind.BOOKCASE, "bookcase", "bookcase", null, 0),
            MapColor.BROWN, BlockSoundGroup.WOOD);
    public static final LinerProp WRITING_TABLE = prop("liner_writing_table",
            new LinerProp.Spec(LinerProp.Kind.WRITING_TABLE, "writing_table", "writing_table", null, 0),
            MapColor.BROWN, BlockSoundGroup.WOOD);
    public static final LinerProp WRITING_CHAIR = prop("liner_writing_chair",
            new LinerProp.Spec(LinerProp.Kind.WRITING_CHAIR, "writing_chair", "writing_chair", null, 0),
            MapColor.DARK_GREEN, BlockSoundGroup.WOOD);
    // 壁灯（ADR-0069 §2 第 ② 批里那一盏；§4 倾向 A：贴在墙前那一格、墙拆了不掉）：亮度照主次取 12（主灯 15 · 落地灯 14 · 台灯 12）
    public static final LinerProp WALL_SCONCE = prop("liner_wall_sconce",
            new LinerProp.Spec(LinerProp.Kind.SCONCE, "wall_sconce", "wall_sconce", "wall_sconce_shade", 12),
            MapColor.GOLD, BlockSoundGroup.LANTERN);

    private LinerProps() {
    }

    /** 方块与物品一起登记；物品只在创造模式里拿得到。 */
    public static void register() {
        PROPS.forEach((name, block) -> {
            Identifier id = Identifier.of(HeavySeasMod.MOD_ID, name);
            Registry.register(Registries.BLOCK, id, block);
            Registry.register(Registries.ITEM, id, new BlockItem(block, new Item.Settings()));
        });
    }

    /** 全部灯与家具，按登记顺序（批量生成工具、物品栏、客户端的镂空渲染层与判据用）。 */
    public static Map<String, LinerProp> all() {
        return Collections.unmodifiableMap(PROPS);
    }

    private static LinerProp prop(String name, LinerProp.Spec spec, MapColor color, BlockSoundGroup sounds) {
        int level = spec.light();
        AbstractBlock.Settings settings = AbstractBlock.Settings.create().mapColor(color).strength(-1.0f, 3_600_000.0f)
                .dropsNothing().pistonBehavior(PistonBehavior.BLOCK).sounds(sounds).nonOpaque()
                .luminance(s -> LinerProp.lightOf(spec.kind(), s, level));
        LinerProp block = LinerProp.create(settings, spec);
        PROPS.put(name, block);
        return block;
    }
}
