package io.github.heavyseasmc.mod.data;

import io.github.heavyseasmc.engine.weather.WeatherCard;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.state.GameComponent;
import io.github.heavyseasmc.mod.state.GameComponents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.registry.Registries;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 把场景数据（航程布局 · 雾表）送进模组：走服务端数据包的重载路径，与 {@link GameDataLoader} 同一条，排在它之后。
 *
 * <h2>为什么不并进 GameDataLoader</h2>
 * 那四份是配平，要求「自称同一变体」；布局是场景，官方地图之外还可能有任意多份（每张非官方地图一份），
 * 不该被那条判据管。两个加载器各读各的；雾表要按天候 id 逐个核对，所以声明在配平之后读。
 *
 * <h2>坏数据一律当场响</h2>
 * 任何一份读不出来就抛：开服时表现为启动失败，{@code /reload} 时表现为指令报错、旧数据原样留着。
 * 默认布局 {@link #DEFAULT} 必须在 —— 大厅敲铃与不带参数的 {@code /seas start} 都用它。
 */
public final class SceneDataLoader implements SimpleSynchronousResourceReloadListener {

    public static final Identifier DEFAULT = Identifier.of(HeavySeasMod.MOD_ID, "default");

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);
    private static final Identifier FABRIC_ID = Identifier.of(HeavySeasMod.MOD_ID, "scene_data");

    /** 最近一次整套读成功的场景数据。读失败时保持不动。 */
    private static volatile Loaded loaded;

    private record Loaded(Map<Identifier, VoyageLayout> layouts, Map<Identifier, FogTable> fogTables) {
    }

    public static void register() {
        ResourceManagerHelper.get(ResourceType.SERVER_DATA).registerReloadListener(new SceneDataLoader());
    }

    /** 取一份布局；没有就抛并列出有哪些 —— 不返回 null，也不退回默认布局。 */
    public static VoyageLayout require(Identifier id) {
        Map<Identifier, VoyageLayout> layouts = loaded().layouts();
        VoyageLayout layout = layouts.get(id);
        if (layout == null) {
            throw new IllegalStateException("没有布局 %s —— 已加载的是 %s".formatted(id, layouts.keySet()));
        }
        return layout;
    }

    public static VoyageLayout defaultLayout() {
        return require(DEFAULT);
    }

    public static Set<Identifier> ids() {
        return loaded().layouts().keySet();
    }

    /** 某份布局在某种天候那一天的雾表条目（ADR-0034 §5.1.4）。 */
    public static FogTable.Entry fogFor(Identifier layoutId, String weatherId) {
        VoyageLayout layout = require(layoutId);
        FogTable table = loaded().fogTables().get(layout.fog());
        if (table == null) {
            throw new IllegalStateException("布局 %s 指的雾表 %s 不在（加载期核过，不该走到这里）".formatted(layoutId, layout.fog()));
        }
        return table.entryFor(weatherId);
    }

    /**
     * 此刻「雾海在哪」：有对局的世界用那一局的布局，否则用默认布局。
     *
     * <p>对局外的几处（{@code /seas} 的世界解析 · 大厅 · 海鸥的 tick）此前都直接读写死的维度 id，现在都问这里。
     * 场景数据还没加载时返回 {@code null}（起服早期），调用方按「维度未加载」处理。
     */
    public static VoyageLayout activeLayout(MinecraftServer server) {
        Loaded current = loaded;
        if (current == null) {
            return null;
        }
        for (ServerWorld world : server.getWorlds()) {
            GameComponent component = GameComponents.of(world);
            if (component.session().isPresent() && component.layoutId().isPresent()) {
                VoyageLayout layout = current.layouts().get(component.layoutId().get());
                if (layout != null) {
                    return layout;
                }
            }
        }
        return current.layouts().get(DEFAULT);
    }

    private static Loaded loaded() {
        Loaded current = loaded;
        if (current == null) {
            throw new IllegalStateException("场景数据尚未加载成功 —— 服务端数据包里应当有 "
                    + Identifier.of(HeavySeasMod.MOD_ID, VoyageLayoutLoader.RESOURCE_DIR + "/default.json")
                    + " 与 " + Identifier.of(HeavySeasMod.MOD_ID, FogTable.RESOURCE_DIR + "/default.json")
                    + "；若刚才 /reload 报过错，先修数据包");
        }
        return current;
    }

    @Override
    public Identifier getFabricId() {
        return FABRIC_ID;
    }

    /** 雾表要按天候 id 逐个核对，所以配平先读。 */
    @Override
    public Collection<Identifier> getFabricDependencies() {
        return Set.of(GameDataLoader.FABRIC_ID);
    }

    @Override
    public void reload(ResourceManager manager) {
        Set<String> weatherIds = GameDataLoader.require().weather().stream()
                .map(WeatherCard::id).collect(Collectors.toUnmodifiableSet());

        Map<Identifier, FogTable> fogTables = new LinkedHashMap<>();
        for (Map.Entry<Identifier, Resource> entry : manager.findResources(FogTable.RESOURCE_DIR,
                id -> id.getPath().endsWith(".json")).entrySet()) {
            Identifier tableId = FogTable.tableIdOf(entry.getKey());
            try (Reader reader = entry.getValue().getReader()) {
                FogTable table = FogTable.parse(entry.getKey().toString(), reader, tableId, weatherIds);
                fogTables.put(table.id(), table);
            } catch (IOException e) {
                throw new UncheckedIOException("读不了雾表 " + entry.getKey(), e);
            }
        }

        Map<Identifier, VoyageLayout> layouts = new LinkedHashMap<>();
        for (Map.Entry<Identifier, Resource> entry : manager.findResources(VoyageLayoutLoader.RESOURCE_DIR,
                id -> id.getPath().endsWith(".json")).entrySet()) {
            Identifier layoutId = VoyageLayoutLoader.layoutIdOf(entry.getKey());
            try (Reader reader = entry.getValue().getReader()) {
                VoyageLayout layout = VoyageLayoutLoader.parse(entry.getKey().toString(), reader, layoutId);
                if (!fogTables.containsKey(layout.fog())) {
                    throw new IllegalStateException("%s 的 fog：雾表 %s 不存在（data/%s/fog/%s.json）—— 读到的雾表是 %s"
                            .formatted(entry.getKey(), layout.fog(), layout.fog().getNamespace(),
                                    layout.fog().getPath(), fogTables.keySet()));
                }
                for (int i = 0; i < layout.backdrops().size(); i++) {
                    Identifier item = layout.backdrops().get(i).item();
                    if (!Registries.ITEM.containsId(item)) {
                        throw new IllegalStateException("%s 的 backdrops[%d].item：物品 %s 不存在（布景只能挂本模组注册过的物品）"
                                .formatted(entry.getKey(), i, item));
                    }
                }
                layouts.put(layout.id(), layout);
                float away = Math.abs(MathHelper.wrapDegrees(layout.arrival().bearing() - layout.ridersFacing()));
                if (away > 90f) {
                    // 不是错：人坐着背对岸只是奇怪。说一声，让写布局的人知道自己写了什么。
                    LOGGER.warn("布局 {}：岸的方向（{}°）与乘客面朝的方向（{}°）相差 {}°，靠岸时全船背对着岸",
                            layout.id(), layout.arrival().bearing(), layout.ridersFacing(), away);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("读不了航程布局 " + entry.getKey(), e);
            }
        }
        if (!layouts.containsKey(DEFAULT)) {
            throw new IllegalStateException("服务端数据包里没有默认布局 %s（%s）—— 模组自带的那份也没读到，说明 jar 没打全；读到的是 %s"
                    .formatted(DEFAULT, Identifier.of(HeavySeasMod.MOD_ID, VoyageLayoutLoader.RESOURCE_DIR + "/default.json"),
                            layouts.keySet()));
        }
        loaded = new Loaded(Map.copyOf(layouts), Map.copyOf(fogTables));
        VoyageLayout main = layouts.get(DEFAULT);
        // 与语言无关的两行：验收脚本靠它们判「布局与雾表真的读到了」。
        LOGGER.info("航程布局已加载：{} 份 · 默认 {} → 维度 {} · 船头 {} · 朝向 {} · 间距 {} · 船体 {} · 雾表 {}",
                layouts.size(), main.id(), main.dimension(), main.boat().bow(), main.boat().yaw(),
                main.boat().seatSpacing(), main.hull().map(h -> h.structure().toString()).orElse("无"), main.fog());
        LOGGER.info("雾表已加载：{} 份 · 覆盖天候 {} 种", fogTables.size(), weatherIds.size());
    }
}
