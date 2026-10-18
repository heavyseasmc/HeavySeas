package io.github.heavyseasmc.mod.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 每一种值的类型：网络上的字符串 → 值 → 字符串来回一趟不变（{@link SettingDef#parse} · {@link SettingDef#check} · {@link SettingDef#format}），
 * 不合规矩的一律拒。第三刀（动脑替身 · 大模型）加设置时用的就是这几种。
 */
final class SettingDefKindsTest {

    private static final SettingDef SECONDS = SettingDef.seconds(SettingsCategory.WINDOWS, "windows.t", 20_000, 5, 300, "c");
    private static final SettingDef INT = SettingDef.integer(SettingsCategory.LLM, "llm.timeout_ms", 15_000, 500, 120_000,
            SettingDef.Unit.MILLISECONDS, SettingDef.When.IMMEDIATE, "c");
    private static final SettingDef DECIMAL = SettingDef.decimal(SettingsCategory.LLM, "llm.share", 0.55, 0.3, 1.0, 0.05,
            SettingDef.When.IMMEDIATE, "c");
    private static final SettingDef FLAG = SettingDef.flag(SettingsCategory.STAND_INS, "stand_ins.x", true,
            SettingDef.When.IMMEDIATE, "c");
    private static final SettingDef CHOICE = SettingDef.choice(SettingsCategory.LLM, "llm.language", "zh_cn",
            List.of("zh_cn", "en_us"), SettingDef.When.IMMEDIATE, "c");
    private static final SettingDef TEXT = SettingDef.text(SettingsCategory.LLM, "llm.model", "", 20,
            SettingDef.When.IMMEDIATE, "c");
    private static final SettingDef SECRET = SettingDef.secret(SettingsCategory.LLM, "llm.api_key", 64,
            SettingDef.When.IMMEDIATE, "c");

    /** 一个值来回一趟：字符串解析出来的值再格式化，回到同一个字符串；值本身合规矩。 */
    private static void roundTrip(SettingDef def, String wire, Object value) {
        SettingDef.Parsed parsed = def.parse(wire);
        assertTrue(parsed.ok(), def.key() + "「" + wire + "」被拒：" + parsed.rejection());
        assertEquals(value, parsed.value(), def.key());
        assertEquals(wire, def.format(parsed.value()), def.key() + " 格式化回不去");
        assertTrue(def.check(parsed.value()).isEmpty(), def.key());
    }

    private static void rejected(SettingDef def, String wire) {
        assertFalse(def.parse(wire).ok(), def.key() + "「" + wire + "」不该收");
    }

    @Test
    @DisplayName("每一种类型来回一趟不变；默认值本身来回也不变")
    void everyKindRoundTrips() {
        roundTrip(SECONDS, "20", 20);
        roundTrip(SECONDS, "300", 300);
        roundTrip(INT, "500", 500);
        roundTrip(INT, "120000", 120_000);
        roundTrip(DECIMAL, "0.55", 0.55);
        roundTrip(DECIMAL, "1", 1.0);
        roundTrip(DECIMAL, "0.3", 0.3);
        roundTrip(FLAG, "true", true);
        roundTrip(FLAG, "false", false);
        roundTrip(CHOICE, "en_us", "en_us");
        roundTrip(TEXT, "gpt-test", "gpt-test");
        roundTrip(TEXT, "", "");
        roundTrip(SECRET, "sk-abc", "sk-abc");
        for (SettingDef def : List.of(SECONDS, INT, DECIMAL, FLAG, CHOICE, TEXT, SECRET)) {
            roundTrip(def, def.formattedFallback(), def.fallback());
        }
        // 表里每一项（服务端的与本机的）的默认值都来回得了
        for (SettingDef def : ServerSettingsTable.DEFAULT.all()) {
            roundTrip(def, def.formattedFallback(), def.fallback());
        }
        for (SettingDef def : LocalSettings.ROWS) {
            roundTrip(def, def.formattedFallback(), def.fallback());
        }
    }

    @Test
    @DisplayName("越界 · 类型不对 · 不在选项里 · 超长 · 控制字符：一律拒")
    void outOfRuleValuesAreRejected() {
        rejected(SECONDS, "4");
        rejected(SECONDS, "301");
        rejected(SECONDS, "2.5");
        rejected(SECONDS, "abc");
        rejected(INT, "499");
        rejected(DECIMAL, "0.29");
        rejected(DECIMAL, "1.01");
        rejected(DECIMAL, "NaN");
        rejected(DECIMAL, "1e0");
        rejected(FLAG, "yes");
        rejected(FLAG, "1");
        rejected(CHOICE, "fr_fr");
        rejected(CHOICE, "");
        rejected(TEXT, "x".repeat(21));
        rejected(TEXT, "a\u0007b");
        rejected(SECRET, "a\nb");
        rejected(SECRET, "k".repeat(65));
    }

    @Test
    @DisplayName("工厂拒收坏定义：默认值不合规矩 · 密钥不是文字 · 选项是空的 · 注释是空的")
    void badDefinitionsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> SettingDef.integer(SettingsCategory.LLM, "llm.a", 5, 6, 9,
                SettingDef.Unit.NONE, SettingDef.When.IMMEDIATE, "c"));
        assertThrows(IllegalArgumentException.class, () -> SettingDef.decimal(SettingsCategory.LLM, "llm.b", 0.5, 0, 1, 0,
                SettingDef.When.IMMEDIATE, "c"));
        assertThrows(IllegalArgumentException.class, () -> SettingDef.choice(SettingsCategory.LLM, "llm.c", "x", List.of(),
                SettingDef.When.IMMEDIATE, "c"));
        assertThrows(IllegalArgumentException.class, () -> SettingDef.choice(SettingsCategory.LLM, "llm.d", "x",
                List.of("a", "b"), SettingDef.When.IMMEDIATE, "c"));
        assertThrows(IllegalArgumentException.class, () -> SettingDef.text(SettingsCategory.LLM, "llm.e", "toolong", 3,
                SettingDef.When.IMMEDIATE, "c"));
        assertThrows(IllegalArgumentException.class, () -> new SettingDef("llm.f", SettingsCategory.LLM, SettingDef.Kind.FLAG,
                false, 0, 0, 1, List.of(), 0, SettingDef.Unit.NONE, true, SettingDef.When.IMMEDIATE, "c"));
        assertThrows(IllegalArgumentException.class, () -> SettingDef.flag(SettingsCategory.LLM, "llm.g", true,
                SettingDef.When.IMMEDIATE, " "));
    }
}
