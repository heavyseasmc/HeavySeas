package io.github.heavyseasmc.mod.datagen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.world.liner.LinerBlocks;
import io.github.heavyseasmc.mod.world.liner.LinerGlass;
import io.github.heavyseasmc.mod.world.liner.LinerDoors;
import io.github.heavyseasmc.mod.world.liner.LinerHull;
import io.github.heavyseasmc.mod.world.liner.LinerLooks;
import io.github.heavyseasmc.mod.world.liner.LinerProps;
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
 * 自制装饰方块的方块状态文件与带贴图的方块模型（「形状 × 材质」批量出，ADR-0053 §5 · ADR-0056 · ADR-0062 · ADR-0063）：
 * 救生艇一族（{@code block/skiff/}）与大邮轮一族（{@code block/liner/}，含灯与家具，模板与贴图在 {@code prop/} 下）。
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
                name, block, s -> skiff(block.look(s)), SkiffBlocks.displayState(block), false, null));
        LinerBlocks.all().forEach((name, block) -> family(generator, "liner", LinerLooks.TEMPLATE_DIR, LinerLooks.TEXTURE_DIR,
                name, block, s -> liner(block, s), block.getDefaultState(), true, null));
        // 灯与家具（ADR-0063）：模型正面朝北作画，物品栏默认角度就看得到正面；跨几格的件的物品是一块整件缩小的模板
        LinerProps.all().forEach((name, block) -> family(generator, "liner", LinerLooks.TEMPLATE_DIR, LinerLooks.TEXTURE_DIR,
                name, block, s -> liner(block, s), block.getDefaultState(), false,
                block.itemLook() == null ? null : liner(block.itemLook())));
        // 玻璃一批（ADR-0074）：模板正面朝南，与大邮轮那一族的墙面件相同；整扇窗（ADR-0091）的物品是整扇缩小
        LinerGlass.all().forEach((name, block) -> family(generator, "liner", LinerLooks.TEMPLATE_DIR, LinerLooks.TEXTURE_DIR,
                name, block, s -> liner((LinerLooks.Styled) block, s), block.getDefaultState(), true,
                ((LinerLooks.Styled) block).itemLook() == null ? null : liner(((LinerLooks.Styled) block).itemLook())));
        // 船壳板与舷窗（ADR-0069 §2）：舷窗正面朝南作画，物品栏里转半圈看正面；门（⑤）的物品是一整扇门缩小
        LinerHull.all().forEach((name, block) -> family(generator, "liner", LinerLooks.TEMPLATE_DIR, LinerLooks.TEXTURE_DIR,
                name, block, s -> liner((LinerLooks.Styled) block, s), block.getDefaultState(), true, null));
        LinerDoors.all().forEach((name, block) -> family(generator, "liner", LinerLooks.TEMPLATE_DIR, LinerLooks.TEXTURE_DIR,
                name, block, s -> liner(block, s), block.getDefaultState(), false, liner(block.itemLook())));
        // 大楼梯一族（ADR 草稿 stairs）：楼梯朝北作画（高的那一边在北），物品栏里转半圈看踏步；收口照檐口朝南；斜栏杆 · 起步灯另有缩小的物品模板
        io.github.heavyseasmc.mod.world.liner.LinerStairs.all().forEach((name, block) -> family(generator, "liner", LinerLooks.TEMPLATE_DIR,
                LinerLooks.TEXTURE_DIR, name, block, s -> liner((LinerLooks.Styled) block, s),
                io.github.heavyseasmc.mod.world.liner.LinerStairs.displayState(block),
                io.github.heavyseasmc.mod.world.liner.LinerStairs.frontInGui(block),
                io.github.heavyseasmc.mod.world.liner.LinerStairs.itemLook(block) == null ? null
                        : liner(io.github.heavyseasmc.mod.world.liner.LinerStairs.itemLook(block))));
    }

    @Override
    public void generateItemModels(ItemModelGenerator generator) {
        // 物品模型在上面逐块登记（指向摆出来那一版的方块模型），这里没有别的。
    }

    private static Look skiff(SkiffLooks.Look l) {
        return new Look(l.template(), l.textures(), l.x(), l.y());
    }

    private static Look liner(LinerLooks.Styled block, BlockState s) {
        return liner(block.look(s));
    }

    private static Look liner(LinerLooks.Look l) {
        return new Look(l.template(), l.textures(), 0, l.y());
    }

    /**
     * 一个方块：每个状态的方块状态项 + 用到的模型 + 物品模型。
     *
     * @param frontInGui 物品栏里要看见正面：模板一律正面朝南，而物品栏那一格默认看的是北面与东面 ——
     *                   大邮轮那一族的大框、墙板、挂墙的件从默认角度只看得见背面或一条边，所以物品模型在物品栏里转半圈
     * @param itemLook   物品另有一块模板（跨几格的灯与家具：整件缩小，显示参数在模板里）；{@code null} = 用 display 那一格的方块模型
     */
    private static void family(BlockStateModelGenerator generator, String family, String templateDir, String textureDir,
                               String name, Block block, Function<BlockState, Look> lookOf, BlockState display,
                               boolean frontInGui, Look itemLook) {
        Map<BlockState, Look> looks = new LinkedHashMap<>();
        for (BlockState state : block.getStateManager().getStates()) {
            looks.put(state, lookOf.apply(state));
        }
        // 多部件（格架上的常春藤，ADR-0080 §7）：底层按 baseProperties 分、每一层按它自己的属性分，写 multipart
        List<LinerLooks.Layer> layers = block instanceof LinerLooks.Styled st ? st.layers() : List.of();
        Map<String, Identifier> models = layers.isEmpty()
                ? uploadModels(generator, family, templateDir, textureDir, name, looks.values())
                : multipart(generator, family, templateDir, textureDir, name, block, looks, (LinerLooks.Styled) block, layers);
        // 物品（创造物品栏里那一格、手里拿着的样子）= 摆出来看得见的那一版的模型
        Identifier itemParent = models.get(modelKey(lookOf.apply(display)));
        if (itemLook != null) {
            JsonObject item = new JsonObject();
            item.addProperty("parent", HeavySeasMod.MOD_ID + ":" + templateDir + itemLook.template());
            item.add("textures", textures(textureDir, itemLook));
            generator.modelCollector.accept(ModelIds.getItemModelId(block.asItem()), () -> item);
        } else if (frontInGui && display.contains(Properties.HORIZONTAL_FACING)) {
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
        if (!layers.isEmpty()) {
            return;                                                // 方块状态文件在 multipart() 里写了
        }
        JsonObject variants = new JsonObject();
        looks.forEach((state, look) -> variants.add(variantKey(state), apply(models.get(modelKey(look)), look)));
        JsonObject root = new JsonObject();
        root.add("variants", variants);
        writeBlockState(generator, block, root);
    }

    /** 方块状态文件里的一项：模型 + x / y 旋转（0 不写）。 */
    private static JsonObject apply(Identifier model, Look look) {
        JsonObject variant = new JsonObject();
        variant.addProperty("model", model.toString());
        if (look.x() != 0) {
            variant.addProperty("x", look.x());
        }
        if (look.y() != 0) {
            variant.addProperty("y", look.y());
        }
        return variant;
    }

    /**
     * 多部件的方块状态文件：底层一项一项按 baseProperties 的取值组合写 when，每一层按它自己那几个属性写（这一层不画的组合不写）。
     * 同一组 when 出了两种模型 = 那一层其实还看别的属性 —— 抛（不然游戏里同一组 when 只认先写的那一项，另一种悄悄丢了）。
     */
    private static Map<String, Identifier> multipart(BlockStateModelGenerator generator, String family, String templateDir, String textureDir,
                                                     String name, Block block, Map<BlockState, Look> baseLooks, LinerLooks.Styled styled,
                                                     List<LinerLooks.Layer> layers) {
        List<Look> all = new ArrayList<>(baseLooks.values());
        List<Map<String, Look>> parts = new ArrayList<>();
        parts.add(group(name, baseLooks, styled.baseProperties()));
        for (LinerLooks.Layer layer : layers) {
            Map<BlockState, Look> looks = new LinkedHashMap<>();
            for (BlockState state : block.getStateManager().getStates()) {
                LinerLooks.Look l = layer.look().apply(state);
                if (l != null) {
                    looks.put(state, liner(l));
                }
            }
            all.addAll(looks.values());
            parts.add(group(name, looks, layer.properties()));
        }
        Map<String, Identifier> models = uploadModels(generator, family, templateDir, textureDir, name, all);
        // 底层对叠层那几个属性写全取值（「none|mid|tip」，恒真）：方块状态文件自己说清每个属性有哪几个值。
        //   不写的话「没有常春藤」(ivy = none) 不出现在任何一项的 when 里，按方块状态文件认合法状态的两道判据（checkLinerShip · liner_build）
        //   就把每一格没藤的格架判成「没有这一项」（ADR-0085 §4：接进整船时实跑撞上）
        Map<String, String> domain = new LinkedHashMap<>();
        for (Property<?> p : block.getStateManager().getProperties()) {
            if (!styled.baseProperties().contains(p)) {
                domain.put(p.getName(), String.join("|", p.getValues().stream().map(v -> valueName(p, v)).toList()));
            }
        }
        JsonArray entries = new JsonArray();
        boolean[] base = {true};
        for (Map<String, Look> part : parts) {
            boolean isBase = base[0];
            base[0] = false;
            part.forEach((when, look) -> {
                JsonObject entry = new JsonObject();
                JsonObject cond = new JsonObject();
                for (String kv : when.split(",")) {
                    String[] p = kv.split("=", 2);
                    cond.addProperty(p[0], p[1]);
                }
                if (isBase) {
                    domain.forEach(cond::addProperty);
                }
                entry.add("when", cond);
                entry.add("apply", apply(models.get(modelKey(look)), look));
                entries.add(entry);
            });
        }
        JsonObject root = new JsonObject();
        root.add("multipart", entries);
        writeBlockState(generator, block, root);
        return models;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String valueName(Property p, Object v) {
        return p.name((Comparable) v);
    }

    /** 按 properties 那几个属性的取值分组：键 = 「名=值」按名字排序、逗号隔开；同一组出了两种样子就抛。 */
    private static Map<String, Look> group(String name, Map<BlockState, Look> looks, List<Property<?>> properties) {
        if (properties.isEmpty()) {
            throw new IllegalStateException(name + "：有叠层，却没给底层看哪几个属性（baseProperties）");
        }
        List<Property<?>> sorted = new ArrayList<>(properties);
        sorted.sort(Comparator.comparing(Property::getName));
        Map<String, Look> out = new LinkedHashMap<>();
        looks.forEach((state, look) -> {
            StringBuilder key = new StringBuilder();
            for (Property<?> p : sorted) {
                if (!key.isEmpty()) {
                    key.append(',');
                }
                key.append(p.getName()).append('=').append(valueName(state, p));
            }
            Look prev = out.putIfAbsent(key.toString(), look);
            if (prev != null && !modelKey(prev).equals(modelKey(look)) || prev != null && prev.y() != look.y()) {
                throw new IllegalStateException(name + "：多部件的一层在 " + key + " 上出了两种样子（" + modelKey(prev) + " · " + modelKey(look)
                        + "）—— 这一层还看别的属性，没写进它的属性表");
            }
        });
        return out;
    }

    private static void writeBlockState(BlockStateModelGenerator generator, Block block, JsonObject root) {
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
            model.add("textures", textures(textureDir, look));
            generator.modelCollector.accept(id, () -> model);
            ids.put(key, id);
        });
        return ids;
    }

    /** 纹理变量 → 贴图；第一张兼作碎屑贴图。 */
    private static JsonObject textures(String textureDir, Look look) {
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
        return textures;
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
