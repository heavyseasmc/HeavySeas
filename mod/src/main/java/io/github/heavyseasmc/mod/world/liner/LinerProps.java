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
 * 两格切斯特菲尔德沙发 · 俱乐部扶手椅（后两件各有米色与绿丝绒两种布）。
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
                .luminance(s -> LinerProp.lightOf(s, level));
        LinerProp block = LinerProp.create(settings, spec);
        PROPS.put(name, block);
        return block;
    }
}
