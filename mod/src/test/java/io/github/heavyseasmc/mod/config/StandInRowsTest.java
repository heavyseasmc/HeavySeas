package io.github.heavyseasmc.mod.config;

import io.github.heavyseasmc.engine.seat.SeatPolicySettings;
import io.github.heavyseasmc.mod.llm.LlmConfig;
import io.github.heavyseasmc.mod.state.StandInMind;
import io.github.heavyseasmc.mod.ui.SettingsMenuState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleFunction;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 替身那几组设置（cut 3b：设置菜单管全部设置）：动脑替身的每一项来自引擎的 {@link SeatPolicySettings}（默认值与上下限都问它本身），
 * 大模型的每一项来自 {@link LlmConfig}；每一行都经 FCAP 存得进、读得回，经菜单的状态改得动、发得出去；读口按存着的值给。
 */
final class StandInRowsTest {

    private static final ServerSettingsTable TABLE = ServerSettingsTable.DEFAULT;
    private static final SeatPolicySettings D = SeatPolicySettings.DEFAULTS;
    /** 单测里用的地址：合 LlmConfig 的规矩、哪儿也连不上。 */
    private static final String URL = "https://llm.example/v1";

    private static SettingDef def(String key) {
        return TABLE.def(key).orElseThrow(() -> new AssertionError("表里没有 " + key));
    }

