package io.github.heavyseasmc.mod.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 设置（{@link LlmConfig}）与它现在唯一的来源（{@link LlmConfigFile}）。
 *
 * <p>{@link LlmConfig.Field} 那张说明书要与字段一项对一项 —— 换配置来源的人照它一项对一项地接，表与字段分家了就会接错。
 */
class LlmConfigTest {

    private static final Function<String, String> NO_ENV = key -> null;

    @Test
    @DisplayName("❗说明书与字段一项对一项：键名、次序相同，写着的缺省值就是 defaults() 的值")
    void fieldTableMatchesTheRecord() throws Exception {
        RecordComponent[] components = LlmConfig.class.getRecordComponents();
        LlmConfig.Field[] fields = LlmConfig.Field.values();
        assertEquals(Arrays.stream(components).map(RecordComponent::getName).toList(),
                Arrays.stream(fields).map(LlmConfig.Field::key).toList(), "字段与说明书对不上（加了字段没补表，或者次序变了）");
        LlmConfig defaults = LlmConfig.defaults();
        for (int i = 0; i < components.length; i++) {
            Object value = components[i].getAccessor().invoke(defaults);
            assertEquals(fields[i].defaultValue(), String.valueOf(value), fields[i].key() + " 的缺省值");
            assertFalse(fields[i].range().isBlank(), fields[i].key() + " 没写取值范围");
        }
    }

    @Test
    @DisplayName("机密只有 apiKey；每一项都只给管理员改")
    void secretAndOpOnlyFlags() {
        assertEquals(List.of(LlmConfig.Field.API_KEY),
                Arrays.stream(LlmConfig.Field.values()).filter(LlmConfig.Field::secret).toList());
        assertTrue(Arrays.stream(LlmConfig.Field.values()).allMatch(LlmConfig.Field::opOnly));
    }

