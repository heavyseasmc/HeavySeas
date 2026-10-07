package io.github.heavyseasmc.mod.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlWriter;
import io.github.heavyseasmc.mod.llm.LlmConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 起服时的两次一次性迁移（{@link SettingsMigration}）：旧的随机开关 → 替身的脑子；旧的 {@code llm.json} → 「大模型」一组。
 * 两件都要幂等，{@code llm.json} 改名不删、不覆盖，密钥不进日志、不进会同步的那份文件。
 */
final class SettingsMigrationTest {

    private static final String KEY = "sk-file-0123456789-not-real";
    private static final Function<String, String> NO_ENV = k -> null;

    private static final String OLD_TOML = """
            [stand_ins]
            \tautoplay = true
            \trandom = true
            \tfill_empty_seats = false
            """;

    // ---------------------------------------------------------------- 旧的随机开关

    @Test
    @DisplayName("旧文件写着 random = true：FCAP 读之前问得到，迁成 mind = random；FCAP 纠正之后旧键没了，再起服不再迁")
    void legacyRandomBecomesTheMindOnce(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("heavyseas-server.toml");
        Files.writeString(file, OLD_TOML, StandardCharsets.UTF_8);

        SettingsMigration.Legacy legacy = SettingsMigration.readLegacy(file);
        assertEquals(new SettingsMigration.Legacy(true, false), legacy);
        assertEquals(Optional.of("random"), SettingsMigration.mindFromLegacy(legacy));

        // FCAP 读文件那一步：按 spec 纠正 —— 旧键被删掉、新键补上默认值。所以上面那一次读必须赶在它前面
        CommentedConfig onDisk = new TomlParser().parse(Files.readString(file, StandardCharsets.UTF_8));
        ServerSettings settings = new ServerSettings(ServerSettingsTable.DEFAULT);
        settings.spec().correct(onDisk);
        assertFalse(onDisk.contains(List.of("stand_ins", "random")), "FCAP 没删旧键：那就不必赶在它前面读了，这条单测的前提变了");
        assertEquals("idle", onDisk.get(List.of("stand_ins", "mind")));
        Files.writeString(file, new TomlWriter().writeToString(onDisk), StandardCharsets.UTF_8);

        // 迁：经 FCAP 存成新键
        ServerSettingsTest.Loaded loaded = new ServerSettingsTest.Loaded();
        loaded.config.putAll(onDisk);
        settings.spec().acceptConfig(loaded);
        int savesBefore = loaded.saves;                 // 只数迁移这一下（装上配置那一步 FCAP 自己也可能存一次）
        try {
            Optional<ServerSettings.Outcome> outcome = SettingsMigration.migrateMind(legacy, settings);
            assertTrue(outcome.isPresent() && outcome.get().accepted(), String.valueOf(outcome));
            assertEquals("random", settings.snapshot().get(ServerSettingsTable.MIND));
            assertEquals(savesBefore + 1, loaded.saves, "迁一次该存一次盘");
        } finally {
            settings.spec().acceptConfig(null);
        }

        // 再起服：纠正过的文件里已经没有旧键 —— 什么都不迁（幂等）
        SettingsMigration.Legacy again = SettingsMigration.readLegacy(file);
        assertNull(again.random());
        assertTrue(again.mindWritten());
        assertEquals(Optional.empty(), SettingsMigration.mindFromLegacy(again));
    }

