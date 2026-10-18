package io.github.heavyseasmc.mod.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import io.github.heavyseasmc.mod.game.GameTiming;
import net.neoforged.fml.config.IConfigSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务端设置（ADR-0099 D2 · D6 · D8）：默认值与改之前写死的一样 · 整批改要么全成要么一个不动 · 没权限的改不了 · 写盘失败退回。
 *
 * <p>用的是真的 Forge Config API Port {@code ModConfigSpec}（由设置表展开），只是不向 FCAP 登记 ——
 * 登记要 Fabric Loader 起着；这里给 spec 塞一份内存里的配置（{@link Loaded}），{@code set} / {@code save} 走的都是 FCAP 自己的代码。
 */
final class ServerSettingsTest {

    /** 一份内存里的「已加载的配置」：记着存了几次盘，可以让存盘失败。 */
    static final class Loaded implements IConfigSpec.ILoadedConfig {
        final CommentedConfig config = CommentedConfig.inMemory();
        int saves;
        RuntimeException failOnSave;

        @Override
        public CommentedConfig config() {
            return config;
        }

        @Override
        public void save() {
            if (failOnSave != null) {
                throw failOnSave;
            }
            saves++;
        }
    }

    /** 按表展开 spec、补齐默认值、装上内存配置 —— 与 FCAP 起服时新建文件那一步同一个顺序（correct 之后 accept）。 */
    static ServerSettings loaded(ServerSettingsTable table, Loaded loaded) {
        ServerSettings settings = new ServerSettings(table);
        settings.spec().correct(loaded.config);
        settings.spec().acceptConfig(loaded);
        return settings;
    }

    private ServerSettings settings;

    @AfterEach
    void unload() {
        if (settings != null) {
            settings.spec().acceptConfig(null);
        }
    }

    @Test
    @DisplayName("默认值就是改之前写死的那些数（逐个按字面量钉住，不靠同源的常量互证）")
    void defaultsAreTheOldHardcodedValues() {
        GameTiming fromTable = ServerSettingsTable.DEFAULT.timing(SettingDef::fallback);
        assertEquals(GameTiming.DEFAULTS, fromTable, "设置表的默认值与 GameTiming.DEFAULTS 分家了");
        // 字面量：表与 DEFAULTS 都取自同一批常量，只比两者等于没比（判据要与结论正交）
        assertEquals(new GameTiming(60_000, 20_000, 20_000, 20_000, 8_000, 20_000, 6_000, 20_000, 20_000, 20_000,
                20_000, 20_000, 2_000, 20_000, 3_000, 45_000, true), fromTable);
        Map<String, Object> flags = new LinkedHashMap<>();
        for (SettingDef def : ServerSettingsTable.DEFAULT.all()) {
            if (def.kind() == SettingDef.Kind.FLAG) {
                flags.put(def.key(), def.fallback());
            }
        }
        assertEquals(Map.of(ServerSettingsTable.AUTOPLAY, true, ServerSettingsTable.FILL_SEATS, false,
                ServerSettingsTable.UNTIMED_DEMO, true, ServerSettingsTable.SMART_SEARCH, true,
                ServerSettingsTable.LLM_ENABLED, false, ServerSettingsTable.LLM_DEBUG_LOG, false), flags);
        assertEquals("idle", ServerSettingsTable.DEFAULT.def(ServerSettingsTable.MIND).orElseThrow().fallback(),
                "替身起服默认不再是「什么也不做」：回归脚本都按它写");
    }

    @Test
    @DisplayName("FCAP 的 spec 由表展开：补齐之后读回来的就是默认值，快照里每一项都在")
    void specFilledWithDefaultsReadsBackTheDefaults() {
        Loaded file = new Loaded();
        settings = loaded(ServerSettingsTable.DEFAULT, file);
        assertTrue(settings.loaded());
        assertEquals(GameTiming.DEFAULTS, settings.timing());
        Map<String, String> snapshot = settings.snapshot();
        assertEquals(ServerSettingsTable.DEFAULT.synced().size(), snapshot.size());
        assertEquals("60", snapshot.get(ServerSettingsTable.ACTION));
        assertEquals("idle", snapshot.get(ServerSettingsTable.MIND));
        // 段落进了 toml：windows.action_seconds 是 [windows] 段里的 action_seconds
        assertEquals(60, (Integer) file.config.get(List.of("windows", "action_seconds")));
        assertEquals(0, file.saves, "只读不该存盘");
    }

