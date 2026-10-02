package io.github.heavyseasmc.mod.datagen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.world.liner.LinerBlock;
import io.github.heavyseasmc.mod.world.liner.LinerBlocks;
import io.github.heavyseasmc.mod.world.liner.LinerLooks;
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
import net.minecraft.data.client.ModelIds;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 自制装饰方块的方块状态文件与带贴图的方块模型（「形状 × 材质」批量出，ADR-0053 §5 · ADR-0056 · ADR-0062）：
 * 救生艇一族（{@code block/skiff/}）与大邮轮一族（{@code block/liner/}）。
 *
 * <p>每个方块的每个状态都问一遍它的 {@code look}：用哪块模板、贴哪几张图、转多少度 —— 与运行时算轮廓的是同一个函数。
 * 同一块模板配同一组贴图只出一个模型；模型名 = 模板名，同一块模板配了几组贴图时后面接上那几张会变的贴图名。
 * 模板与贴图本身不在这里：由 {@code docs/tools/scene/decor_textures.py --write} · {@code liner_decor.py --write} 画好入库。
 *
 * <p>一个生成器管两族：两个生成器同名会被批量生成工具拒掉，而且两族出模型的规则本来就该是同一份。
 */
final class DecorModels extends FabricModelProvider {

    /** 两族共用的「样子」：模板、纹理变量 → 贴图名、方块状态的 x / y 旋转。 */
    private record Look(String template, Map<String, String> textures, int x, int y) {
    }

    DecorModels(FabricDataOutput output) {
        super(output);
    }

    @Override
    public void generateBlockStateModels(BlockStateModelGenerator generator) {
        SkiffBlocks.all().forEach((name, block) -> family(generator, "skiff", SkiffLooks.TEMPLATE_DIR, SkiffLooks.TEXTURE_DIR,
                name, block, s -> skiff(block.look(s)), SkiffBlocks.displayState(block), false));
        LinerBlocks.all().forEach((name, block) -> family(generator, "liner", LinerLooks.TEMPLATE_DIR, LinerLooks.TEXTURE_DIR,
                name, block, s -> liner(block, s), block.getDefaultState(), true));
    }

    @Override
    public void generateItemModels(ItemModelGenerator generator) {
        // 物品模型在上面逐块登记（指向摆出来那一版的方块模型），这里没有别的。
    }

    private static Look skiff(SkiffLooks.Look l) {
        return new Look(l.template(), l.textures(), l.x(), l.y());
    }

    private static Look liner(LinerBlock block, BlockState s) {
        LinerLooks.Look l = block.look(s);
        return new Look(l.template(), l.textures(), 0, l.y());
    }

    /**
     * 一个方块：每个状态的方块状态项 + 用到的模型 + 物品模型。
     *
     * @param frontInGui 物品栏里要看见正面：模板一律正面朝南，而物品栏那一格默认看的是北面与东面 ——
     *                   大邮轮那一族的大框、墙板、挂墙的件从默认角度只看得见背面或一条边，所以物品模型在物品栏里转半圈
     */
    private static void family(BlockStateModelGenerator generator, String family, String templateDir, String textureDir,
                               String name, Block block, Function<BlockState, Look> lookOf, BlockState display,
                               boolean frontInGui) {
        Map<BlockState, Look> looks = new LinkedHashMap<>();
        for (BlockState state : block.getStateManager().getStates()) {
            looks.put(state, lookOf.apply(state));
        }
        Map<String, Identifier> models = uploadModels(generator, family, templateDir, textureDir, name, looks.values());
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
        // 物品（创造物品栏里那一格、手里拿着的样子）= 摆出来看得见的那一版的模型
        Identifier itemParent = models.get(modelKey(lookOf.apply(display)));
        if (frontInGui && display.contains(Properties.HORIZONTAL_FACING)) {
            JsonObject item = new JsonObject();
            item.addProperty("parent", itemParent.toString());
            JsonObject gui = new JsonObject();
            gui.add("rotation", array(30, 45, 0));
            gui.add("translation", array(0, 0, 0));
            gui.add("scale", array(0.625, 0.625, 0.625));
            JsonObject displays = new JsonObject();
            displays.add("gui", gui);
            item.add("display", displays);
            generator.modelCollector.accept(ModelIds.getItemModelId(block.asItem()), () -> item);
        } else {
            generator.registerParentedItemModel(block, itemParent);
        }
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
    }

    private static JsonArray array(Number... values) {
        JsonArray a = new JsonArray();
        for (Number v : values) {
            a.add(v);
        }
        return a;
    }

    /** 一个方块用到的每一种「模板 + 贴图」各出一个模型；返回 模型键 → 模型 id。 */
    private static Map<String, Identifier> uploadModels(BlockStateModelGenerator generator, String family, String templateDir,
                                                        String textureDir, String block, Iterable<Look> looks) {
        Map<String, Look> distinct = new LinkedHashMap<>();
        for (Look look : looks) {
            distinct.putIfAbsent(modelKey(look), look);
        }
        // 同一块模板配了几组贴图：找出这几组里会变的那几个纹理变量，拿它们的贴图名区分模型
        Map<String, Set<String>> varying = new LinkedHashMap<>();
        Map<String, List<Look>> byTemplate = new LinkedHashMap<>();
        distinct.values().forEach(l -> byTemplate.computeIfAbsent(l.template(), t -> new ArrayList<>()).add(l));
        byTemplate.forEach((template, group) -> {
            Set<String> keys = new LinkedHashSet<>();
            for (Look l : group) {
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
            StringBuilder path = new StringBuilder("block/").append(family).append('/').append(block).append('/').append(look.template());
            for (String k : varying.get(look.template())) {
                path.append('_').append(look.textures().get(k));
            }
            Identifier id = Identifier.of(HeavySeasMod.MOD_ID, path.toString());
            JsonObject model = new JsonObject();
            model.addProperty("parent", HeavySeasMod.MOD_ID + ":" + templateDir + look.template());
            JsonObject textures = new JsonObject();
            String first = null;
            for (Map.Entry<String, String> e : look.textures().entrySet()) {
                String ref = HeavySeasMod.MOD_ID + ":" + textureDir + e.getValue();
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

    private static String modelKey(Look look) {
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