    /** 拿一份合规矩的设置，只改一项（按字段名），走构造器。 */
    private static LlmConfig with(String key, Object value) throws Exception {
        LlmConfig base = new LlmConfig(true, "http://127.0.0.1:9/v1", "", "m", 64, -1.0, "", 15_000, 0.55, 3, 800, 300, 3_000,
                4, 32, 1_000, 5, 30_000, "zh_cn", false);
        RecordComponent[] components = LlmConfig.class.getRecordComponents();
        Object[] args = new Object[components.length];
        Class<?>[] types = new Class<?>[components.length];
        boolean found = false;
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            args[i] = components[i].getName().equals(key) ? value : components[i].getAccessor().invoke(base);
            found |= components[i].getName().equals(key);
        }
        assertTrue(found, "没有字段 " + key);
        Constructor<LlmConfig> ctor = LlmConfig.class.getDeclaredConstructor(types);
        try {
            return ctor.newInstance(args);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    @Test
    @DisplayName("❗构造器按范围校验：每个整数项的上下限本身合规矩、出界一格就抛，消息里点名那一项")
    void constructorEnforcesIntRanges() throws Exception {
        Map<String, LlmConfig.IntRange> ranges = Map.ofEntries(
                Map.entry("maxTokens", LlmConfig.MAX_TOKENS), Map.entry("attemptTimeoutMs", LlmConfig.ATTEMPT_TIMEOUT_MS),
                Map.entry("maxAttempts", LlmConfig.MAX_ATTEMPTS), Map.entry("minAttemptMs", LlmConfig.MIN_ATTEMPT_MS),
                Map.entry("maxConcurrent", LlmConfig.MAX_CONCURRENT),
                Map.entry("maxQueued", LlmConfig.MAX_QUEUED), Map.entry("deadlineMarginMs", LlmConfig.DEADLINE_MARGIN_MS),
                Map.entry("breakerThreshold", LlmConfig.BREAKER_THRESHOLD),
                Map.entry("breakerCooldownMs", LlmConfig.BREAKER_COOLDOWN_MS));
        // 退避的两项互相牵制（base ≤ max；基准配置里 base 300、max 3000），单独查
        assertDoesNotThrow(() -> with("backoffBaseMs", LlmConfig.BACKOFF_BASE_MS.min()));
        assertDoesNotThrow(() -> with("backoffBaseMs", 3_000));
        assertThrows(IllegalArgumentException.class, () -> with("backoffBaseMs", LlmConfig.BACKOFF_BASE_MS.min() - 1));
        assertThrows(IllegalArgumentException.class, () -> with("backoffBaseMs", 3_001), "base 大过 max");
        assertDoesNotThrow(() -> with("backoffMaxMs", 300));
        assertDoesNotThrow(() -> with("backoffMaxMs", LlmConfig.BACKOFF_MAX_MS.max()));
        assertThrows(IllegalArgumentException.class, () -> with("backoffMaxMs", 299), "max 小过 base");
        assertThrows(IllegalArgumentException.class, () -> with("backoffMaxMs", LlmConfig.BACKOFF_MAX_MS.max() + 1));
        for (var e : ranges.entrySet()) {
            String key = e.getKey();
            LlmConfig.IntRange r = e.getValue();
            assertDoesNotThrow(() -> with(key, r.min()), key + " 下限");
            assertDoesNotThrow(() -> with(key, r.max()), key + " 上限");
            assertDoesNotThrow(() -> with(key, r.fallback()), key + " 缺省");
            for (int bad : new int[]{r.min() - 1, r.max() + 1}) {
                String msg = assertThrows(IllegalArgumentException.class, () -> with(key, bad), key + "=" + bad).getMessage();
                assertTrue(msg.contains(key), msg);
            }
        }
    }

    @Test
    @DisplayName("构造器：temperature 只认 -1 或 0–2；reasoningEffort 只认空串或小写字母；language 只认两种；开着时地址与模型必填")
    void constructorEnforcesTheRest() throws Exception {
        assertDoesNotThrow(() -> with("temperature", 0.0));
        assertDoesNotThrow(() -> with("temperature", 2.0));
        assertThrows(IllegalArgumentException.class, () -> with("temperature", -0.5));
        assertThrows(IllegalArgumentException.class, () -> with("temperature", 2.5));
        assertDoesNotThrow(() -> with("attemptShare", 0.3));
        assertDoesNotThrow(() -> with("attemptShare", 1.0));
        assertThrows(IllegalArgumentException.class, () -> with("attemptShare", 0.29));
        assertThrows(IllegalArgumentException.class, () -> with("attemptShare", 1.01));
        assertDoesNotThrow(() -> with("reasoningEffort", "low"));
        assertThrows(IllegalArgumentException.class, () -> with("reasoningEffort", "Low!"));
        assertThrows(IllegalArgumentException.class, () -> with("language", "fr_fr"));
        assertThrows(IllegalArgumentException.class, () -> with("baseUrl", ""));
        assertThrows(IllegalArgumentException.class, () -> with("model", " "));
        assertThrows(IllegalArgumentException.class, () -> with("apiKey", "sk-a\nb"));
        assertThrows(IllegalArgumentException.class, () -> with("baseUrl", "ftp://h/v1"));
        assertThrows(IllegalArgumentException.class, () -> with("baseUrl", "http://h/v1/chat/completions"));
        assertThrows(IllegalArgumentException.class, () -> with("baseUrl", "http://h/v1?key=1"));
        // 审查 2026-10-07 L1：地址里带用户名密码 —— 它会随设置同步给客户端，也是另一种把凭据送到别处的写法
        assertThrows(IllegalArgumentException.class, () -> with("baseUrl", "http://user:pass@h/v1"), "地址里带 user:pass@");
        assertThrows(IllegalArgumentException.class, () -> with("baseUrl", "https://token@h/v1"), "地址里带 user@");
        assertEquals("http://h:1/v1", with("baseUrl", " http://h:1/v1/// ").baseUrl(), "首尾空白与末尾的 / 去掉");
        LlmConfig off = LlmConfig.off();
        assertFalse(off.enabled());
        assertEquals(LlmConfig.defaults(), off);
    }

    @Test
    @DisplayName("没有文件 → 关着，但不算写坏")
    void missingFileIsOffNotBroken(@TempDir Path dir) {
        LlmConfig.Loaded loaded = LlmConfigFile.load(dir.resolve("llm.json"), NO_ENV);
        assertFalse(loaded.broken(), loaded.problem());
        assertFalse(loaded.config().enabled());
        assertTrue(loaded.note().contains("没有"), loaded.note());
    }

    @ParameterizedTest(name = "「{0}」")
    @ValueSource(strings = {
            "{", "", "[]", "{} {}", "{\"enabled\": true,}",
            "{\"enable\": true}",
            "{\"enabled\": \"yes\"}",
            "{\"maxTokens\": \"64\"}",
            "{\"maxTokens\": 64.5}",
            "{\"maxTokens\": 0}",
            "{\"retries\": [1]}",
            "{\"language\": \"fr_fr\"}",
            "{\"temperature\": 3}",
            "{\"backoffBaseMs\": 500, \"backoffMaxMs\": 100}",
            "{\"maxAttempts\": 0}",
            "{\"attemptShare\": 0.1}",
            "{\"breakerThreshold\": -1}",
            "{\"retries\": 2}",
            "{\"enabled\": true}",
            "{\"enabled\": true, \"baseUrl\": \"http://127.0.0.1:9/v1\"}",
            "{\"enabled\": true, \"model\": \"m\", \"baseUrl\": \"127.0.0.1:9/v1\"}"})
    @DisplayName("❗写坏了（语法 · 认不出的键 · 类型 · 范围 · 开着却缺地址或模型）→ 关着，并有一句点名的原因")
    void malformedIsOffAndBroken(String text) {
        LlmConfig.Loaded loaded = LlmConfigFile.parse(text, NO_ENV);
        assertTrue(loaded.broken(), "该算写坏：" + text);
        assertFalse(loaded.config().enabled());
        assertFalse(loaded.problem().isBlank());
    }

    @Test
    @DisplayName("写坏的文件：原因里带文件路径；一次把几处错都报出来")
    void brokenFileNamesThePathAndEveryProblem(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("llm.json");
        Files.writeString(file, "{\"enable\": true, \"maxTokens\": -1, \"language\": \"xx\"}", StandardCharsets.UTF_8);
        LlmConfig.Loaded loaded = LlmConfigFile.load(file, NO_ENV);
        assertTrue(loaded.broken());
        assertTrue(loaded.problem().contains(file.toString()), loaded.problem());
        for (String part : List.of("enable", "maxTokens", "language")) {
            assertTrue(loaded.problem().contains(part), "没报出「" + part + "」：" + loaded.problem());
        }
    }

    @Test
    @DisplayName("好的文件：开着，写了的照写，没写的取缺省值")
    void goodFileEnables() {
        LlmConfig.Loaded loaded = LlmConfigFile.parse("""
                {"enabled": true, "baseUrl": "http://127.0.0.1:9/v1/", "model": "m", "maxTokens": 128,
                 "temperature": 0.3, "reasoningEffort": "low", "language": "en_us", "debugLog": true}""", NO_ENV);
        assertFalse(loaded.broken(), loaded.problem());
        LlmConfig c = loaded.config();
        assertTrue(c.enabled());
        assertEquals("http://127.0.0.1:9/v1", c.baseUrl());
        assertEquals("http://127.0.0.1:9/v1/chat/completions", c.endpoint().toString());
        assertEquals("127.0.0.1:9", c.hostLabel());
        assertEquals(128, c.maxTokens());
        assertEquals(0.3, c.temperature(), 1e-9);
        assertEquals("low", c.reasoningEffort());
        assertEquals("en_us", c.language());
        assertTrue(c.debugLog());
        assertEquals(LlmConfig.ATTEMPT_TIMEOUT_MS.fallback(), c.attemptTimeoutMs());
        assertEquals(LlmConfig.MAX_ATTEMPTS.fallback(), c.maxAttempts());
        assertEquals(LlmConfig.BREAKER_THRESHOLD.fallback(), c.breakerThreshold());
        assertEquals(LlmConfig.MAX_CONCURRENT.fallback(), c.maxConcurrent());
        assertEquals("无", loaded.keySource());
        assertFalse(c.hasKey());
    }

    @Test
    @DisplayName("❗密钥：环境变量压过文件；toString、Draft、类型写错时的原因里都不出现密钥")
    void keyHandling() {
        String text = "{\"enabled\": true, \"baseUrl\": \"http://h/v1\", \"model\": \"m\", \"apiKey\": \"file-secret-123\"}";
        LlmConfig.Loaded fromFile = LlmConfigFile.parse(text, NO_ENV);
        assertEquals("file-secret-123", fromFile.config().apiKey());
        assertEquals("配置文件", fromFile.keySource());

        LlmConfig.Loaded fromEnv = LlmConfigFile.parse(text, Map.of(LlmConfig.KEY_ENV, "env-secret-456")::get);
        assertEquals("env-secret-456", fromEnv.config().apiKey());
        assertTrue(fromEnv.keySource().contains(LlmConfig.KEY_ENV));

        assertFalse(fromFile.config().toString().contains("file-secret-123"));
        assertFalse(fromFile.config().describe().toString().contains("file-secret-123"));
        LlmConfig.Draft draft = new LlmConfig.Draft(true, "http://h/v1", "draft-secret-789", "m", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null);
        assertFalse(draft.toString().contains("draft-secret-789"));
        LlmConfig.Loaded wrongType = LlmConfigFile.parse("{\"apiKey\": 12345678}", NO_ENV);
        assertTrue(wrongType.broken());
        assertFalse(wrongType.problem().contains("12345678"), wrongType.problem());
    }

    @Test
    @DisplayName("写坏的配置起出来的服务：当场 DISABLED")
    void brokenConfigGivesDisabledService() throws Exception {
        LlmConfig.Loaded loaded = LlmConfigFile.parse("{\"enabled\": true", NO_ENV);
        assertTrue(loaded.broken());
        try (LlmService service = LlmService.start(loaded.config())) {
            assertFalse(service.enabled());
            ChoiceOutcome o = service.choose(LlmServiceTest.request(3, 10_000)).get();
            assertEquals(ChoiceOutcome.Fallback.DISABLED, o.fallback());
        }
    }
}