    @Test
    @DisplayName("不迁的几种：旧开关关着 · 文件里已经写了新键 · 没有文件 · 文件写坏了（不抛）")
    void legacyLeftAlone(@TempDir Path dir) throws Exception {
        Path off = dir.resolve("off.toml");
        Files.writeString(off, "[stand_ins]\nrandom = false\n", StandardCharsets.UTF_8);
        assertEquals(Optional.empty(), SettingsMigration.mindFromLegacy(SettingsMigration.readLegacy(off)));

        Path both = dir.resolve("both.toml");
        Files.writeString(both, "[stand_ins]\nrandom = true\nmind = \"smart\"\n", StandardCharsets.UTF_8);
        SettingsMigration.Legacy written = SettingsMigration.readLegacy(both);
        assertEquals(new SettingsMigration.Legacy(true, true), written, "正向对照：旧开关读到了");
        assertEquals(Optional.empty(), SettingsMigration.mindFromLegacy(written), "文件里写了新键就以新键为准");

        assertEquals(SettingsMigration.Legacy.NONE, SettingsMigration.readLegacy(dir.resolve("missing.toml")));
        Path broken = dir.resolve("broken.toml");
        Files.writeString(broken, "[stand_ins\nrandom = = true\n", StandardCharsets.UTF_8);
        assertEquals(SettingsMigration.Legacy.NONE, SettingsMigration.readLegacy(broken));
    }

    @Test
    @DisplayName("读哪一份：存档的 serverconfig/ 里有就是它（FCAP 的覆盖规则），否则是全局 config/ 下的")
    void readsTheFileFcapWillRead(@TempDir Path dir) throws Exception {
        Path config = Files.createDirectories(dir.resolve("config"));
        Path world = Files.createDirectories(dir.resolve("world").resolve("serverconfig"));
        String name = "heavyseas-server.toml";
        assertEquals(config.resolve(name), SettingsMigration.effectiveFile(config, world, name));
        Files.writeString(world.resolve(name), OLD_TOML, StandardCharsets.UTF_8);
        assertEquals(world.resolve(name), SettingsMigration.effectiveFile(config, world, name));
    }

    // ---------------------------------------------------------------- 旧的 llm.json

    private static Path llmJson(Path dir, String text) throws Exception {
        Path file = dir.resolve("llm.json");
        Files.writeString(file, text, StandardCharsets.UTF_8);
        return file;
    }

    private static final String FULL_JSON = """
            {
              "enabled": true,
              "baseUrl": "https://llm.example/v1/",
              "model": "file-model",
              "maxTokens": 128,
              "temperature": 0.3,
              "reasoningEffort": "low",
              "language": "en_us",
              "apiKey": "%s"
            }
            """.formatted(KEY);

    @Test
    @DisplayName("llm.json：只填还是默认值的那几项，菜单里改过的不动；密钥进密钥文件；文件改名（内容不变）；再起服什么都不做")
    void llmJsonFillsDefaultsOnlyAndIsRenamedOnce(@TempDir Path dir) throws Exception {
        Path file = llmJson(dir, FULL_JSON);
        byte[] original = Files.readAllBytes(file);
        try (TestSettings t = TestSettings.defaults()) {
            ServerSettings s = t.settings();
            assertTrue(s.save(true, Map.of(ServerSettingsTable.LLM_MODEL, "menu-model")).accepted(), "菜单里先改过模型名");

            SettingsMigration.LlmImport result = SettingsMigration.importLlmJson(file, s, NO_ENV);
            assertNotNull(result);
            assertNull(result.problem(), result.problem());
            assertEquals(List.of(ServerSettingsTable.LLM_ENABLED, ServerSettingsTable.LLM_BASE_URL,
                    ServerSettingsTable.LLM_REASONING_EFFORT, ServerSettingsTable.LLM_LANGUAGE,
                    ServerSettingsTable.LLM_MAX_TOKENS, ServerSettingsTable.LLM_TEMPERATURE), result.imported());
            assertTrue(result.skipped().stream().anyMatch(s2 -> s2.startsWith(ServerSettingsTable.LLM_MODEL)), result.skipped().toString());
            Map<String, String> now = s.snapshot();
            assertEquals("menu-model", now.get(ServerSettingsTable.LLM_MODEL), "菜单里改过的被 llm.json 盖掉了");
            assertEquals("https://llm.example/v1", now.get(ServerSettingsTable.LLM_BASE_URL), "地址末尾的 / 没去掉");
            assertEquals("true", now.get(ServerSettingsTable.LLM_ENABLED));
            assertEquals("128", now.get(ServerSettingsTable.LLM_MAX_TOKENS));
            assertEquals("0.3", now.get(ServerSettingsTable.LLM_TEMPERATURE));
            assertEquals("en_us", now.get(ServerSettingsTable.LLM_LANGUAGE));
            assertEquals(Optional.of(KEY), s.secret(ServerSettingsTable.LLM_API_KEY), "密钥没进密钥文件");
            assertTrue(result.keyNote().contains("迁进了"), result.keyNote());

            assertFalse(Files.exists(file), "原文件还在原地");
            assertEquals(dir.resolve("llm.json" + SettingsMigration.MIGRATED_SUFFIX), result.renamedTo());
            assertArrayEquals(original, Files.readAllBytes(result.renamedTo()), "改名之后内容变了");

            // ❗密钥只在密钥文件里：不在会同步的那份配置里、不在快照里
            assertFalse(t.dump().contains(KEY), "密钥进了 heavyseas-server.toml");
            assertTrue(t.dump().contains("en_us"), "正向对照：配置写成的字里确实有迁进去的值");
            assertFalse(now.containsValue(KEY));
            assertFalse(result.toString().contains(KEY), "迁移结果的 toString 带了密钥");

            // 再起服：文件已经改了名 —— 什么都不做，值也不动
            int saves = t.saves();
            assertNull(SettingsMigration.importLlmJson(file, s, NO_ENV));
            assertEquals(now, s.snapshot());
            assertEquals(saves, t.saves(), "第二次又存了盘");
        }
    }

