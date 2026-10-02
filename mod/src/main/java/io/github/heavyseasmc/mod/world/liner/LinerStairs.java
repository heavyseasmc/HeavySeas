package io.github.heavyseasmc.mod.world.liner;

import io.github.heavyseasmc.mod.HeavySeasMod;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
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
 * 北辰号的大楼梯一族（ADR-0069 §2 第 ③ 批；ADR 草稿 {@code docs/adr/DRAFT-stairs.md}）：替换第一段里游戏自带的深色橡木楼梯与铁栏杆，
 * 加上起步柱与光井收口。模板与贴图由 {@code docs/tools/scene/liner_stairs.py --write} 画好入库
 * （{@code models/block/liner/template/stairs/} · {@code textures/block/liner/stairs/}）。
 *
 * <p>通用件，不带船名；与大邮轮那一族一样只在创造模式里拿（挖不动、不掉东西、没有配方、活塞推不动）。
 * 七件都进「建筑」页（跟在大邮轮那一族后面）。
 */
public final class LinerStairs {

    private static final Map<String, Block> BLOCKS = new LinkedHashMap<>();

    /** 桃花心木楼梯（踏面、踢面都是木头）。 */
    public static final LinerStairBlock STAIR_MAHOGANY = add("liner_stair_mahogany",
            new LinerStairBlock(LinerBlocks.MAHOGANY_WALL.getDefaultState(), solid(MapColor.DARK_RED, BlockSoundGroup.WOOD), false));
    /** 铺地毯的桃花心木楼梯：踏面与踢面是朱红地毯、每道折角一根黄铜压毯杆；前缘、侧面、楼梯底与木楼梯相同。 */
    public static final LinerStairBlock STAIR_CARPET = add("liner_stair_carpet",
            new LinerStairBlock(LinerBlocks.MAHOGANY_WALL.getDefaultState(), solid(MapColor.RED, BlockSoundGroup.WOOL), true));
    public static final LinerStairPiece BALUSTRADE = add("liner_balustrade",
            LinerStairPiece.create(piece(MapColor.BLACK, BlockSoundGroup.METAL), LinerStairPiece.Kind.BALUSTRADE));
    public static final LinerStairPiece BALUSTRADE_SLOPE = add("liner_balustrade_slope",
            LinerStairPiece.create(piece(MapColor.BLACK, BlockSoundGroup.METAL), LinerStairPiece.Kind.SLOPE));
    public static final LinerStairPiece NEWEL_POST = add("liner_newel_post",
            LinerStairPiece.create(piece(MapColor.DARK_RED, BlockSoundGroup.WOOD), LinerStairPiece.Kind.NEWEL));
    public static final LinerStairPiece NEWEL_LAMP = add("liner_newel_lamp",
            LinerStairPiece.create(piece(MapColor.DARK_RED, BlockSoundGroup.WOOD)
                    .luminance(s -> s.contains(LinerStairPiece.LIT) && s.get(LinerStairPiece.LIT) ? LinerStairPiece.LAMP_LIGHT : 0),
                    LinerStairPiece.Kind.NEWEL_LAMP));
    public static final LinerStairPiece WELL_TRIM = add("liner_well_trim",
            LinerStairPiece.create(piece(MapColor.OFF_WHITE, BlockSoundGroup.WOOD), LinerStairPiece.Kind.WELL_TRIM));
    // 平台正面的木作主景（立框 · 横楣 · 钟面，钟面不写字）：贴在墙前面那一格，照木壁柱 / 顶帽的约定
    public static final LinerStairPiece FEATURE_POST = add("liner_feature_post",
            LinerStairPiece.create(piece(MapColor.DARK_RED, BlockSoundGroup.WOOD), LinerStairPiece.Kind.FEATURE_POST));
    public static final LinerStairPiece FEATURE_LINTEL = add("liner_feature_lintel",
            LinerStairPiece.create(piece(MapColor.DARK_RED, BlockSoundGroup.WOOD), LinerStairPiece.Kind.FEATURE_LINTEL));
    public static final LinerStairPiece CLOCK = add("liner_clock",
            LinerStairPiece.create(piece(MapColor.DARK_RED, BlockSoundGroup.WOOD), LinerStairPiece.Kind.CLOCK));

    private LinerStairs() {
    }

    /** 方块与物品一起登记；物品只在创造模式里拿得到。 */
    public static void register() {
        BLOCKS.forEach((name, block) -> {
            Identifier id = Identifier.of(HeavySeasMod.MOD_ID, name);
            Registry.register(Registries.BLOCK, id, block);
            Registry.register(Registries.ITEM, id, new BlockItem(block, new Item.Settings()));
        });
    }

    /** 全部方块，按登记顺序（批量生成工具、物品栏与判据用）。 */
    public static Map<String, Block> all() {
        return Collections.unmodifiableMap(BLOCKS);
    }

    /** 物品栏里那一格照哪个状态的模型画：栏杆画一段东西走向的直栏杆；别的用默认状态。 */
    public static BlockState displayState(Block block) {
        if (block instanceof LinerStairPiece p && p.kind() == LinerStairPiece.Kind.BALUSTRADE) {
            return block.getDefaultState().with(LinerStairPiece.EAST, true).with(LinerStairPiece.WEST, true);
        }
        return block.getDefaultState();
    }

    /** 楼梯物品要不要在物品栏里转半圈看正面：楼梯模板「朝北 = 高的那一边在北」，正面（踏步）在南；收口与主景照檐口，正面朝南。 */
    public static boolean frontInGui(Block block) {
        return block instanceof LinerStairBlock
                || block instanceof LinerStairPiece p && (p.kind() == LinerStairPiece.Kind.WELL_TRIM
                || p.kind() == LinerStairPiece.Kind.FEATURE_POST || p.kind() == LinerStairPiece.Kind.FEATURE_LINTEL
                || p.kind() == LinerStairPiece.Kind.CLOCK);
    }

    /** 斜栏杆 · 起步灯的物品另有一块缩小的模板；别的用方块那一格的模型（{@code null}）。 */
    public static LinerLooks.Look itemLook(Block block) {
        return block instanceof LinerLooks.Styled s ? s.itemLook() : null;
    }

    private static <T extends Block> T add(String name, T block) {
        BLOCKS.put(name, block);
        return block;
    }

    /** 挖不动、不掉东西、活塞推不动（与大邮轮那一族相同）。楼梯是不透光的整块件（照游戏的楼梯：按形状剔面、半透光）。 */
    private static AbstractBlock.Settings solid(MapColor color, BlockSoundGroup sounds) {
        return AbstractBlock.Settings.create().mapColor(color).strength(-1.0f, 3_600_000.0f).dropsNothing()
                .pistonBehavior(PistonBehavior.BLOCK).sounds(sounds);
    }

    /** 栏杆、起步柱、收口：不满一格、不挡光、不剔相邻的面。 */
    private static AbstractBlock.Settings piece(MapColor color, BlockSoundGroup sounds) {
        return solid(color, sounds).nonOpaque();
    }
}
