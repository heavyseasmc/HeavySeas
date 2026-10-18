package io.github.heavyseasmc.mod.llm;

import io.github.heavyseasmc.mod.config.ServerSettings;
import io.github.heavyseasmc.mod.config.ServerSettingsTable;
import io.github.heavyseasmc.mod.config.TestSettings;
import io.github.heavyseasmc.mod.llm.FakeChatServer.Reply;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审查 2026-10-07 L1：密钥「只写不读」，但原先没和接口地址绑在一起 —— 谁能改 {@code llm.base_url}，谁就能把地址改到自己的服务器，
 * 服务端把密钥放进 {@code Authorization} 头送过去。这里用两个只绑回环的假服务端（端口不同 = 地址不同）逐条核：
 * 请求头里有没有密钥，看的是<b>假服务端自己收到的请求头</b>（判据不靠被测的一侧报数）。
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
final class LlmKeyBindingTest {

    private static final String MENU_KEY = "sk-menu-bound-0123456789-not-real";
    private static final String ENV_KEY = "sk-env-bound-9876543210-not-real";

    private static void enable(TestSettings t, String baseUrl) {
        Map<String, String> changes = new LinkedHashMap<>();
        changes.put(ServerSettingsTable.LLM_ENABLED, "true");
        changes.put(ServerSettingsTable.LLM_BASE_URL, baseUrl);
        changes.put(ServerSettingsTable.LLM_MODEL, "fake-model");
        assertTrue(t.settings().save(true, changes).accepted());
    }

