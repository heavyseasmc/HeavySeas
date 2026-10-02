package io.github.heavyseasmc.mod.world;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.world.liner.LinerBlocks;
import io.github.heavyseasmc.mod.world.liner.LinerProps;
import io.github.heavyseasmc.mod.world.skiff.SkiffBlock;
import io.github.heavyseasmc.mod.world.skiff.SkiffBlocks;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Set;

/**
 * 本模组自己的三页创造物品栏（ADR-0058 §8 Q6；用户 2026-10-02：「我们模组要在创造物品栏单开分类」，不放进游戏自带的分类）。
 *
 * <p>船与建材 · 灯与家具 · 物件与功能。判据 {@code checkCreativeTabs}：源码里不许再往游戏自带的页里加东西、
 * 每件物品都有物品模型与两种语言的名字。大邮轮那一族（ADR-0062）在「船与建材」，它的灯与家具（ADR-0063）在「灯与家具」。
 */
public final class CreativeTabs {

    // ❗页签按 id 的字母顺序排（实测：fittings 跑到了 hull 前面）—— id 取 building < fittings < objects，顺序才对
    public static final RegistryKey<ItemGroup> BUILDING = key("building");
    public static final RegistryKey<ItemGroup> FITTINGS = key("fittings");
    public static final RegistryKey<ItemGroup> OBJECTS = key("objects");

    /** 灯与家具那一页里的救生艇方块（其余救生艇方块都在「船与建材」）。 */
    private static final Set<String> SKIFF_FITTINGS = Set.of("skiff_lantern");

    private CreativeTabs() {
    }

    public static void register() {
        Registry.register(Registries.ITEM_GROUP, BUILDING, FabricItemGroup.builder()
                .icon(() -> new ItemStack(SkiffBlocks.HULL))
                .displayName(Text.translatable("itemGroup.heavyseas.building"))
                .entries((context, entries) -> {
                    skiff(false).forEach(entries::add);
                    LinerBlocks.all().values().forEach(entries::add);   // 大邮轮那一族（ADR-0062）：墙、地、框、线脚全在这一页
                })
                .build());
        Registry.register(Registries.ITEM_GROUP, FITTINGS, FabricItemGroup.builder()
                .icon(() -> new ItemStack(SkiffBlocks.LANTERN))
                .displayName(Text.translatable("itemGroup.heavyseas.fittings"))
                .entries((context, entries) -> {
                    skiff(true).forEach(entries::add);
                    LinerProps.all().values().forEach(entries::add);   // 大邮轮的灯与家具（ADR-0063）
                })
                .build());
        Registry.register(Registries.ITEM_GROUP, OBJECTS, FabricItemGroup.builder()
                .icon(() -> new ItemStack(GullEntity.SPAWN_EGG))
                .displayName(Text.translatable("itemGroup.heavyseas.objects"))
                .entries((context, entries) -> {
                    entries.add(LobbyBoatBlock.ITEM);
                    entries.add(GullEntity.SPAWN_EGG);
                })
                .build());
    }

    private static List<SkiffBlock> skiff(boolean fittings) {
        return SkiffBlocks.all().entrySet().stream()
                .filter(e -> SKIFF_FITTINGS.contains(e.getKey()) == fittings)
                .map(java.util.Map.Entry::getValue)
                .toList();
    }

    private static RegistryKey<ItemGroup> key(String name) {
        return RegistryKey.of(RegistryKeys.ITEM_GROUP, Identifier.of(HeavySeasMod.MOD_ID, name));
    }
}
