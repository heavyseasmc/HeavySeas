package io.github.heavyseasmc.mod.llm;

import io.github.heavyseasmc.mod.config.ServerSettings;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;

/**
 * 大模型替身接入层挂进服务端的地方（ADR-0096 §3）：起服时按服务端设置（设置菜单的「大模型」一组，{@link ServerSettings#llmConfig}）
 * 起一个 {@link LlmService}，<b>设置存了就当场换一个</b>（{@link ServerSettings#onChange}），停服关掉它，外加开发指令
 * {@code /seasllm}（{@link LlmCommand}）。
 *
 * <p>密钥先取设置菜单存的（服务端自己的密钥文件），没有再看环境变量 {@value LlmConfig#KEY_ENV}；
 * {@link #keySource()} 只说取自哪里，密钥本身不出这一层。旧的 {@code config/heavyseas/llm.json} 起服时一次性迁进设置（{@code SettingsMigration}）。
 *
 * <p>模组入口只调一行 {@link #register()}。替身那一侧要问模型时取 {@link #service()} —— 它永远不是 {@code null}，
 * 没开时是一个当场回 {@code DISABLED} 的服务。
 */
public final class LlmHooks {

    private static final Logger LOGGER = LoggerFactory.getLogger("heavyseas");

    private static volatile LlmService service = LlmService.disabled("服务端还没起来");
    private static volatile String lastLoad = "还没读过";
    private static volatile String keySource = LlmConfig.KEY_SOURCE_NONE;
    /** 上一次读到的那一份（设置变了时比一比：一样就不换服务，免得把在路上的决定白白收掉）。 */
    private static LlmConfig.Loaded lastLoaded;

    private LlmHooks() {
    }

    public static void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> reload());
        // 设置菜单存了「大模型」一组、或者设了 / 清了密钥：当场换一个服务（值真的变了才换）
        ServerSettings.onChange(LlmHooks::reloadIfChanged);
        // 停服时还没收场的决定一律收成 SHUTDOWN；单人游戏同一个进程里再开一个世界，会在 SERVER_STARTED 重新读
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> shutdown());
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> LlmCommand.register(dispatcher));
    }

    /** 现在用的服务；没开时是一个当场回 {@code DISABLED} 的服务，永远不是 {@code null}。 */
    public static LlmService service() {
        return service;
    }

    /** 旧的配置文件在哪：{@code <游戏目录>/config/heavyseas/llm.json}（起服时迁进设置后改名成 {@code llm.json.migrated}）。 */
    public static Path legacyConfigFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("heavyseas").resolve("llm.json");
    }

    /** 上一次读设置是什么时候、读成了什么（status 用）。 */
    public static String lastLoad() {
        return lastLoad;
    }

    /** 密钥的来源（「设置菜单（服务端的密钥文件）」「环境变量 …」「无」）—— 密钥本身不出这一层。 */
    public static String keySource() {
        return keySource;
    }

    /**
     * 按此刻的设置重起一个服务；旧的那个关掉（还没收场的收成 {@code SHUTDOWN}，调用方走各自的退路）。
     *
     * @return 一句给人看的结果
     */
    public static synchronized String reload() {
        return apply(ServerSettings.llmConfig(System::getenv));
    }

    /** 设置或密钥变了：读出来的那一份与上一次不一样才换服务。 */
    static synchronized void reloadIfChanged() {
        reloadIfChanged(ServerSettings.llmConfig(System::getenv));
    }

    static synchronized boolean reloadIfChanged(LlmConfig.Loaded loaded) {
        if (loaded.equals(lastLoaded)) {
            return false;
        }
        apply(loaded);
        return true;
    }

    /** 单测用：按给的环境变量读（不碰真的环境）。 */
    static synchronized String reload(Function<String, String> env) {
        return apply(ServerSettings.llmConfig(env));
    }

    private static String apply(LlmConfig.Loaded loaded) {
        LlmService next = LlmService.start(loaded.config());
        LlmService old = service;
        service = next;
        keySource = loaded.keySource();
        lastLoaded = loaded;
        old.close();

        String result;
        if (loaded.broken()) {
            result = "大模型替身关着：「大模型」一组的设置合不起来（" + loaded.problem() + "）";
            LOGGER.error(result);
        } else if (next.enabled()) {
            LlmConfig c = next.config();
            result = "大模型替身开着：模型 " + c.model() + " · 地址 " + c.hostLabel() + " · 密钥 "
                    + (c.hasKey() ? "有（来自" + loaded.keySource() + "）" : "无") + " · 语言 " + c.language();
            LOGGER.info(result);
        } else if (loaded.config().enabled()) {
            result = "大模型替身关着：" + next.disabledWhy();     // 设置开着，规则摘录拼不出来（LlmService 已记 ERROR）
        } else {
            result = loaded.note();
            LOGGER.debug(result);                       // 没开 = 从没打算开：每个装了模组的服务端都会走这里，不刷屏
        }
        lastLoad = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")) + " · " + result;
        return result;
    }

    static synchronized void shutdown() {
        LlmService old = service;
        service = LlmService.disabled("服务端停了");
        lastLoaded = null;
        old.close();
    }
}
