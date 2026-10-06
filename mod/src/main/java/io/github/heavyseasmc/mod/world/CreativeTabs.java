package io.github.heavyseasmc.mod.world;

import io.github.heavyseasmc.mod.HeavySeasMod;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 本模组自己的创造物品栏（ADR-0058 §8 Q6；用户 2026-10-02：「我们模组要在创造物品栏单开分类」，不放进游戏自带的分类）。
 *
 * <p>ADR-0093 B8：照 Minecraft 自带物品栏按用途分页、页内按材料成套排（白漆 → 描金 → 桃花心木 → 柚木 → 地毯 → 钢）；每页的图标是那一页第一件。
 * 每件物品只进一页、每件都要进（场景用的几件除外，{@link #NOT_IN_TABS}）：起服时核对（{@link #register}），
 * 构建时 {@code checkItemCatalog} 从源码再核一遍。
 */
public final class CreativeTabs {

    /** 一页：id（❗页签按 id 的字母顺序排 —— 实测 fittings 跑到过 hull 前面 —— 所以 id 带 p1…p9）与页内物品。 */
    record Page(String id, List<String> items) {
    }

    // ---- 页表开始（checkItemCatalog 读这一段）
    static final List<Page> PAGES = List.of(
            new Page("p1_materials", List.of(
                    "liner_wall_white", "liner_wall", "liner_ceiling", "liner_wainscot", "liner_frame_white", "liner_arch",
                    "liner_frame_gilt", "liner_frame_gilt_wide",
                    "liner_mahogany_wall", "liner_wainscot_mahogany", "liner_frame_mahogany", "liner_frame_mahogany_gilt",
                    "liner_teak_deck",
                    "liner_carpet",
                    "liner_hull_white", "liner_hull_black", "liner_hull_red", "liner_hull_waterline", "liner_hull_buff",
                    "liner_funnel_buff", "liner_funnel_black")),
            new Page("p2_trim", List.of(
                    "liner_cornice", "liner_cornice_thin", "liner_chair_rail", "liner_pilaster", "liner_door_casing",
                    "liner_door_casing_tall", "liner_trellis",
                    "liner_skirting", "liner_pilaster_mahogany", "liner_capping_mahogany")),
            new Page("p3_openings", List.of(
                    "liner_door_cabin", "liner_door_double",
                    "liner_window_1x1", "liner_window_1x2", "liner_window_1x3", "liner_window_2x1", "liner_window_2x2",
                    "liner_window_2x3", "liner_window_3x1", "liner_window_3x2", "liner_window_3x3",
                    "liner_porthole_black", "liner_porthole_white", "liner_porthole_inner", "liner_porthole_inner_plain",
                    "liner_dome_glass")),
            new Page("p4_stairs", List.of(
                    "liner_stair_mahogany", "liner_stair_carpet", "liner_well_trim",
                    "liner_balustrade", "liner_balustrade_slope", "liner_newel_post", "liner_newel_lamp")),
            new Page("p5_lights", List.of(
                    "liner_ceiling_light", "liner_brass_chandelier",
                    "liner_crystal_chandelier", "liner_wall_sconce", "liner_floor_lamp", "liner_table_lamp", "liner_lamppost")),
            new Page("p6_furniture", List.of(
                    "liner_sofa_cream", "liner_sofa_green", "liner_club_chair_cream", "liner_club_chair_green",
                    "liner_grand_table", "liner_writing_table", "liner_writing_chair", "liner_bookcase", "liner_wardrobe",
                    "liner_washstand", "liner_bar_counter",
                    "liner_bed",
                    "liner_fireplace_marble", "liner_fireplace_mahogany", "liner_overmantel_mirror", "liner_overmantel_picture",
                    "liner_clock",
                    "liner_wicker_chair", "liner_wicker_table", "liner_wicker_settee",
                    "liner_palm", "liner_palm_tall")),
            new Page("p7_deck", List.of(
                    "liner_deck_chair", "liner_ventilator", "liner_ventilator_short", "liner_davit")),
            new Page("p8_special", List.of(
                    "liner_mirror", "liner_drill_bell", "liner_chart_table", "liner_lectern",
                    "liner_portrait_small", "liner_portrait_medium", "liner_portrait_large",
                    "liner_lookout_floor", "liner_lookout_rim", "liner_hollow_mast", "liner_telescope_cabinet", "liner_alarm_bell",
                    "liner_dome_rib", "gull_spawn_egg")),
            new Page("p9_skiff", List.of(
                    "skiff_hull", "skiff_hull_waterline", "skiff_hull_paneled", "skiff_stem", "skiff_gunwale",
                    "skiff_gunwale_paneled", "skiff_floorboards", "skiff_thwart", "skiff_oar", "skiff_rowlock", "skiff_rudder",
                    "skiff_tiller", "skiff_cask", "skiff_bailer", "skiff_canvas", "skiff_coil", "skiff_binnacle", "skiff_flarebox",
                    "skiff_lifebuoy", "skiff_grab_line", "skiff_nameplate", "skiff_emblem", "skiff_lantern", "skiff_mast",
                    "skiff_yard", "skiff_sail")));
    // ---- 页表结束

    /**
     * 不进物品栏的：场景用的物品（布景板、补给箱、海图上的小铜船，指令与对局摆它们）；骑缝的两格 / 四格吸顶灯
     * （ADR-0093 B6，用户 2026-10-07 定：物品栏只留一种吸顶灯，偏半格时由装修锤把它换成占两格 / 四格的那一件 ——
     * 亮度对称、每一块都点得中；船上照用这两件，/give 照样拿得到）。
     */
    static final Set<String> NOT_IN_TABS = Set.of(
            "coast_backdrop", "lighthouse_backdrop", "pier_backdrop", "supply_crate", "chart_ship",
            "liner_ceiling_light_pair", "liner_ceiling_light_quad");

    private CreativeTabs() {
    }

    /** 物品都登记完之后调用：先核对页表与登记的物品对得上（不对就不开服 —— 漏进页表的物品在游戏里只是「找不到」，不报错）。 */
    public static void register() {
        List<String> problems = problems();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("创造物品栏页表与登记的物品对不上：" + String.join("；", problems));
        }
        for (Page page : PAGES) {
            RegistryKey<ItemGroup> key = RegistryKey.of(RegistryKeys.ITEM_GROUP, Identifier.of(HeavySeasMod.MOD_ID, page.id()));
            Registry.register(Registries.ITEM_GROUP, key, FabricItemGroup.builder()
                    .icon(() -> new ItemStack(item(page.items().get(0))))
                    .displayName(Text.translatable("itemGroup." + HeavySeasMod.MOD_ID + "." + page.id()))
                    .entries((context, entries) -> page.items().forEach(id -> entries.add(item(id))))
                    .build());
        }
    }

    /** 页表与登记的物品之间的出入：没进页的 · 进了两次的 · 页表里写了却没登记的。 */
    static List<String> problems() {
        Set<String> registered = new TreeSet<>();
        for (Identifier id : Registries.ITEM.getIds()) {
            if (id.getNamespace().equals(HeavySeasMod.MOD_ID)) {
                registered.add(id.getPath());
            }
        }
        List<String> problems = new ArrayList<>();
        Set<String> listed = new HashSet<>();
        for (Page page : PAGES) {
            for (String id : page.items()) {
                if (!listed.add(id)) {
                    problems.add(id + " 进了两次");
                }
                if (!registered.contains(id)) {
                    problems.add(page.id() + " 里的 " + id + " 没有登记");
                }
            }
        }
        for (String id : registered) {
            if (!listed.contains(id) && !NOT_IN_TABS.contains(id)) {
                problems.add(id + " 没进任何一页");
            }
        }
        return problems;
    }

    private static Item item(String id) {
        Item item = Registries.ITEM.get(Identifier.of(HeavySeasMod.MOD_ID, id));
        if (item == Items.AIR) {
            throw new IllegalStateException("创造物品栏：" + id + " 没有登记");
        }
        return item;
    }
}