    @Test
    @DisplayName("整批：一项不合法整批拒，一个值都不动、不存盘；全合法才改、只存一次盘")
    void batchIsAllOrNothing() {
        Loaded file = new Loaded();
        settings = loaded(ServerSettingsTable.DEFAULT, file);
        Map<String, String> bad = new LinkedHashMap<>();
        bad.put(ServerSettingsTable.ACTION, "30");
        bad.put(ServerSettingsTable.ROW, "abc");
        ServerSettings.Outcome rejected = settings.save(true, bad);
        assertFalse(rejected.accepted());
        assertTrue(rejected.rejection().contains(ServerSettingsTable.ROW), "理由要点名坏的那一项：" + rejected.rejection());
        assertEquals(60_000, settings.timing().actionMs(), "被拒的一批里合法的那一项也不许改");
        assertEquals(0, file.saves);

        Map<String, String> good = new LinkedHashMap<>();
        good.put(ServerSettingsTable.ACTION, "30");
        good.put(ServerSettingsTable.ROW, "25");
        ServerSettings.Outcome accepted = settings.save(true, good);
        assertTrue(accepted.accepted(), String.valueOf(accepted.rejection()));
        assertEquals(List.of(ServerSettingsTable.ACTION, ServerSettingsTable.ROW), accepted.keys());
        assertEquals(30_000, settings.timing().actionMs());
        assertEquals(25_000, settings.timing().rowMs());
        assertEquals(1, file.saves, "一批只存一次盘");
    }

    @Test
    @DisplayName("没权限：拒，一个值都不动")
    void withoutPermissionNothingChanges() {
        Loaded file = new Loaded();
        settings = loaded(ServerSettingsTable.DEFAULT, file);
        ServerSettings.Outcome outcome = settings.save(false, Map.of(ServerSettingsTable.ACTION, "30"));
        assertFalse(outcome.accepted());
        assertTrue(outcome.rejection().contains("没有权限"), outcome.rejection());
        assertEquals(60_000, settings.timing().actionMs());
        assertEquals(0, file.saves);
    }

    @Test
    @DisplayName("越界 · 不认识的键 · 空的一批 · 开关写成 yes：一律拒")
    void malformedBatchesAreRejected() {
        Loaded file = new Loaded();
        settings = loaded(ServerSettingsTable.DEFAULT, file);
        assertFalse(settings.save(true, Map.of(ServerSettingsTable.ACTION, "9")).accepted(), "下限 10 秒");
        assertFalse(settings.save(true, Map.of(ServerSettingsTable.ACTION, "601")).accepted(), "上限 600 秒");
        assertFalse(settings.save(true, Map.of("windows.nope", "30")).accepted());
        assertFalse(settings.save(true, Map.of()).accepted());
        assertFalse(settings.save(true, Map.of(ServerSettingsTable.FILL_SEATS, "yes")).accepted());
        assertFalse(settings.save(true, Map.of(ServerSettingsTable.MIND, "clever")).accepted(), "不认识的脑子");
        assertEquals(GameTiming.DEFAULTS, settings.timing());
        assertEquals(0, file.saves);
    }

    @Test
    @DisplayName("❗「大模型」一组：每一项单看都合法、合起来大模型那一层不收的一批，整批拒并说理由（不是存下去、再静默关掉大模型）")
    void llmBatchMustMakeAWholeConfig() {
        // 审查 2026-10-07 R8：菜单原先只逐项核对，存了显示「已保存」，然后 LlmConfig 不收、整个大模型层关掉，只在服务端日志留一行
        Loaded file = new Loaded();
        settings = loaded(ServerSettingsTable.DEFAULT, file);
        Map<String, String> before = settings.snapshot();

        ServerSettings.Outcome effort = settings.save(true, Map.of(ServerSettingsTable.LLM_REASONING_EFFORT, "High"));
        assertFalse(effort.accepted(), "reasoning_effort 写成「High」（LlmConfig 只认小写字母）却存进去了");
        assertTrue(effort.rejection().contains("reasoningEffort"), effort.rejection());

        Map<String, String> backoff = new LinkedHashMap<>();
        backoff.put(ServerSettingsTable.LLM_BACKOFF_BASE, "5000");
        backoff.put(ServerSettingsTable.LLM_BACKOFF_MAX, "3000");
        ServerSettings.Outcome b = settings.save(true, backoff);
        assertFalse(b.accepted(), "退避底数大于上限却存进去了");
        assertTrue(b.rejection().contains("backoffMaxMs"), b.rejection());

        Map<String, String> noScheme = new LinkedHashMap<>();
        noScheme.put(ServerSettingsTable.LLM_ENABLED, "true");
        noScheme.put(ServerSettingsTable.LLM_BASE_URL, "127.0.0.1:11434/v1");
        noScheme.put(ServerSettingsTable.LLM_MODEL, "m");
        assertFalse(settings.save(true, noScheme).accepted(), "地址没写 http:// 却存进去了");

        assertEquals(before, settings.snapshot(), "被拒的几批动了值");
        assertEquals(0, file.saves);

        // 正向对照：合得起来的一批照常存
        Map<String, String> good = new LinkedHashMap<>(noScheme);
        good.put(ServerSettingsTable.LLM_BASE_URL, "http://127.0.0.1:11434/v1");
        good.put(ServerSettingsTable.LLM_REASONING_EFFORT, "high");
        ServerSettings.Outcome ok = settings.save(true, good);
        assertTrue(ok.accepted(), String.valueOf(ok.rejection()));
        // 不碰「大模型」一组的批不受它牵连
        assertTrue(settings.save(true, Map.of(ServerSettingsTable.ACTION, "30")).accepted());
    }

