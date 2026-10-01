package io.github.heavyseasmc.mod.datagen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.world.skiff.SkiffBlock;
import io.github.heavyseasmc.mod.world.skiff.SkiffBlocks;
import io.github.heavyseasmc.mod.world.skiff.SkiffLooks;
import net.fabricmc.fabric.api.datagen.v1.FabricDataOutput;
import net.fabricmc.fabric.api.datagen.v1.provider.FabricModelProvider;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.data.client.BlockStateModelGenerator;
import net.minecraft.data.client.BlockStateSupplier;
import net.minecraft.data.client.ItemModelGenerator;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 救生艇方块的方块状态文件与带贴图的方块模型（「形状 × 材质」批量出，ADR-0053 §5）。
 *
 * <p>每个方块的每个状态都问一遍 {@link SkiffBlock#look}：用哪块模板、贴哪几张图、转多少度 —— 与运行时算轮廓的是同一个函数。
 * 同一块模板配同一组贴图只出一个模型；模型名 = 模板名，同一块模板配了几组贴图时后面接上那几张会变的贴图名。
 * 模板与贴图本身不在这里：它们由 {@code docs/tools/scene/decor_textures.py --write} 画好入库。
 */
final class SkiffModels extends FabricModelProvider {

    SkiffModels(FabricDataOutput output) {
        super(output);
    }

    @Override
    public void generateBlockStateModels(BlockStateModelGenerator generator) {
        SkiffBlocks.all().forEach((name, block) -> {
            List<BlockState> states = block.getStateManager().getStates();
            Map<BlockState, SkiffLooks.Look> looks = new LinkedHashMap<>();
            for (BlockState state : states) {
                looks.put(state, block.look(state));
            }
            Map<String, Identifier> models = uploadModels(generator, name, looks.values());
            JsonObject variants = new JsonObject();
            looks.forEach((state, look) -> {
                JsonObject variant = new JsonObject();
                variant.addProperty("model", models.get(modelKey(look)).toString());
                if (look.x() != 0) {
                    variant.addProperty("x", look.x());
                }
                if (look.y() != 0) {
                    variant.addProperty("y", look.y());
                }
                variants.add(variantKey(state), variant);
            });
            JsonObject root = new JsonObject();
            root.add("variants", variants);
            generator.blockStateCollector.accept(new BlockStateSupplier() {
                @Override
                public Block getBlock() {
                    return block;
                }

                @Override
                public JsonElement get() {
                    return root;
                }
            });
        });
    }

    @Override
    public void generateItemModels(ItemModelGenerator generator) {
        // 这一套方块只随船体结构放进世界，没有物品形态。
    }

    /** 一个方块用到的每一种「模板 + 贴图」各出一个模型；返回 模型键 → 模型 id。 */
    private static Map<String, Identifier> uploadModels(BlockStateModelGenerator generator, String block,
                                                        Iterable<SkiffLooks.Look> looks) {
        Map<String, SkiffLooks.Look> distinct = new LinkedHashMap<>();
        for (SkiffLooks.Look look : looks) {
            distinct.putIfAbsent(modelKey(look), look);
        }
        // 同一块模板配了几组贴图：找出这几组里会变的那几个纹理变量，拿它们的贴图名区分模型
        Map<String, Set<String>> varying = new LinkedHashMap<>();
        Map<String, List<SkiffLooks.Look>> byTemplate = new LinkedHashMap<>();
        distinct.values().forEach(l -> byTemplate.computeIfAbsent(l.template(), t -> new ArrayList<>()).add(l));
        byTemplate.forEach((template, group) -> {
            Set<String> keys = new LinkedHashSet<>();
            for (SkiffLooks.Look l : group) {
                l.textures().forEach((k, v) -> {
                    if (group.stream().anyMatch(o -> !v.equals(o.textures().get(k)))) {
                        keys.add(k);
                    }
                });
            }
            varying.put(template, keys);
        });
        Map<String, Identifier> ids = new LinkedHashMap<>();
        distinct.forEach((key, look) -> {
            StringBuilder path = new StringBuilder("block/skiff/").append(block).append('/').append(look.template());
            for (String k : varying.get(look.template())) {
                path.append('_').append(look.textures().get(k));
            }
            Identifier id = Identifier.of(HeavySeasMod.MOD_ID, path.toString());
            JsonObject model = new JsonObject();
            model.addProperty("parent", HeavySeasMod.MOD_ID + ":" + SkiffLooks.TEMPLATE_DIR + look.template());
            JsonObject textures = new JsonObject();
            String first = null;
            for (Map.Entry<String, String> e : look.textures().entrySet()) {
                String ref = HeavySeasMod.MOD_ID + ":" + SkiffLooks.TEXTURE_DIR + e.getValue();
                textures.addProperty(e.getKey(), ref);
                if (first == null) {
                    first = ref;
                }
            }
            textures.addProperty("particle", first);
            model.add("textures", textures);
            generator.modelCollector.accept(id, () -> model);
            ids.put(key, id);
        });
        return ids;
    }

    private static String modelKey(SkiffLooks.Look look) {
        return look.template() + look.textures();
    }

    /** 与 Minecraft 自己写方块状态文件时同一种键：属性按名字排序，名=值，逗号隔开。 */
    private static String variantKey(BlockState state) {
        List<Property<?>> properties = new ArrayList<>(state.getProperties());
        properties.sort(Comparator.comparing(Property::getName));
        StringBuilder key = new StringBuilder();
        for (Property<?> property : properties) {
            if (!key.isEmpty()) {
                key.append(',');
            }
            key.append(property.getName()).append('=').append(valueName(state, property));
        }
        return key.toString();
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.name(state.get(property));
    }
}