    @Test
    @DisplayName("llm.json 里的密钥：设置菜单已经设了、或者环境变量里有，都不迁（原先环境变量就压过文件）")
    void keyIsNotImportedOverAStoredOrEnvironmentKey(@TempDir Path dir) throws Exception {
        try (TestSettings t = TestSettings.defaults()) {
            ServerSettings s = t.settings();
            SettingsMigration.LlmImport env = SettingsMigration.importLlmJson(llmJson(dir, FULL_JSON), s,
                    Map.of(LlmConfig.KEY_ENV, "env-key")::get);
            assertTrue(env.keyNote().contains(LlmConfig.KEY_ENV), env.keyNote());
            assertEquals(Optional.empty(), s.secret(ServerSettingsTable.LLM_API_KEY), "环境变量有值时 llm.json 的密钥还是迁进来了");
            assertFalse(env.keyNote().contains("env-key"));
        }
        Files.deleteIfExists(dir.resolve("llm.json" + SettingsMigration.MIGRATED_SUFFIX));
        try (TestSettings t = TestSettings.defaults()) {
            ServerSettings s = t.settings();
            assertTrue(s.setSecret(true, ServerSettingsTable.LLM_API_KEY, "menu-key").accepted());
            SettingsMigration.LlmImport stored = SettingsMigration.importLlmJson(llmJson(dir, FULL_JSON), s, NO_ENV);
            assertTrue(stored.keyNote().contains("已经设了"), stored.keyNote());
            assertEquals(Optional.of("menu-key"), s.secret(ServerSettingsTable.LLM_API_KEY), "设置菜单设的密钥被 llm.json 盖掉了");
        }
    }

    @Test
    @DisplayName("改名不覆盖：llm.json.migrated、.1 都被占了就改成 .2，那两份原样留着")
    void renameNeverOverwrites(@TempDir Path dir) throws Exception {
        Path taken = Files.writeString(dir.resolve("llm.json.migrated"), "first", StandardCharsets.UTF_8);
        Path taken1 = Files.writeString(dir.resolve("llm.json.migrated.1"), "second", StandardCharsets.UTF_8);
        try (TestSettings t = TestSettings.defaults()) {
            SettingsMigration.LlmImport result = SettingsMigration.importLlmJson(llmJson(dir, "{\"maxQueued\": 8}"),
                    t.settings(), NO_ENV);
            assertEquals(dir.resolve("llm.json.migrated.2"), result.renamedTo());
            assertEquals("{\"maxQueued\": 8}", Files.readString(result.renamedTo(), StandardCharsets.UTF_8));
            assertEquals("first", Files.readString(taken, StandardCharsets.UTF_8));
            assertEquals("second", Files.readString(taken1, StandardCharsets.UTF_8));
            assertEquals("8", t.settings().snapshot().get(ServerSettingsTable.LLM_MAX_QUEUED));
        }
    }