    @Test
    @DisplayName("写盘失败：内存里的值退回原样，不留「改了一半」")
    void failedWriteRollsBack() {
        Loaded file = new Loaded();
        settings = loaded(ServerSettingsTable.DEFAULT, file);
        file.failOnSave = new IllegalStateException("磁盘满了（测试）");
        ServerSettings.Outcome outcome = settings.save(true, Map.of(ServerSettingsTable.ACTION, "30"));
        assertFalse(outcome.accepted());
        assertEquals(60_000, settings.timing().actionMs());
    }

    @Test
    @DisplayName("开关与演示局：存进去的值读得回来，进开局快照")
    void flagsRoundTripIntoTheTiming() {
        Loaded file = new Loaded();
        settings = loaded(ServerSettingsTable.DEFAULT, file);
        Map<String, String> changes = new LinkedHashMap<>();
        changes.put(ServerSettingsTable.UNTIMED_DEMO, "false");
        changes.put(ServerSettingsTable.FILL_SEATS, "true");
        assertTrue(settings.save(true, changes).accepted());
        assertFalse(settings.timing().untimedDemo());
        assertTrue(ServerSettingsTable.DEFAULT.flag(settings::current, ServerSettingsTable.FILL_SEATS));
    }

    @Test
    @DisplayName("每一种类型都经 FCAP 存取：整数 · 小数 · 选项 · 文字补齐默认、改得进去、读回同一个值；坏值整批拒")
    void everyKindGoesThroughFcap() {
        ServerSettingsTable kinds = new ServerSettingsTable(List.of(
                SettingDef.integer(SettingsCategory.LLM, "llm.max_tokens", 64, 1, 32_768, SettingDef.Unit.NONE,
                        SettingDef.When.IMMEDIATE, "max tokens"),
                SettingDef.decimal(SettingsCategory.LLM, "llm.share", 0.55, 0.3, 1.0, 0.05, SettingDef.When.IMMEDIATE, "share"),
                SettingDef.choice(SettingsCategory.LLM, "llm.language", "zh_cn", List.of("zh_cn", "en_us"),
                        SettingDef.When.IMMEDIATE, "language"),
                SettingDef.text(SettingsCategory.LLM, "llm.model", "", 200, SettingDef.When.IMMEDIATE, "model"),
                SettingDef.secret(SettingsCategory.LLM, "llm.api_key", 128, SettingDef.When.IMMEDIATE, "key")));
        Loaded file = new Loaded();
        settings = loaded(kinds, file);
        assertEquals("64", settings.snapshot().get("llm.max_tokens"));
        assertEquals("0.55", settings.snapshot().get("llm.share"));
        assertEquals("zh_cn", settings.snapshot().get("llm.language"));
        assertEquals("", settings.snapshot().get("llm.model"));
        assertFalse(settings.snapshot().containsKey("llm.api_key"), "密钥不进快照");
        assertFalse(file.config.contains(List.of("llm", "api_key")), "密钥不进会同步的那份配置");

        Map<String, String> good = new LinkedHashMap<>();
        good.put("llm.max_tokens", "128");
        good.put("llm.share", "0.8");
        good.put("llm.language", "en_us");
        good.put("llm.model", "test-model");
        assertTrue(settings.save(true, good).accepted());
        assertEquals(good, Map.copyOf(settings.snapshot()));
        assertEquals(128, settings.current(kinds.def("llm.max_tokens").orElseThrow()));
        assertEquals(0.8, settings.current(kinds.def("llm.share").orElseThrow()));
        assertEquals("test-model", file.config.get(List.of("llm", "model")));

        Map<String, String> bad = new LinkedHashMap<>(good);
        bad.put("llm.language", "fr_fr");
        assertFalse(settings.save(true, bad).accepted(), "一项不合规矩整批拒");
        assertEquals(1, file.saves);
    }

    @Test
    void settingDefRefusesBlankCommentsAndOutOfRangeDefaults() {
        assertThrows(IllegalArgumentException.class, () -> SettingDef.seconds(SettingsCategory.WINDOWS, "windows.x", 20_000, 5, 10, "注释"));
        assertThrows(IllegalArgumentException.class, () -> SettingDef.seconds(SettingsCategory.WINDOWS, "windows.x", 20_000, 5, 300, " "));
        assertThrows(IllegalArgumentException.class, () -> SettingDef.seconds(SettingsCategory.WINDOWS, "windows.x", 2_500, 1, 30, "不是整秒"));
        assertNotNull(SettingDef.seconds(SettingsCategory.WINDOWS, "windows.x", 20_000, 5, 300, "注释"));
    }
}
