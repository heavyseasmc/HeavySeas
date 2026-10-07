package io.github.heavyseasmc.mod.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.heavyseasmc.mod.config.LocalSettings;
import io.github.heavyseasmc.mod.config.ServerSettingsTable;
import io.github.heavyseasmc.mod.config.SettingDef;
import io.github.heavyseasmc.mod.config.SettingsCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 设置菜单里每一项的名字、说明、选项名，每一张签的名字，底下每一条按键提示的那个词，两份 lang 里都要有（ADR-0099 §6）。
 *
 * <p>这些键是<b>拼出来</b>的（{@code heavyseas.settings.<键>}），构建期的 checkLangKeys 只认字面量、看不见它们；
 * 漏一条的表现是菜单上露出一行原始键名，游戏照跑、没有任何报错（TLM 教训：缺英文 lang 键时界面上露出原始键名）。
 * 反过来也查：lang 里那几段（本机 · 时限 · 节奏 · 替身 · 演示局）的键必须对得上表里的一项 —— 改了键名忘了改 lang 也会红。
 */
final class SettingsLangKeysTest {

    private static final Path LANG = Path.of("src", "main", "resources", "assets", "heavyseas", "lang");
    private static final Path SCREEN = Path.of("src", "client", "java", "io", "github", "heavyseasmc", "mod", "client",
            "SettingsScreen.java");
    /** 底下按键提示：{@code keys("词", …)} / {@code primary("词", …)}。 */
    private static final Pattern HINT = Pattern.compile("\\b(?:keys|primary)\\(\"([a-z_]+)\"");

    private static JsonObject lang(String name) throws IOException {
        return JsonParser.parseString(Files.readString(LANG.resolve(name + ".json"), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /** 设置表（本机 + 服务端）要的全部键。 */
    static Set<String> required(List<SettingDef> defs) {
        Set<String> out = new LinkedHashSet<>();
        for (SettingDef def : defs) {
            out.add("heavyseas.settings." + def.key());
            out.add("heavyseas.settings." + def.key() + ".desc");
            for (String choice : def.choices()) {
                out.add("heavyseas.settings." + def.key() + "." + choice);
            }
        }
        for (SettingsCategory c : SettingsCategory.values()) {
            out.add("heavyseas.settings.category." + c.id());
        }
        return out;
    }

    static List<SettingDef> allDefs() {
        List<SettingDef> defs = new ArrayList<>(LocalSettings.ROWS);
        defs.addAll(ServerSettingsTable.DEFAULT.all());
        return defs;
    }

    /** 判据本体：{@code required} 里哪几条在这份 lang 里没有。 */
    static List<String> missing(Set<String> required, JsonObject lang, String name) {
        return required.stream().filter(k -> !lang.has(k)).map(k -> name + " 少了 " + k).toList();
    }

    @Test
    @DisplayName("每一项的名字 · 说明 · 选项名，每一张签，两份 lang 里都在")
    void everySettingHasBothLanguages() throws IOException {
        Set<String> required = required(allDefs());
        assertTrue(required.size() >= 50, "只算出 " + required.size() + " 条 —— 没在核");
        List<String> problems = new ArrayList<>();
        for (String name : List.of("zh_cn", "en_us")) {
            problems.addAll(missing(required, lang(name), name));
        }
        assertEquals(List.of(), problems, String.join("\n", problems));
    }

    @Test
    @DisplayName("底下每一条按键提示的那个词（keys / primary），两份 lang 里都在")
    void everyKeyHintWordExists() throws IOException {
        String source = Files.readString(SCREEN, StandardCharsets.UTF_8);
        Set<String> words = new LinkedHashSet<>();
        Matcher m = HINT.matcher(source);
        while (m.find()) {
            words.add("heavyseas.keys." + m.group(1));
        }
        assertTrue(words.size() >= 8, "只认出 " + words + " —— 没在扫");
        List<String> problems = new ArrayList<>();
        for (String name : List.of("zh_cn", "en_us")) {
            problems.addAll(missing(words, lang(name), name));
        }
        assertEquals(List.of(), problems, String.join("\n", problems));
    }

    @Test
    @DisplayName("反过来：lang 里那几段的设置键都对得上表里的一项（改了键名忘了改 lang 也红）")
    void noOrphanSettingKeys() throws IOException {
        Set<String> required = required(allDefs());
        Set<String> sections = new LinkedHashSet<>();
        for (SettingDef def : allDefs()) {
            sections.add("heavyseas.settings." + def.key().substring(0, def.key().indexOf('.') + 1));
        }
        assertTrue(sections.size() >= 5, "只认出 " + sections + " 几段 —— 没在扫");
        List<String> orphans = new ArrayList<>();
        for (String name : List.of("zh_cn", "en_us")) {
            for (String key : lang(name).keySet()) {
                if (sections.stream().anyMatch(key::startsWith) && !required.contains(key)) {
                    orphans.add(name + " 多了 " + key);
                }
            }
        }
        assertEquals(List.of(), orphans, String.join("\n", orphans));
    }

    @Test
    @DisplayName("红测：表里多一项、lang 里没有 —— 判据点名那两条（名字与说明）")
    void judgeNamesAMissingRow() throws IOException {
        List<SettingDef> defs = allDefs();
        defs.add(SettingDef.flag(SettingsCategory.STAND_INS, "stand_ins.not_in_lang", false, SettingDef.When.IMMEDIATE, "c"));
        assertEquals(List.of("zh_cn 少了 heavyseas.settings.stand_ins.not_in_lang",
                        "zh_cn 少了 heavyseas.settings.stand_ins.not_in_lang.desc"),
                missing(required(defs), lang("zh_cn"), "zh_cn"));
    }
}