    @Test
    @DisplayName("整份读不了：什么都不迁、文件留在原地，说一句为什么")
    void unreadableFileStaysPut(@TempDir Path dir) throws Exception {
        Path file = llmJson(dir, "{ \"enabled\": true, \"apiKey\": \"" + KEY + "\"");
        try (TestSettings t = TestSettings.defaults()) {
            Map<String, String> before = t.settings().snapshot();
            SettingsMigration.LlmImport result = SettingsMigration.importLlmJson(file, t.settings(), NO_ENV);
            assertNotNull(result.problem());
            assertNull(result.renamedTo());
            assertTrue(Files.exists(file), "读不了的文件被挪走了");
            assertEquals(before, t.settings().snapshot());
            assertEquals(Optional.empty(), t.settings().secret(ServerSettingsTable.LLM_API_KEY));
            assertFalse(result.problem().contains(KEY), "理由里带了密钥");
        }
    }

    @Test
    @DisplayName("一项写错不拖累别的：认不出的键、越界的数记进「没填」，其余照填，文件照样改名")
    void badItemsAreSkipped(@TempDir Path dir) throws Exception {
        Path file = llmJson(dir, "{\"enable\": true, \"maxTokens\": 999999, \"model\": \"ok-model\"}");
        try (TestSettings t = TestSettings.defaults()) {
            SettingsMigration.LlmImport result = SettingsMigration.importLlmJson(file, t.settings(), NO_ENV);
            assertNull(result.problem());
            assertEquals(List.of(ServerSettingsTable.LLM_MODEL), result.imported());
            assertTrue(result.skipped().stream().anyMatch(s -> s.contains("enable")), result.skipped().toString());
            assertTrue(result.skipped().stream().anyMatch(s -> s.startsWith(ServerSettingsTable.LLM_MAX_TOKENS)),
                    result.skipped().toString());
            assertEquals("ok-model", t.settings().snapshot().get(ServerSettingsTable.LLM_MODEL));
            assertEquals(String.valueOf(LlmConfig.MAX_TOKENS.fallback()),
                    t.settings().snapshot().get(ServerSettingsTable.LLM_MAX_TOKENS));
            assertNotNull(result.renamedTo());
        }
    }

    @Test
    @DisplayName("❗迁移那几行日志里没有密钥（迁成了 · 读不了两条路）")
    void migrationLogNeverCarriesTheKey(@TempDir Path dir) throws Exception {
        try (SecretPathTest.Capture logs = new SecretPathTest.Capture(); TestSettings t = TestSettings.defaults()) {
            Path file = llmJson(dir, FULL_JSON);
            SettingsMigration.LlmImport ok = SettingsMigration.importLlmJson(file, t.settings(), NO_ENV);
            ok.log(LoggerFactory.getLogger("heavyseas"), file);
            Path broken = llmJson(dir, "{ \"apiKey\": \"" + KEY + "\", ");
            SettingsMigration.LlmImport bad = SettingsMigration.importLlmJson(broken, t.settings(), NO_ENV);
            bad.log(LoggerFactory.getLogger("heavyseas"), broken);
            List<String> lines = logs.lines();
            assertEquals(List.of(), lines.stream().filter(l -> l.contains(KEY)).toList(), "密钥进了日志");
            // 正向对照：两条都写了（收得到日志，不是什么都没写）
            assertTrue(lines.stream().anyMatch(l -> l.contains("迁进了") && l.contains("密钥：迁进了服务端的密钥文件")), lines.toString());
            assertTrue(lines.stream().anyMatch(l -> l.contains("没有迁进设置")), lines.toString());
        }
    }
}
