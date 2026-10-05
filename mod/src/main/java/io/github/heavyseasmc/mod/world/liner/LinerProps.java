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
 * 书柜 · 写字台 · 写字椅）与壁灯（ADR 草稿 furniture）；A 甲板新家具（ADR 草稿 furnish）：壁炉两种 · 炉上件两种 · 棕榈两种 ·
 * 藤编扶手椅 / 小圆桌 / 长椅 · 吧台。
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
    // 魔镜（ADR-0083 · ADR-0065 §1 第 4 条落地立镜）：玩家自己世界里那面与北辰号大楼梯平台上那面是同一种方块。
    //   样子是用户 2026-10-04 挑的那一版：银箔框、弧顶小冠饰、镜面「轻」的旧银；同日放大到两格宽三格高、嵌进墙里（摆法 C，liner_props.py 的 mirror_mod_set）
    public static final LinerProp MIRROR = prop("liner_mirror",
            new LinerProp.Spec(LinerProp.Kind.MIRROR, "mirror", "mirror", null, 0),
            MapColor.LIGHT_GRAY, BlockSoundGroup.WOOD);
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
    // A 甲板新家具（ADR 草稿 furnish；用户 2026-10-04「全按倾向」）：休息室白大理石壁炉 + 描金框镜 · 吸烟室桃花心木壁炉 + 桃花心木框油画；
    //   炉火亮度 13（落地灯 14 与壁灯 12 之间），只有正中下面那一格发光
    public static final LinerProp FIREPLACE_MARBLE = prop("liner_fireplace_marble",
            new LinerProp.Spec(LinerProp.Kind.FIREPLACE, "fireplace_marble", "fireplace_marble", "fireplace_marble_glow", 13),
            MapColor.OFF_WHITE, BlockSoundGroup.STONE);
    public static final LinerProp FIREPLACE_MAHOGANY = prop("liner_fireplace_mahogany",
            new LinerProp.Spec(LinerProp.Kind.FIREPLACE, "fireplace_mahogany", "fireplace_mahogany", "fireplace_mahogany_glow", 13),
            MapColor.BROWN, BlockSoundGroup.WOOD);
    public static final LinerProp OVERMANTEL_MIRROR = prop("liner_overmantel_mirror",
            new LinerProp.Spec(LinerProp.Kind.OVERMANTEL, "overmantel_mirror", "overmantel_mirror", null, 0),
            MapColor.GOLD, BlockSoundGroup.GLASS);
    public static final LinerProp OVERMANTEL_PICTURE = prop("liner_overmantel_picture",
            new LinerProp.Spec(LinerProp.Kind.OVERMANTEL, "overmantel_picture", "overmantel_picture", null, 0),
            MapColor.BROWN, BlockSoundGroup.WOOD);
    //   棕榈：两格高的藤编花篮（咖啡座）· 三格高的大棵木桶（休息室灯井下）
    public static final LinerProp PALM = prop("liner_palm",
            new LinerProp.Spec(LinerProp.Kind.PALM, "palm", "palm", null, 0),
            MapColor.DARK_GREEN, BlockSoundGroup.AZALEA_LEAVES);
    public static final LinerProp PALM_TALL = prop("liner_palm_tall",
            new LinerProp.Spec(LinerProp.Kind.PALM_TALL, "palm_tall", "palm_tall", null, 0),
            MapColor.DARK_GREEN, BlockSoundGroup.AZALEA_LEAVES);
    //   藤编三件：白漆藤配深绿丝绒坐垫（大面斜纹、桌面与搁板篮纹）
    public static final LinerProp WICKER_CHAIR = prop("liner_wicker_chair",
            new LinerProp.Spec(LinerProp.Kind.WICKER_CHAIR, "wicker_chair", "wicker_chair", null, 0),
            MapColor.OFF_WHITE, BlockSoundGroup.WOOD);
    public static final LinerProp WICKER_TABLE = prop("liner_wicker_table",
            new LinerProp.Spec(LinerProp.Kind.WICKER_TABLE, "wicker_table", "wicker_table", null, 0),
            MapColor.OFF_WHITE, BlockSoundGroup.WOOD);
    public static final LinerProp WICKER_SETTEE = prop("liner_wicker_settee",
            new LinerProp.Spec(LinerProp.Kind.WICKER_SETTEE, "wicker_settee", "wicker_settee", null, 0),
            MapColor.OFF_WHITE, BlockSoundGroup.WOOD);
    //   吧台：带台后酒架那一版（4 长 × 2 高）
    public static final LinerProp BAR_COUNTER = prop("liner_bar_counter",
            new LinerProp.Spec(LinerProp.Kind.BAR_COUNTER, "bar_counter", "bar_counter", null, 0),
            MapColor.BROWN, BlockSoundGroup.WOOD);
    // 艇甲板设备（ADR-0080 §7 · ADR 草稿 deckgear；用户 2026-10-04「全按倾向」）：四分圆摇臂式吊艇架（白臂黑座）· 喇叭口通风筒高 / 矮。
    //   模板与贴图由 liner_props_deck.py 经 liner_props.py --write 写（#b 白漆 · #k 铁与吊索 / 喇叭与口）
    public static final LinerProp DAVIT = prop("liner_davit",
            new LinerProp.Spec(LinerProp.Kind.DAVIT, "davit", "davit", null, 0),
            MapColor.WHITE, BlockSoundGroup.METAL);
    public static final LinerProp VENTILATOR = prop("liner_ventilator",
            new LinerProp.Spec(LinerProp.Kind.VENTILATOR, "ventilator", "ventilator", null, 0),
            MapColor.WHITE, BlockSoundGroup.METAL);
    public static final LinerProp VENTILATOR_SHORT = prop("liner_ventilator_short",
            new LinerProp.Spec(LinerProp.Kind.VENTILATOR_SHORT, "ventilator_short", "ventilator_short", null, 0),
            MapColor.WHITE, BlockSoundGroup.METAL);
    // 开局的钟（ADR-0084 第三轮；用户 2026-10-04 定门形钟架）：演习艇艏柱那一头里侧，坐在艇里的人右键敲钟开阵容面板（DrillSkiff.ringBell）
    public static final LinerProp DRILL_BELL = prop("liner_drill_bell",
            new LinerProp.Spec(LinerProp.Kind.DRILL_BELL, "drill_bell", "drill_bell", null, 0),
            MapColor.GOLD, BlockSoundGroup.METAL);
    // C3 第二轮 · c3-gallery：肖像画框（ADR-0086 §2 第 7–10 条：桃花心木配描金内压条 · 画面 ×2、原色；画心是木刻头像，ADR-0090 §8）。
    //   画的是谁是方块属性 sitter（八个值）；中、大两档带画框灯（属性 lamp，亮度 12 同壁灯，灯那一排两格都发光）；右键看牌（PortraitView）。
    //   模板与贴图由 liner_props_gallery.py 经 liner_props.py --write 写（#b 框 · #p 画面 · #g 灯管）
    public static final LinerProp PORTRAIT_SMALL = prop("liner_portrait_small",
            new LinerProp.Spec(LinerProp.Kind.PORTRAIT_SMALL, "portrait_small", "portrait_small", null, 0),
            MapColor.BROWN, BlockSoundGroup.WOOD);
    public static final LinerProp PORTRAIT_MEDIUM = prop("liner_portrait_medium",
            new LinerProp.Spec(LinerProp.Kind.PORTRAIT_MEDIUM, "portrait_medium", "portrait_medium", "portrait_medium_glow", 12),
            MapColor.BROWN, BlockSoundGroup.WOOD);
    public static final LinerProp PORTRAIT_LARGE = prop("liner_portrait_large",
            new LinerProp.Spec(LinerProp.Kind.PORTRAIT_LARGE, "portrait_large", "portrait_large", "portrait_large_glow", 12),
            MapColor.BROWN, BlockSoundGroup.WOOD);
    // C3 第二轮 · c3-table（ADR-0086 §2 第 3 · 4 · 6 条；用户 2026-10-05「全部按倾向」）：
    //   斜面海图桌 C′（3 宽 × 2 深，海图一格一张 32 像素/格）· 讲台 C（方座 + 黄铜绿罩阅读灯，1 × 2 高，书常驻摊开）。
    //   讲台灯亮度 12，同绿罩台灯与壁灯：读书用的那一盏小灯，不是一间屋的主灯（主灯 15 · 落地灯 14 · 炉火 13）
    public static final LinerProp CHART_TABLE = prop("liner_chart_table",
            new LinerProp.Spec(LinerProp.Kind.CHART_TABLE, "chart_table", "chart_table", null, 0),
            MapColor.BROWN, BlockSoundGroup.WOOD);
    public static final LinerProp LECTERN = prop("liner_lectern",
            new LinerProp.Spec(LinerProp.Kind.LECTERN, "lectern", "lectern", "lectern_glow", 12),
            MapColor.BROWN, BlockSoundGroup.WOOD);
    // C3 第二轮 · c3-deck（ADR-0086 §2 第 11–15 条，用户 2026-10-05「全部按倾向」）：露天甲板的躺椅 B · C（一种方块两种铺法）、
    //   前桅瞭望台 A（台面一圈 · 口沿一圈两件）、空心桅杆（竖井 · 门）、望远镜柜 A、小警钟。模板与贴图由 liner_props_opendeck.py 经 liner_props.py --write 写
    public static final LinerProp DECK_CHAIR = prop("liner_deck_chair",
            new LinerProp.Spec(LinerProp.Kind.DECK_CHAIR, "deck_chair", "deck_chair", null, 0),
            MapColor.BROWN, BlockSoundGroup.WOOD);
    public static final LinerProp LOOKOUT_FLOOR = prop("liner_lookout_floor",
            new LinerProp.Spec(LinerProp.Kind.LOOKOUT_FLOOR, "lookout_floor", "lookout", null, 0),
            MapColor.TERRACOTTA_YELLOW, BlockSoundGroup.METAL);
    public static final LinerProp LOOKOUT_RIM = prop("liner_lookout_rim",
            new LinerProp.Spec(LinerProp.Kind.LOOKOUT_RIM, "lookout_rim", "lookout", null, 0),
            MapColor.TERRACOTTA_YELLOW, BlockSoundGroup.METAL);
    public static final LinerProp HOLLOW_MAST = prop("liner_hollow_mast",
            new LinerProp.Spec(LinerProp.Kind.HOLLOW_MAST, "hollow_mast", "hollow_mast", null, 0),
            MapColor.TERRACOTTA_YELLOW, BlockSoundGroup.METAL);
    public static final LinerProp TELESCOPE_CABINET = prop("liner_telescope_cabinet",
            new LinerProp.Spec(LinerProp.Kind.TELESCOPE_CABINET, "telescope_cabinet", "telescope_cabinet", null, 0),
            MapColor.BROWN, BlockSoundGroup.WOOD);
    public static final LinerProp ALARM_BELL = prop("liner_alarm_bell",
            new LinerProp.Spec(LinerProp.Kind.ALARM_BELL, "alarm_bell", "alarm_bell", null, 0),
            MapColor.GOLD, BlockSoundGroup.METAL);

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
