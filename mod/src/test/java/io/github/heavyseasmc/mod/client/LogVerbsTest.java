package io.github.heavyseasmc.mod.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 航海日志里每一句播报都说了「发生了什么」：两种语言的格式串里都用 {@code §l}…{@code §r} 标出动词（{@link LogInk} 加粗），
 * 或者在这里显式豁免并写明为什么（只是一个标题、一个参数、一句叙事）。用户 2026-10-13 选样张 B「动词同色加粗」。
 *
 * <h2>哪些键算播报</h2>
 * 读服务端源码：{@code broadcast(} · {@code notify(} · {@code privateEvent(} · {@code publicEventExcept(} 这几个调用的
 * 括号里出现的 lang 键字面量；键经变量、三元、辅助方法传进去的那几条扫不到，列在 {@link #INDIRECT}（并核对它们在 lang 里）。
 * 与「checkLangKeys 只认字面量」同一个盲区，所以那张表要随服务端一起改。
 *
 * <h2>记号只许长这样</h2>
 * 只有 {@code §l} 起、{@code §r} 止，成对、不嵌套、中间有字；也只许出现在播报键上 —— 别处的字由 GuiText 剥掉记号，
 * 加了也不会粗，只会让人以为加上了。
 */
class LogVerbsTest {

    private static final Path MAIN_SRC = Path.of("src", "main", "java");
    private static final Path LANG_DIR = Path.of("src", "main", "resources", "assets", "heavyseas", "lang");
    private static final Pattern CALL = Pattern.compile("(?:\\bbroadcast|\\.notify|\\bprivateEvent|\\bpublicEventExcept)\\s*\\(");
    private static final Pattern KEY = Pattern.compile("\"(heavyseas\\.[a-z0-9_.]+)\"");
    private static final String ON = "§l";
    private static final String OFF = "§r";

    /** 键经变量传进播报、扫字面量扫不到的那几条（来源写在后面）。 */
    private static final Set<String> INDIRECT = Set.of(
            // ContestPhase.responseKey：表态那一句
            "heavyseas.contest.agreed", "heavyseas.contest.refused",
            "heavyseas.contest.ration_passed", "heavyseas.contest.ration_objected",
            // ContestPhase.open 的 key 参数：这一段窗口开了
            "heavyseas.contest.consent_wait", "heavyseas.contest.stances", "heavyseas.contest.weapons",
            "heavyseas.contest.pick_wait",
            // GameFlow.outcomeKey：收场
            "heavyseas.game.over.landed", "heavyseas.game.over.all_dead",
            // ThirstPhase.untendedNotice
            "heavyseas.game.thirst_untended",
            // ContestPhase 的 receipt、PlayerFeedback 的 message：先存进变量再播
            "heavyseas.ux.receipt.stolen", "heavyseas.ux.receipt.water_damage", "heavyseas.ux.receipt.rescue");

    /** 不标动词的播报键，及为什么。 */
    private static final Map<String, String> EXEMPT = exempt();

    private static Map<String, String> exempt() {
        Map<String, String> m = new LinkedHashMap<>();
        String arg = "是嵌在别的句子里的参数，不是一句";
        for (String k : List.of("heavyseas.contest.side_attack", "heavyseas.contest.side_defend", "heavyseas.game.dummy",
                "heavyseas.hud.unlimited")) {
            m.put(k, arg);
        }
        String heading = "只是一个标题或状态名（界面不是说明书：状态名照写，不造一个动词）";
        for (String k : List.of("heavyseas.contest.stances", "heavyseas.contest.weapons", "heavyseas.endgame.begin",
                "heavyseas.endgame.round_love", "heavyseas.endgame.score_line", "heavyseas.endgame.winner",
                "heavyseas.game.card_played", "heavyseas.game.final_line", "heavyseas.game.final_line_removed",
                "heavyseas.game.overboard", "heavyseas.game.provision_begin", "heavyseas.game.provision_empty",
                "heavyseas.game.seat", "heavyseas.game.thirst", "heavyseas.game.weather",
                "heavyseas.game.weather_extra_navigation", "heavyseas.game.weather_extra_provision",
                "heavyseas.game.weather_skip_navigation", "heavyseas.overboard.pending", "heavyseas.ux.battle.summary",
                "heavyseas.ux.battle.weapons")) {
            m.put(k, heading);
        }
        String narrative = "开场与收场的叙事句，整句读，不挑动词";
        for (String k : List.of("heavyseas.opening.line1", "heavyseas.opening.line2", "heavyseas.opening.line3",
                "heavyseas.endgame.arrived", "heavyseas.game.over.landed", "heavyseas.game.over.all_dead")) {
            m.put(k, narrative);
        }
        String notice = "只给本人的确认，句子本身就是结果";
        for (String k : List.of("heavyseas.ux.connection.action_lost", "heavyseas.ux.receipt.no_card")) {
            m.put(k, notice);
        }
        m.put("heavyseas.debug.trace", "调试追踪，格式由调用方拼");
        return m;
    }

    // ---------------------------------------------------------------- 判据本体

    /** 一个值里的动词段；记号写坏了（不成对、嵌套、空段、别的格式码）返回 {@code null}。 */
    static List<String> verbs(String value) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (true) {
            int start = value.indexOf('§', i);
            if (start < 0) {
                return out;
            }
            if (!value.startsWith(ON, start)) {
                return null;
            }
            int end = value.indexOf('§', start + 2);
            if (end < 0 || !value.startsWith(OFF, end)) {
                return null;
            }
            String verb = value.substring(start + 2, end);
            if (verb.isBlank()) {
                return null;
            }
            out.add(verb);
            i = end + 2;
        }
    }

    /** 返回每一条问题；空 = 通过。 */
    static List<String> problems(Set<String> broadcast, Map<String, Map<String, String>> langs) {
        List<String> p = new ArrayList<>();
        for (var lang : langs.entrySet()) {
            for (var e : lang.getValue().entrySet()) {
                List<String> v = verbs(e.getValue());
                if (v == null) {
                    p.add(lang.getKey() + "：" + e.getKey() + " 的动词记号写坏了：「" + e.getValue() + "」");
                } else if (!v.isEmpty() && !broadcast.contains(e.getKey())) {
                    p.add(lang.getKey() + "：" + e.getKey() + " 不是播报，却标了动词（别处不会加粗）");
                }
            }
            for (String key : broadcast) {
                String value = lang.getValue().get(key);
                if (value == null) {
                    p.add(lang.getKey() + "：播报键 " + key + " 在 lang 里没有");
                    continue;
                }
                List<String> v = verbs(value);
                if (v == null) {
                    continue;                            // 上面已经报过
                }
                if (EXEMPT.containsKey(key) && !v.isEmpty()) {
                    p.add(lang.getKey() + "：" + key + " 已豁免（" + EXEMPT.get(key) + "），却又标了动词");
                } else if (!EXEMPT.containsKey(key) && v.isEmpty()) {
                    p.add(lang.getKey() + "：播报 " + key + " 没有标动词：「" + value + "」");
                }
            }
        }
        for (String key : EXEMPT.keySet()) {
            if (!broadcast.contains(key)) {
                p.add("豁免表里的 " + key + " 已经不是播报了 —— 表过期了");
            }
        }
        return p;
    }

    // ---------------------------------------------------------------- 真文件

    /** 扫服务端源码得到的播报键（不含 {@link #INDIRECT}）；以「.」结尾的是拼接前缀，不是键。 */
    static Set<String> scannedKeys() throws IOException {
        Set<String> keys = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN_SRC)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f, StandardCharsets.UTF_8);
                Matcher call = CALL.matcher(src);
                while (call.find()) {
                    int end = closingParen(src, call.end());
                    Matcher key = KEY.matcher(src.substring(call.end(), end));
                    while (key.find()) {
                        if (!key.group(1).endsWith(".")) {
                            keys.add(key.group(1));
                        }
                    }
                }
            }
        }
        return keys;
    }

    /** 从左括号之后走到与它配对的右括号（跳过字符串里的括号）。 */
    private static int closingParen(String src, int from) {
        int depth = 1;
        boolean inString = false;
        int i = from;
        while (i < src.length() && depth > 0) {
            char c = src.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            i++;
        }
        return i;
    }

    static Map<String, Map<String, String>> langs() throws IOException {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (String lang : List.of("zh_cn", "en_us")) {
            JsonObject json = JsonParser.parseString(Files.readString(LANG_DIR.resolve(lang + ".json"),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, String> values = new TreeMap<>();
            json.entrySet().forEach(e -> values.put(e.getKey(), e.getValue().getAsString()));
            out.put(lang, values);
        }
        return out;
    }

    private static Set<String> broadcastKeys() throws IOException {
        Set<String> all = new TreeSet<>(scannedKeys());
        all.addAll(INDIRECT);
        return all;
    }

    @Test
    @DisplayName("每一条播报在两种语言里都标了动词，或者显式豁免；记号只许成对的 §l…§r、只许在播报上")
    void everyBroadcastDeclaresItsVerb() throws IOException {
        Set<String> scanned = scannedKeys();
        // 正向对照：扫描确实扫到了东西，而且扫到的是播报 —— 一条都没有时「全都标了」与「没在扫」长得一样
        assertTrue(scanned.size() > 60, "只扫到 " + scanned.size() + " 条播报键 —— 没在扫：" + scanned);
        assertTrue(scanned.contains("heavyseas.contest.stolen_front") && scanned.contains("heavyseas.game.rowing"),
                "认得出的播报（抢牌 · 划船）没被扫到：" + scanned);
        List<String> problems = problems(broadcastKeys(), langs());
        assertEquals(List.of(), problems, String.join("\n", problems));
    }

    @Test
    @DisplayName("红测：删掉一条播报的记号、写坏一条、给非播报加上一条 —— 各红在它自己那一条上")
    void redTests() throws IOException {
        Set<String> broadcast = broadcastKeys();
        Map<String, Map<String, String>> langs = langs();
        assertEquals(List.of(), problems(broadcast, langs), "真文件本身不干净，红测没有意义");

        Map<String, Map<String, String>> dropped = copy(langs);
        String stolen = "heavyseas.contest.stolen_front";
        dropped.get("zh_cn").put(stolen, langs.get("zh_cn").get(stolen).replace(ON, "").replace(OFF, ""));
        List<String> p1 = problems(broadcast, dropped);
        assertEquals(1, p1.size(), String.join("\n", p1));
        assertTrue(p1.get(0).contains(stolen) && p1.get(0).contains("没有标动词"), p1.get(0));

        Map<String, Map<String, String>> broken = copy(langs);
        broken.get("en_us").put(stolen, "%s " + ON + "takes %s's face-up %s.");
        List<String> p2 = problems(broadcast, broken);
        assertEquals(1, p2.size(), String.join("\n", p2));
        assertTrue(p2.get(0).contains(stolen) && p2.get(0).contains("写坏"), p2.get(0));

        Map<String, Map<String, String>> stray = copy(langs);
        stray.get("zh_cn").put("heavyseas.score.total", ON + "合计" + OFF);
        List<String> p3 = problems(broadcast, stray);
        assertEquals(1, p3.size(), String.join("\n", p3));
        assertTrue(p3.get(0).contains("heavyseas.score.total") && p3.get(0).contains("不是播报"), p3.get(0));
    }

    private static Map<String, Map<String, String>> copy(Map<String, Map<String, String>> langs) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        langs.forEach((k, v) -> out.put(k, new TreeMap<>(v)));
        return out;
    }
}