    private static boolean accepts(Runnable build) {
        try {
            build.run();
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 小数项的上下限正好是记录收与不收的分界：端点收，端点外最近的那个数不收。 */
    private static void exactDecimalBounds(String key, DoubleFunction<SeatPolicySettings> with) {
        SettingDef d = def(key);
        assertTrue(accepts(() -> with.apply(d.min())), key + "：下限 " + d.min() + " 记录不收");
        assertFalse(accepts(() -> with.apply(Math.nextDown(d.min()))), key + "：下限 " + d.min() + " 往下还收 —— 菜单拦早了");
        assertTrue(accepts(() -> with.apply(d.max())), key + "：上限 " + d.max() + " 记录不收");
        assertFalse(accepts(() -> with.apply(Math.nextUp(d.max()))), key + "：上限 " + d.max() + " 往上还收 —— 菜单拦早了");
    }

    private static void exactIntBounds(String key, IntFunction<SeatPolicySettings> with) {
        SettingDef d = def(key);
        int min = (int) d.min();
        int max = (int) d.max();
        assertTrue(accepts(() -> with.apply(min)), key + "：下限 " + min + " 记录不收");
        assertFalse(accepts(() -> with.apply(min - 1)), key + "：下限 " + min + " 往下还收");
        assertTrue(accepts(() -> with.apply(max)), key + "：上限 " + max + " 记录不收");
        assertFalse(accepts(() -> with.apply(max + 1)), key + "：上限 " + max + " 往上还收");
    }

    @Test
    @DisplayName("动脑替身：九项全在，默认值就是记录的默认，上下限正好是记录收与不收的分界（不抄数）")
    void smartRowsComeFromTheRecord() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put(ServerSettingsTable.SMART_SEARCH, D.search());
        defaults.put(ServerSettingsTable.SMART_MILLIS, D.millisPerDecision());
        defaults.put(ServerSettingsTable.SMART_ROLLOUTS, D.rollouts());
        defaults.put(ServerSettingsTable.SMART_WIDTH, D.width());
        defaults.put(ServerSettingsTable.SMART_HORIZON, D.horizonDays());
        defaults.put(ServerSettingsTable.SMART_TEMPERATURE, D.temperature());
        defaults.put(ServerSettingsTable.SMART_GRATITUDE, D.gratitude());
        defaults.put(ServerSettingsTable.SMART_RESENTMENT, D.resentment());
        defaults.put(ServerSettingsTable.SMART_MEMORY_DAYS, D.memoryDays());
        List<String> smartKeys = TABLE.all().stream().filter(d -> d.category() == SettingsCategory.SMART)
                .map(SettingDef::key).toList();
        assertEquals(List.copyOf(defaults.keySet()), smartKeys, "动脑替身那一组不是这九项（或次序变了）");
        defaults.forEach((key, value) -> assertEquals(value, def(key).fallback(), key));
        for (String key : smartKeys) {
            assertEquals(SettingDef.When.NEXT_GAME, def(key).when(), key + "：旋钮开局时取一份");
        }

        exactDecimalBounds(ServerSettingsTable.SMART_TEMPERATURE, D::withTemperature);
        exactDecimalBounds(ServerSettingsTable.SMART_GRATITUDE, v -> D.withMemory(v, D.resentment(), D.memoryDays()));
        exactDecimalBounds(ServerSettingsTable.SMART_RESENTMENT, v -> D.withMemory(D.gratitude(), v, D.memoryDays()));
        exactDecimalBounds(ServerSettingsTable.SMART_MEMORY_DAYS, v -> D.withMemory(D.gratitude(), D.resentment(), v));
        exactIntBounds(ServerSettingsTable.SMART_ROLLOUTS, v -> D.withBudget(v, D.millisPerDecision(), D.width(), D.horizonDays()));
        exactIntBounds(ServerSettingsTable.SMART_MILLIS, v -> D.withBudget(D.rollouts(), v, D.width(), D.horizonDays()));
        exactIntBounds(ServerSettingsTable.SMART_WIDTH, v -> D.withBudget(D.rollouts(), D.millisPerDecision(), v, D.horizonDays()));
        exactIntBounds(ServerSettingsTable.SMART_HORIZON, v -> D.withBudget(D.rollouts(), D.millisPerDecision(), D.width(), v));
    }

    /** {@code attemptTimeoutMs} → {@code attempt_timeout_ms}。 */
    private static String snake(String camel) {
        return camel.replaceAll("([A-Z])", "_$1").toLowerCase(Locale.ROOT);
    }

    @Test
    @DisplayName("大模型：LlmConfig 的每一项一行一项（密钥是只写的那一种），默认值与范围取自它；外加替身最多等多久")
    void llmRowsComeFromLlmConfig() {
        // 一项对一项：LlmConfig 加了字段而表里没加，这里红
        for (LlmConfig.Field f : LlmConfig.Field.values()) {
            String key = "llm." + snake(f.key());
            SettingDef d = TABLE.def(key).orElseThrow(() -> new AssertionError("LlmConfig 的 " + f.key() + " 在表里没有一行（" + key + "）"));
            assertEquals(f.defaultValue(), String.valueOf(d.fallback()), key + " 的默认值与 LlmConfig 不一样");
            assertEquals(f.secret(), d.secret(), key + "：是不是密钥与 LlmConfig 说的不一样");
        }
        long llmRows = TABLE.all().stream().filter(d -> d.category() == SettingsCategory.LLM).count();
        assertEquals(LlmConfig.Field.values().length + 1, llmRows, "「大模型」一组除了 LlmConfig 的各项，只该多一项「最多等多久」");
        assertTrue(def(ServerSettingsTable.LLM_API_KEY).secret(), "密钥那一项不是只写的");
        assertEquals(LlmConfig.MODEL_MAX_CHARS, def(ServerSettingsTable.LLM_MODEL).maxLength());
        assertEquals(LlmConfig.LANGUAGES, def(ServerSettingsTable.LLM_LANGUAGE).choices());
        assertEquals(LlmConfig.TEMPERATURE_UNSET, def(ServerSettingsTable.LLM_TEMPERATURE).min());
        assertEquals(LlmConfig.TEMPERATURE_MAX, def(ServerSettingsTable.LLM_TEMPERATURE).max());
        assertEquals(LlmConfig.ATTEMPT_SHARE_MIN, def(ServerSettingsTable.LLM_ATTEMPT_SHARE).min());
        assertEquals(LlmConfig.ATTEMPT_SHARE_MAX, def(ServerSettingsTable.LLM_ATTEMPT_SHARE).max());
        Map<String, LlmConfig.IntRange> ranges = Map.ofEntries(
                Map.entry(ServerSettingsTable.LLM_MAX_TOKENS, LlmConfig.MAX_TOKENS),
                Map.entry(ServerSettingsTable.LLM_MAX_ATTEMPTS, LlmConfig.MAX_ATTEMPTS),
                Map.entry(ServerSettingsTable.LLM_ATTEMPT_TIMEOUT, LlmConfig.ATTEMPT_TIMEOUT_MS),
                Map.entry(ServerSettingsTable.LLM_MIN_ATTEMPT, LlmConfig.MIN_ATTEMPT_MS),
                Map.entry(ServerSettingsTable.LLM_BACKOFF_BASE, LlmConfig.BACKOFF_BASE_MS),
                Map.entry(ServerSettingsTable.LLM_BACKOFF_MAX, LlmConfig.BACKOFF_MAX_MS),
                Map.entry(ServerSettingsTable.LLM_DEADLINE_MARGIN, LlmConfig.DEADLINE_MARGIN_MS),
                Map.entry(ServerSettingsTable.LLM_MAX_CONCURRENT, LlmConfig.MAX_CONCURRENT),
                Map.entry(ServerSettingsTable.LLM_MAX_QUEUED, LlmConfig.MAX_QUEUED),
                Map.entry(ServerSettingsTable.LLM_BREAKER_THRESHOLD, LlmConfig.BREAKER_THRESHOLD),
                Map.entry(ServerSettingsTable.LLM_BREAKER_COOLDOWN, LlmConfig.BREAKER_COOLDOWN_MS));
        ranges.forEach((key, r) -> {
            assertEquals(r.fallback(), def(key).fallback(), key);
            assertEquals(r.min(), (int) def(key).min(), key);
            assertEquals(r.max(), (int) def(key).max(), key);
        });
        for (SettingDef d : TABLE.all()) {
            if (d.category() == SettingsCategory.LLM) {
                assertEquals(SettingDef.When.IMMEDIATE, d.when(), d.key() + "：大模型那一组存了就生效");
            }
        }
    }

    @Test
    @DisplayName("替身怎么拿主意：四种脑子（与 StandInMind 同名），默认「什么也不做」，起服时用；旧的随机开关不在表里")
    void mindRow() {
        SettingDef mind = def(ServerSettingsTable.MIND);
        assertEquals(List.of("idle", "random", "smart", "llm"), mind.choices());
        assertEquals("idle", mind.fallback());
        assertEquals(SettingDef.When.SERVER_START, mind.when());
        assertTrue(TABLE.def(ServerSettingsTable.LEGACY_RANDOM).isEmpty(), "旧的随机开关还在表里");
        for (String choice : mind.choices()) {
            assertEquals(choice, StandInMind.valueOf(choice.toUpperCase(Locale.ROOT)).name().toLowerCase(Locale.ROOT));
        }
    }

    /** 每一行挑一个合规矩、又不是默认的值。 */
    private static String otherValue(SettingDef d) {
        return switch (d.kind()) {
            case FLAG -> String.valueOf(!(Boolean) d.fallback());
            case CHOICE -> d.choices().stream().filter(c -> !c.equals(d.fallback())).findFirst().orElseThrow();
            case TEXT -> switch (d.key()) {
                case ServerSettingsTable.LLM_BASE_URL -> URL;
                case ServerSettingsTable.LLM_REASONING_EFFORT -> "low";
                default -> "test-model";
            };
            case SECONDS, INT -> d.format((int) ((Integer) d.fallback() == (int) d.max() ? d.min() : d.max()));
            case DECIMAL -> d.format((Double) d.fallback() == d.max() ? d.min() : d.max());
        };
    }

    /** 新加的那几行（替身的脑子 · 动脑替身 · 大模型；密钥另有一条路）。 */
    private static List<SettingDef> newRows() {
        return TABLE.all().stream().filter(d -> !d.secret()).filter(d -> d.key().equals(ServerSettingsTable.MIND)
                || d.category() == SettingsCategory.SMART || d.category() == SettingsCategory.LLM).toList();
    }

    /** 正向对照：「新加的那几行」确实挑出了东西 —— 三段的头尾都在。 */
    private static void coversAllThreeGroups(Map<String, String> changes) {
        assertTrue(changes.keySet().containsAll(List.of(ServerSettingsTable.MIND, ServerSettingsTable.SMART_SEARCH,
                ServerSettingsTable.SMART_MEMORY_DAYS, ServerSettingsTable.LLM_ENABLED, ServerSettingsTable.LLM_DECISION_CAP,
                ServerSettingsTable.LLM_DEBUG_LOG)), "挑出来的行不全：" + changes.keySet());
    }

    @Test
    @DisplayName("每一行都经 FCAP 存得进、读得回（一批里全改），读口拼出来的旋钮与大模型设置跟着变")
    void everyNewRowRoundTripsThroughFcap() {
        try (TestSettings t = TestSettings.defaults()) {
            ServerSettings s = t.settings();
            Map<String, String> changes = new LinkedHashMap<>();
            for (SettingDef d : newRows()) {
                changes.put(d.key(), otherValue(d));
            }
            coversAllThreeGroups(changes);
            ServerSettings.Outcome outcome = s.save(true, changes);
            assertTrue(outcome.accepted(), String.valueOf(outcome.rejection()));
            Map<String, String> snapshot = s.snapshot();
            changes.forEach((key, value) -> assertEquals(value, snapshot.get(key), key + " 读回来不一样"));
            for (SettingDef d : newRows()) {
                assertEquals(d.parse(changes.get(d.key())).value(), s.current(d), d.key() + " 的类型读回来不对");
            }
            SeatPolicySettings seat = TABLE.seatPolicy(s::current);
            assertEquals((int) def(ServerSettingsTable.SMART_ROLLOUTS).max(), seat.rollouts());
            assertEquals(!D.search(), seat.search());
            LlmConfig.Draft draft = TABLE.llmDraft(s::current, null);
            assertEquals("test-model", draft.model());
            assertEquals(URL, draft.baseUrl());
            assertEquals(!LlmConfig.defaults().enabled(), draft.enabled());
            assertEquals(StandInMind.RANDOM, TABLE.mind(s::current));
            assertEquals(1, t.saves(), "一批只存一次盘");
        }
    }

    @Test
    @DisplayName("菜单的状态：每一行都改得动、按 S 只发改过的那几项，服务端回了新快照就显示新值")
    void everyNewRowRoundTripsThroughTheMenu() {
        List<SettingDef> defs = new ArrayList<>(LocalSettings.ROWS);
        defs.addAll(TABLE.all());
        Map<String, String> sent = new LinkedHashMap<>();
        SettingsMenuState.Outbox outbox = new SettingsMenuState.Outbox() {
            @Override
            public void save(Map<String, String> changes) {
                sent.putAll(changes);
            }

            @Override
            public void secret(String key, String value) {
                throw new AssertionError("这一条不该发密钥");
            }
        };
        Map<String, String> local = new HashMap<>();
        SettingsMenuState state = new SettingsMenuState(defs, new SettingsMenuState.LocalPrefs() {
            @Override
            public String get(String key) {
                return local.getOrDefault(key, "");
            }

            @Override
            public void set(String key, String value) {
                local.put(key, value);
            }
        }, outbox);
        Map<String, String> base = new LinkedHashMap<>();
        TABLE.synced().forEach(d -> base.put(d.key(), d.formattedFallback()));
        state.snapshot(true, true, base, Set.of());
        Map<String, String> expected = new LinkedHashMap<>();
        for (SettingDef d : newRows()) {
            String value = otherValue(d);
            assertTrue(state.set(d, value), d.key() + " 在菜单里改不动");
            expected.put(d.key(), value);
        }
        coversAllThreeGroups(expected);
        assertEquals(SettingsMenuState.SaveResult.SENT, state.save());
        assertEquals(expected, sent, "按 S 发出去的不是改过的那几项");
        Map<String, String> after = new LinkedHashMap<>(base);
        after.putAll(expected);
        state.snapshot(true, true, after, Set.of());
        for (SettingDef d : newRows()) {
            assertEquals(expected.get(d.key()), state.display(d).orElseThrow(), d.key());
        }
        assertTrue(state.categories().containsAll(List.of(SettingsCategory.SMART, SettingsCategory.LLM)),
                "菜单里没有动脑替身 / 大模型两张签");
    }

    @Test
    @DisplayName("读口按存着的值给：存了新的旋钮、新的等待上限，下一次读就是新的；没加载时是默认值")
    void readersFollowSavedValues() throws Exception {
        assertEquals(D, ServerSettings.seatPolicy(), "没加载时不是默认值");
        try (TestSettings t = TestSettings.defaults(); AutoCloseable use = ServerSettings.useForTests(t.settings())) {
            assertEquals(D, ServerSettings.seatPolicy(), "全是默认值时读出来的不是默认的旋钮");
            Map<String, String> changes = new LinkedHashMap<>();
            changes.put(ServerSettingsTable.SMART_ROLLOUTS, "64");
            changes.put(ServerSettingsTable.SMART_SEARCH, "false");
            changes.put(ServerSettingsTable.LLM_DECISION_CAP, "12000");
            assertTrue(t.settings().save(true, changes).accepted());
            SeatPolicySettings now = ServerSettings.seatPolicy();
            assertEquals(64, now.rollouts());
            assertFalse(now.search());
            assertEquals(12_000L, ServerSettings.llmDecisionCapMs());
            assertNotEquals(D, now);
        }
        assertEquals(D, ServerSettings.seatPolicy(), "单测换进来的那一份没换回去");
    }

    @Test
    @DisplayName("大模型设置：值取自表；密钥先取设置菜单存的、没有再看环境变量；温度小于 0 当作不发")
    void llmConfigReadsTheTableThenTheStoredKeyThenTheEnvironment() throws Exception {
        Map<String, String> env = Map.of(LlmConfig.KEY_ENV, "env-key-for-test");
        try (TestSettings t = TestSettings.defaults(); AutoCloseable use = ServerSettings.useForTests(t.settings())) {
            Map<String, String> changes = new LinkedHashMap<>();
            changes.put(ServerSettingsTable.LLM_ENABLED, "true");
            changes.put(ServerSettingsTable.LLM_BASE_URL, URL);
            changes.put(ServerSettingsTable.LLM_MODEL, "test-model");
            changes.put(ServerSettingsTable.LLM_TEMPERATURE, "-0.5");
            assertTrue(t.settings().save(true, changes).accepted());
            LlmConfig.Loaded none = ServerSettings.llmConfig(k -> null);
            assertFalse(none.broken(), String.valueOf(none.problem()));
            assertTrue(none.config().enabled());
            assertEquals("test-model", none.config().model());
            assertFalse(none.config().sendsTemperature(), "温度小于 0 该当作不发");
            assertEquals(LlmConfig.KEY_SOURCE_NONE, none.keySource());

            LlmConfig.Loaded fromEnv = ServerSettings.llmConfig(env::get);
            assertEquals("env-key-for-test", fromEnv.config().apiKey());
            assertEquals(LlmConfig.KEY_SOURCE_ENV, fromEnv.keySource());

            assertTrue(t.settings().setSecret(true, ServerSettingsTable.LLM_API_KEY, "stored-key-for-test").accepted());
            LlmConfig.Loaded stored = ServerSettings.llmConfig(env::get);
            assertEquals("stored-key-for-test", stored.config().apiKey(), "设置菜单存的密钥没压过环境变量");
            assertEquals(LlmConfig.KEY_SOURCE_STORE, stored.keySource());

            // 合不起来（开着却没写模型）：关着，但密钥取自哪里照样说得出来（status 要用）
            assertTrue(t.settings().save(true, Map.of(ServerSettingsTable.LLM_MODEL, "")).accepted());
            LlmConfig.Loaded broken = ServerSettings.llmConfig(env::get);
            assertTrue(broken.broken(), "开着却没写模型，该合不起来");
            assertFalse(broken.config().enabled());
            assertEquals(LlmConfig.KEY_SOURCE_STORE, broken.keySource(), "合不起来时说不出密钥取自哪里");
            assertFalse(broken.problem().contains("stored-key-for-test"));
        }
    }
}
