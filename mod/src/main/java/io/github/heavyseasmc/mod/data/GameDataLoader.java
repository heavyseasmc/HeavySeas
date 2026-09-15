package io.github.heavyseasmc.mod.data;

import io.github.heavyseasmc.engine.data.DataConsistency;
import io.github.heavyseasmc.engine.data.DataDocument;
import io.github.heavyseasmc.engine.data.NavigationLoader;
import io.github.heavyseasmc.engine.data.ProvisionLoader;
import io.github.heavyseasmc.engine.data.RosterData;
import io.github.heavyseasmc.engine.data.RosterLoader;
import io.github.heavyseasmc.engine.model.Provisions;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.mod.HeavySeasMod;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * 把数值数据送进模组：走 Minecraft 的资源管理器，当作服务端数据包读。
 *
 * <h2>为什么走资源管理器，而不是读 jar 里的文件</h2>
 * 这三份数据是<b>配平</b>，注定要改。走资源管理器意味着数据包能覆盖它们：
 * 调平衡不必重新打包模组，服主也能自己改。代价是「数据可能是别人写的」，
 * 所以引擎那四层校验从可有可无变成了必需 —— 它们现在面对的是玩家写的文件。
 *
 * <h2>坏数据一律当场响，绝不退回内置那份</h2>
 * 静默退回会让「数据包写错了」与「没装数据包」表现完全相同，而这两件事要做的处置正相反
 * （{@code DataDir} 那条教训的同一形态）。所以任何一份读不出来就抛：
 * 开服时表现为启动失败，{@code /reload} 时表现为指令报错、<b>旧数据原样留着</b>。
 *
 * <h2>三份一起换</h2>
 * 三份全读成功才 {@link #current} 换新。半套配平（角色换了、航海牌没换）单看每一份都合法，
 * 四层校验一层都不会红 —— 只有比对三份自称的 id 才发现。那条比对在 {@link #reload} 里。
 */
public final class GameDataLoader implements SimpleSynchronousResourceReloadListener {

    /** 变体名。M1 只有 {@code default} 一套；选变体是以后的事，现在写死好过做一个没人用的开关。 */
    public static final String VARIANT = "default";

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    /** 本监听器自己的标识，与数据的 id 无关。 */
    private static final Identifier FABRIC_ID = Identifier.of(HeavySeasMod.MOD_ID, "game_data");

    /** 最近一次<b>整套</b>读成功的数据。读失败时保持不动。 */
    private static volatile GameData current;

    /**
     * 取当前配平。
     *
     * @throws IllegalStateException 还没有任何一套数据读成功。<b>不返回 null，也不返回空壳</b>：
     *                               空壳会让「数据没加载」一路漂到某个算不出分的地方才炸
     */
    public static GameData require() {
        GameData data = current;
        if (data == null) {
            throw new IllegalStateException(
                    "数值数据尚未加载成功 —— 服务端数据包里应当有 " + resource("roster")
                            + " 等三份；若刚才 /reload 报过错，先修数据包");
        }
        return data;
    }

    /** 注册到服务端数据包的重载流程上。 */
    public static void register() {
        ResourceManagerHelper.get(ResourceType.SERVER_DATA)
                .registerReloadListener(new GameDataLoader());
    }

    @Override
    public Identifier getFabricId() {
        return FABRIC_ID;
    }

    @Override
    public void reload(ResourceManager manager) {
        DataDocument<RosterData> roster =
                read(manager, "roster", RosterLoader::loadDocument);
        // 读整份目录（18 种的类别、张数与效果）。整副牌由目录展开，id 全集也由它派生 ——
        // 只有一处解析，也就只有一份真相源。
        DataDocument<Provisions> provisions =
                read(manager, "provisions", ProvisionLoader::loadCatalog);
        // 航海牌点名角色与物资，必须在那两份之后读 —— 顺序是规则决定的，不是随手排的。
        DataDocument<List<NavigationCard>> navigation =
                read(manager, "navigation", (source, reader) -> NavigationLoader.loadDocument(
                        source, reader, roster.value().ids(), provisions.value().ids()));

        requireSameVariant(roster, provisions, navigation);
        // ❗跨文件核对：角色表与物资表互相点名的地方（诱饵穿透水手的免伤、医生的医疗箱不弃、
        //   陪酒女蹭什么、哪个角色让哪种财宝翻倍）两边说的必须是同一件事。
        //   单份校验永远发现不了 —— 两份文件各自都合法。
        DataConsistency.require(resource("provisions").toString(), roster.value(), provisions.value());

        current = new GameData(roster.id(), roster.value(), provisions.value(), navigation.value());
        LOGGER.info("数值数据已加载：变体 {} · 角色 {} 个 · 物资 {} 种 {} 张 · 航海牌 {} 张",
                roster.id(), roster.value().characters().size(),
                provisions.value().ids().size(), provisions.value().total(),
                navigation.value().size());
    }

    /**
     * 三份必须自称同一个变体。
     *
     * <p>这条检查的判据与内容正交：它不看数值对不对，只看三份是不是一套。数据包只覆盖了
     * 其中一份时，那一份自己完全合法，另外两份也完全合法 —— 单份校验永远发现不了。
     */
    private static void requireSameVariant(DataDocument<?>... documents) {
        String first = documents[0].id();
        for (DataDocument<?> document : documents) {
            if (!first.equals(document.id())) {
                throw new IllegalStateException(
                        "三份数值数据不是同一套配平：读到了 %s 与 %s。数据包大概只覆盖了其中一部分 —— "
                                .formatted(first, document.id())
                                + "它们互相点名，混用会让点名落空");
            }
        }
    }

    private static <T> DataDocument<T> read(
            ResourceManager manager, String kind, BiFunction<String, Reader, DataDocument<T>> parse) {
        Identifier id = resource(kind);
        Resource resource = manager.getResource(id).orElseThrow(() -> new IllegalStateException(
                "服务端数据包里没有 " + id + " —— 模组自带的那份也没读到，说明 jar 没打全"));
        try (Reader reader = resource.getReader()) {
            return parse.apply(id.toString(), reader);
        } catch (IOException e) {
            // 读不出来与读出来是坏数据要分开报：前者是打包/数据包的问题，后者是内容的问题。
            throw new UncheckedIOException("读不了数值数据 " + id, e);
        }
    }

    private static Identifier resource(String kind) {
        return Identifier.of(HeavySeasMod.MOD_ID, kind + "/" + VARIANT + ".json");
    }
}
