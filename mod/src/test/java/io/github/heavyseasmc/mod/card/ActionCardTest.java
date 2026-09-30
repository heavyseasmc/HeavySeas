package io.github.heavyseasmc.mod.card;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 行动 · 表态 · 站队那十张牌（ADR-0050）：枚举 · 母版 · lang 三方对得上。
 *
 * <p>这一族不在 {@code data/} 里，所以物资那几道「data ↔ 母版 ↔ lang」闸门管不到它；
 * 这里补上同样的三条：每个常量有母版、母版 {@code <title>} 的最后一段就是 zh_cn 的牌名、
 * 牌名 · 说明 · 类别题头两种语言都有。反过来，母版多出一张没有常量的也算错（贴图烘了却永远画不到）。
 */
class ActionCardTest {

    private static final Path MASTERS = Path.of("..", "art", "cards");
    private static final Path LANG = Path.of("src", "main", "resources", "assets", "heavyseas", "lang");
    private static final Pattern TITLE = Pattern.compile("<title>(.*?)</title>");

    // ---------------------------------------------------------------- 判据本体

    /**
     * 返回每一处对不上的地方。空 = 通过。
     *
     * @param masters 母版 id → {@code <title>} 全文
     * @param zh      zh_cn 的键值
     * @param en      en_us 的键值
     */
    static List<String> problems(List<String> ids, Map<String, String> masters, JsonObject zh, JsonObject en,
                                 List<String> extraKeys) {
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            String title = masters.get(id);
            String key = "heavyseas.actioncard." + id;
            if (title == null) {
                out.add("缺母版 art/cards/action." + id + ".svg");
            } else if (zh.has(key)) {
                String last = title.substring(title.lastIndexOf(" · ") + 3);
                if (!last.equals(zh.get(key).getAsString())) {
                    out.add("action." + id + ".svg 的牌名「" + last + "」≠ zh_cn「" + zh.get(key).getAsString() + "」");
                }
            }
        }
        for (String id : masters.keySet()) {
            if (!ids.contains(id)) {
                out.add("母版 action." + id + ".svg 没有对应的 ActionCard 常量");
            }
        }
        for (String key : extraKeys) {
            for (Map.Entry<String, JsonObject> lang : Map.of("zh_cn", zh, "en_us", en).entrySet()) {
                if (!lang.getValue().has(key) || lang.getValue().get(key).getAsString().isBlank()) {
                    out.add(lang.getKey() + " 缺 " + key);
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 真数据

    private static List<String> ids() {
        return Stream.of(ActionCard.values()).map(ActionCard::id).toList();
    }

    private static List<String> keys() {
        List<String> out = new ArrayList<>();
        for (ActionCard a : ActionCard.values()) {
            out.add(a.titleKey());
            out.add(a.effectKey());
            out.add(a.group().captionKey());
        }
        return out;
    }

    private static Map<String, String> masters() throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> files = Files.list(MASTERS)) {
            for (Path p : files.toList()) {
                String name = p.getFileName().toString();
                if (name.startsWith("action.") && name.endsWith(".svg")) {
                    Matcher m = TITLE.matcher(Files.readString(p, StandardCharsets.UTF_8));
                    out.put(name.substring(7, name.length() - 4), m.find() ? m.group(1) : "");
                }
            }
        }
        return out;
    }

    private static JsonObject lang(String code) throws IOException {
        return JsonParser.parseString(Files.readString(LANG.resolve(code + ".json"), StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    @Test
    @DisplayName("十张行动 · 表态 · 站队牌：每个常量有母版、母版牌名就是 zh_cn 的牌名、牌名 · 说明 · 题头两种语言都有")
    void enumMastersAndLangAgree() throws IOException {
        Map<String, String> masters = masters();
        // 正向对照：一张母版都没读到时「0 处问题」与「没在查」输出一样
        assertEquals(ActionCard.values().length, masters.size(), "读到的母版张数：" + masters.keySet());
        List<String> problems = problems(ids(), masters, lang("zh_cn"), lang("en_us"), keys());
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    @DisplayName("红测：少一张母版 · 牌名不一致 · 多一张母版 · 缺一个说明，各自红在自己那一行")
    void redTestsNameTheInjectedDefect() throws IOException {
        JsonObject zh = lang("zh_cn");
        JsonObject en = lang("en_us");
        Map<String, String> good = masters();

        Map<String, String> missing = new TreeMap<>(good);
        assertTrue(missing.remove("steal") != null, "注入点过期了：没有 action.steal 可删");
        assertEquals(List.of("缺母版 art/cards/action.steal.svg"), problems(ids(), missing, zh, en, keys()));

        Map<String, String> renamed = new TreeMap<>(good);
        renamed.put("row", "怒海狂涛 · 行动卡 · 摇橹");
        assertEquals(List.of("action.row.svg 的牌名「摇橹」≠ zh_cn「" + zh.get("heavyseas.actioncard.row").getAsString() + "」"),
                problems(ids(), renamed, zh, en, keys()));

        Map<String, String> extra = new TreeMap<>(good);
        extra.put("bribe", "怒海狂涛 · 行动卡 · 贿赂");
        assertEquals(List.of("母版 action.bribe.svg 没有对应的 ActionCard 常量"), problems(ids(), extra, zh, en, keys()));

        JsonObject enMissing = en.deepCopy();
        assertTrue(enMissing.remove("heavyseas.actioncard.pass.effect") != null, "注入点过期了");
        assertEquals(List.of("en_us 缺 heavyseas.actioncard.pass.effect"), problems(ids(), good, zh, enMissing, keys()));
    }
}
