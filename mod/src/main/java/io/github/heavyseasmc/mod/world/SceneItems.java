package io.github.heavyseasmc.mod.world;

import io.github.heavyseasmc.mod.HeavySeasMod;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/**
 * 只用来挂模型的物品（ADR-0034 §5.3 · §5.4）：布景与补给箱各是一个 item_display，它显示的是一个物品的模型。
 *
 * <p>1.21.1 没有「给物品换模型」的数据组件，自定义模型要么覆盖某个原有物品的模型文件（与别的模组打架），
 * 要么自己注册物品 —— 这里是后者。它们不进任何物品栏、没有配方；{@code /give} 拿得到，但拿到也只是一个模型。
 * 布局校验按 {@code Registries.ITEM} 核对 id，所以这里注册的名字就是布局里写的名字。
 */
public final class SceneItems {

    public static final Item COAST_BACKDROP = new Item(new Item.Settings());
    public static final Item LIGHTHOUSE_BACKDROP = new Item(new Item.Settings());
    public static final Item PIER_BACKDROP = new Item(new Item.Settings());
    public static final Item SUPPLY_CRATE = new Item(new Item.Settings());

    private SceneItems() {
    }

    public static void register() {
        register("coast_backdrop", COAST_BACKDROP);
        register("lighthouse_backdrop", LIGHTHOUSE_BACKDROP);
        register("pier_backdrop", PIER_BACKDROP);
        register("supply_crate", SUPPLY_CRATE);
    }

    private static void register(String name, Item item) {
        Registry.register(Registries.ITEM, Identifier.of(HeavySeasMod.MOD_ID, name), item);
    }
}
