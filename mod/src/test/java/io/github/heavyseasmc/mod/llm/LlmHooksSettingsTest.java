package io.github.heavyseasmc.mod.llm;

import io.github.heavyseasmc.mod.config.ServerSettings;
import io.github.heavyseasmc.mod.config.ServerSettingsTable;
import io.github.heavyseasmc.mod.config.TestSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接入层按服务端设置起（cut 3b）：值取自设置菜单的「大模型」一组，密钥先取设置菜单存的、没有再看环境变量；
 * 设置变了才换服务；<b>密钥不进日志、不进 status、不进会同步的那份文件</b>，只说取自哪里。
 */
final class LlmHooksSettingsTest {

    private static final String MENU_KEY = "sk-menu-0123456789-not-real";
    private static final String ENV_KEY = "sk-env-9876543210-not-real";
    private static final String URL = "https://llm.example/v1";
    /** 环境变量里的密钥要配一个写明发往哪里的变量才带（审查 2026-10-07 L1）。 */
    private static final Function<String, String> ENV = Map.of(LlmConfig.KEY_ENV, ENV_KEY, LlmConfig.KEY_ORIGIN_ENV, URL)::get;

    @AfterEach
    void stop() {
        LlmHooks.shutdown();
    }

    private static void enable(TestSettings t) {
        Map<String, String> changes = new LinkedHashMap<>();
        changes.put(ServerSettingsTable.LLM_ENABLED, "true");
        changes.put(ServerSettingsTable.LLM_BASE_URL, URL);
        changes.put(ServerSettingsTable.LLM_MODEL, "test-model");
        assertTrue(t.settings().save(true, changes).accepted());
    }

    @Test
    @DisplayName("❗密钥：设置菜单的压过环境变量；日志 · status · 设置文件 · 快照里都只有「取自哪里」，没有值")
    void keySourceIsNamedAndTheKeyNeverShows(@TempDir Path dir) throws Exception {
        List<String> said = new ArrayList<>();
        try (LogCapture logs = new LogCapture(); TestSettings t = TestSettings.defaults();
             AutoCloseable use = ServerSettings.useForTests(t.settings())) {
            enable(t);
            assertTrue(t.settings().setSecret(true, ServerSettingsTable.LLM_API_KEY, MENU_KEY, URL).accepted());
            said.add(LlmHooks.reload(ENV));
            assertTrue(LlmHooks.service().enabled(), said.toString());
            assertEquals(MENU_KEY, LlmHooks.service().config().apiKey(), "正向对照：接入层拿到的是设置菜单存的那一份");
            assertEquals(LlmConfig.KEY_SOURCE_STORE, LlmHooks.keySource());
            List<String> status = LlmCommand.statusLines(LlmHooks.service(), dir.resolve("llm.json"));
            assertTrue(status.stream().anyMatch(l -> l.contains("密钥取自：" + LlmConfig.KEY_SOURCE_STORE)), status.toString());
            said.addAll(status);

            // 清掉菜单里的：退回环境变量
            assertTrue(t.settings().setSecret(true, ServerSettingsTable.LLM_API_KEY, "", URL).accepted());
            said.add(LlmHooks.reload(ENV));
            assertEquals(ENV_KEY, LlmHooks.service().config().apiKey());
            assertEquals(LlmConfig.KEY_SOURCE_ENV, LlmHooks.keySource());
            said.addAll(LlmCommand.statusLines(LlmHooks.service(), dir.resolve("llm.json")));

            // 都没有：说「无」
            said.add(LlmHooks.reload(k -> null));
            assertEquals(LlmConfig.KEY_SOURCE_NONE, LlmHooks.keySource());

            said.add(LlmHooks.lastLoad());
            said.add(LlmHooks.service().config().toString());
            said.add(t.dump());
            said.add(t.settings().snapshot().toString());
            said.addAll(logs.lines());
            for (String line : said) {
                assertFalse(line.contains(MENU_KEY) || line.contains(ENV_KEY), "密钥露出来了：" + line);
            }
            // 正向对照：确实收到了「开着」那一行（判据在扫写出去的日志）
            assertTrue(logs.lines().stream().anyMatch(l -> l.contains("大模型替身开着") && l.contains(LlmConfig.KEY_SOURCE_STORE)),
                    logs.lines().toString());
        }
    }

    @Test
    @DisplayName("设置变了才换服务：没变不换（在路上的决定不白收），改了模型名或密钥就换")
    void reloadsOnlyWhenTheSettingsChange() throws Exception {
        try (TestSettings t = TestSettings.defaults(); AutoCloseable use = ServerSettings.useForTests(t.settings())) {
            enable(t);
            LlmHooks.reload(ENV);
            LlmService first = LlmHooks.service();
            assertFalse(LlmHooks.reloadIfChanged(ServerSettings.llmConfig(ENV)), "没变也换了");
            assertSame(first, LlmHooks.service());

            assertTrue(t.settings().save(true, Map.of(ServerSettingsTable.LLM_MODEL, "other-model")).accepted());
            assertTrue(LlmHooks.reloadIfChanged(ServerSettings.llmConfig(ENV)), "改了模型名没换");
            assertNotSame(first, LlmHooks.service());
            assertEquals("other-model", LlmHooks.service().config().model());
            assertFalse(first.enabled(), "旧的那个没关");

            LlmService second = LlmHooks.service();
            assertTrue(t.settings().setSecret(true, ServerSettingsTable.LLM_API_KEY, MENU_KEY, URL).accepted());
            assertTrue(LlmHooks.reloadIfChanged(ServerSettings.llmConfig(ENV)), "换了密钥没换服务");
            assertNotSame(second, LlmHooks.service());
            assertEquals(LlmConfig.KEY_SOURCE_STORE, LlmHooks.keySource());

            // 合不起来（开着却没写模型）：菜单存不进去了（审查 2026-10-07 R8），手改文件才走得到 —— 关着，status 照样说得出密钥取自哪里
            // （用模型名而不用地址造：地址空了，密钥绑不上任何地方，那是「没带」，见 LlmKeyBindingTest）
            assertFalse(t.settings().save(true, Map.of(ServerSettingsTable.LLM_MODEL, "")).accepted(), "菜单该拒");
            t.setRaw(ServerSettingsTable.LLM_MODEL, "");
            String result = LlmHooks.reload(ENV);
            assertFalse(LlmHooks.service().enabled());
            assertTrue(result.contains("合不起来"), result);
            assertEquals(LlmConfig.KEY_SOURCE_STORE, LlmHooks.keySource());
            assertFalse(result.contains(MENU_KEY));
        }
    }
}