    /** 按此刻的服务端设置起一个接入层、问一次、关掉。 */
    private static ChoiceOutcome ask(Function<String, String> env) throws Exception {
        LlmConfig.Loaded loaded = ServerSettings.llmConfig(env);
        assertTrue(loaded.config().enabled(), "前提：大模型开着 —— " + loaded.problem());
        try (LlmService service = LlmService.start(loaded.config())) {
            return service.choose(LlmServiceTest.request(3, 10_000)).get(30, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("❗菜单里设的密钥只发往设它时的那个地址：地址改到别处，请求头里就没有它")
    void storedKeyStaysWithTheEndpointItWasSetFor() throws Exception {
        try (FakeChatServer owner = new FakeChatServer((n, body) -> Reply.answer("1"));
             FakeChatServer elsewhere = new FakeChatServer((n, body) -> Reply.answer("1"));
             TestSettings t = TestSettings.defaults(); AutoCloseable use = ServerSettings.useForTests(t.settings())) {
            enable(t, owner.baseUrl());
            assertTrue(t.settings().setSecret(true, ServerSettingsTable.LLM_API_KEY, MENU_KEY, owner.baseUrl()).accepted());
            ask(k -> null);
            assertEquals("Bearer " + MENU_KEY, owner.received().getFirst().authorization(), "正向对照：设它时的那个地址收得到密钥");

            // 能改设置的另一个人把地址改到了自己的服务器
            assertTrue(t.settings().save(true, Map.of(ServerSettingsTable.LLM_BASE_URL, elsewhere.baseUrl())).accepted());
            ask(k -> null);
            assertEquals(1, elsewhere.requests(), "前提：请求确实发到了新地址");
            assertNull(elsewhere.received().getFirst().authorization(), "改了地址，密钥跟着发到了新地址");
            LlmConfig.Loaded withheld = ServerSettings.llmConfig(k -> null);
            assertEquals(LlmConfig.KEY_SOURCE_NONE, withheld.keySource());
            assertTrue(withheld.keyWithheld() != null && withheld.keyWithheld().contains("重新设"), "没带的那一句要说出来：" + withheld);
            assertTrue(!withheld.keyWithheld().contains(MENU_KEY), "那一句里带了密钥");

            // 地址改回去：照旧带（比的是协议 + 主机 + 端口，末尾多一个 / 还是同一个地方）
            assertTrue(t.settings().save(true, Map.of(ServerSettingsTable.LLM_BASE_URL, owner.baseUrl() + "/")).accepted());
            ask(k -> null);
            assertEquals("Bearer " + MENU_KEY, owner.received().getLast().authorization());
        }
    }

    @Test
    @DisplayName("旧版本存的密钥（没记下地址）：不带，说清要在菜单里重新设一次")
    void unboundLegacyKeyIsWithheld() throws Exception {
        try (FakeChatServer owner = new FakeChatServer((n, body) -> Reply.answer("1"))) {
            io.github.heavyseasmc.mod.config.SecretStore store = io.github.heavyseasmc.mod.config.SecretStore.inMemory();
            store.put(ServerSettingsTable.LLM_API_KEY, MENU_KEY);                  // 改之前的写法：只有密钥，没有 .origin
            try (TestSettings t = TestSettings.of(ServerSettingsTable.DEFAULT, store);
                 AutoCloseable use = ServerSettings.useForTests(t.settings())) {
                enable(t, owner.baseUrl());
                ask(k -> null);
                assertNull(owner.received().getFirst().authorization(), "没记下地址的密钥照样带了");
                assertTrue(ServerSettings.llmConfig(k -> null).keyWithheld().contains("旧版本"));
            }
        }
    }

    @Test
    @DisplayName("❗环境变量里的密钥：服主没另用环境变量写明发往哪个地址，就不带")
    void environmentKeyNeedsAnAllowedEndpoint() throws Exception {
        try (FakeChatServer elsewhere = new FakeChatServer((n, body) -> Reply.answer("1"));
             TestSettings t = TestSettings.defaults(); AutoCloseable use = ServerSettings.useForTests(t.settings())) {
            enable(t, elsewhere.baseUrl());
            ask(Map.of(LlmConfig.KEY_ENV, ENV_KEY)::get);
            assertEquals(1, elsewhere.requests(), "前提：请求确实发出去了");
            assertNull(elsewhere.received().getFirst().authorization(), "环境变量里的密钥发到了配置里随便写的地址");

            // 写明了别的地址：照样不带
            ask(Map.of(LlmConfig.KEY_ENV, ENV_KEY, LlmConfig.KEY_ORIGIN_ENV, "https://api.example.com")::get);
            assertNull(elsewhere.received().getLast().authorization(), "环境变量写明的是别处，密钥却发到了这里");

            // 正向对照：写明的就是这个地址（写到 /v1 也行）—— 带
            ask(Map.of(LlmConfig.KEY_ENV, ENV_KEY, LlmConfig.KEY_ORIGIN_ENV, elsewhere.baseUrl())::get);
            assertEquals("Bearer " + ENV_KEY, elsewhere.received().getLast().authorization());
            assertEquals(3, elsewhere.requests());
        }
    }

    @Test
    @DisplayName("地址：带用户名密码的写法菜单存不进去；只比协议 + 主机 + 端口（大小写、默认端口一样就是同一个）")
    void originComparesSchemeHostAndPort() {
        assertEquals("https://api.example.com:443", LlmConfig.origin("HTTPS://API.Example.com/v1/"));
        assertEquals(LlmConfig.origin("http://127.0.0.1/v1"), LlmConfig.origin("http://127.0.0.1:80"));
        assertTrue(!LlmConfig.origin("http://127.0.0.1:8317/v1").equals(LlmConfig.origin("http://127.0.0.1:8318/v1")), "端口不同是两个地方");
        assertTrue(!LlmConfig.origin("http://h/v1").equals(LlmConfig.origin("https://h/v1")), "协议不同是两个地方");
        assertNull(LlmConfig.origin("127.0.0.1:11434/v1"), "没写协议的不是地址");
        assertNull(LlmConfig.origin(""));
        assertNull(LlmConfig.origin(null));
        try (TestSettings t = TestSettings.defaults()) {
            assertTrue(!t.settings().save(true, Map.of(ServerSettingsTable.LLM_BASE_URL, "http://user:pass@127.0.0.1:8317/v1")).accepted(),
                    "地址里带 user:pass@ 却存进去了");
        }
    }
}
