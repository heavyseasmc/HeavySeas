package io.github.heavyseasmc.mod.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.Font;
import java.awt.FontFormatException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GUI 上会出现的每一个字，都要在随模组分发的那套字体子集里（ADR-0037 §7.3）。
 *
 * <p>不在子集里时 Minecraft <b>不报错</b>：字体定义后面挂着 {@code minecraft:default}，缺的字静默退回像素字 ——
 * 屏幕上只是那一个字变小、变样。2026-09-25 第一次实拍阵容一面：名字前那个「✓」是代码里写死的字面量，
 * 不在子集里，于是八个按钮上各有一个认不出的小点。**lang 里的字管线建子集时会扫到，代码里写死的字没人扫。**
 *
 * <p>查两处：① 客户端源码里 {@code "…"} 字面量中的非 ASCII 字 · ② 两种语言 lang 文件里的全部值。
 * 粗体子集只查牌名（牌名是粗体排的）。与 {@code GuiFontLadderTest} 一样读文件本身，不起客户端。
 */
class GuiGlyphCoverageTest {

    private static final Path CLIENT_SRC = Path.of("src", "client", "java", "io", "github", "heavyseasmc", "mod", "client");
    private static final Path FONT_DIR = Path.of("src", "main", "resources", "assets", "heavyseas", "font");
    private static final Path LANG_DIR = Path.of("src", "main", "resources", "assets", "heavyseas", "lang");
    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");
    /** 牌名那几族 lang 键：它们在牌面上用粗体排。 */
    private static final Pattern CARD_NAME_KEY = Pattern.compile(
            "heavyseas\\.(provision\\.[a-z0-9_]+|character\\.[a-z0-9_]+|weather\\.[a-z0-9_]+|nav\\.title\\..+|nav\\.name_sep)");

    // ---------------------------------------------------------------- 判据本体

    /** 返回「这个字 → 出现在哪」里字体显示不了的那些。空 = 通过。 */
    static List<String> missing(Font font, Map<Integer, String> wanted) {
        return wanted.entrySet().stream()
                .filter(e -> !font.canDisplay(e.getKey()))
                .map(e -> "「" + new String(Character.toChars(e.getKey())) + "」U+"
                        + Integer.toHexString(e.getKey()).toUpperCase() + " —— " + e.getValue())
                .toList();
    }

    /** 一段文字里要画出来的字（跳过 ASCII、空白与 lang 的 %s 占位）。 */
    static void collect(String text, String where, Map<Integer, String> into) {
        text.codePoints().filter(cp -> cp > 0x7F && !Character.isWhitespace(cp))
                .forEach(cp -> into.putIfAbsent(cp, where));
    }

    // ---------------------------------------------------------------- 真文件

    @Test
    @DisplayName("客户端源码里写死的非 ASCII 字，都在正文字体子集里")
    void sourceLiteralsAreInSubset() throws IOException {
        Map<Integer, String> wanted = new TreeMap<>();
        try (Stream<Path> files = Files.list(CLIENT_SRC)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    String code = line.strip();
                    if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) {
                        continue;                        // 注释与文档不上屏幕
                    }
                    int comment = code.indexOf("//");
                    Matcher m = LITERAL.matcher(comment >= 0 ? code.substring(0, comment) : code);
                    while (m.find()) {
                        // LOGGER 的日志行不上屏幕
                        if (code.contains("LOGGER.") || code.contains("Exception(")) {
                            continue;
                        }
                        collect(m.group(1), f.getFileName().toString(), wanted);
                    }
                }
            }
        }
        // 正向对照：键帽上的「←」「→」就写在源码里 —— 一个都没收到说明扫描坏了，不是都在子集里
        assertTrue(wanted.containsKey((int) '←') && wanted.containsKey((int) '→'),
                "源码里的「←」「→」没被收进来 —— 没在查：" + wanted.keySet());
        List<String> problems = missing(font("serif.ttf"), wanted);
        assertEquals(List.of(), problems, String.join("\n", problems));
    }

    @Test
    @DisplayName("两种语言 lang 里的每个字都在正文字体子集里；牌名还要在粗体子集里")
    void langValuesAreInSubset() throws IOException {
        Map<Integer, String> regular = new TreeMap<>();
        Map<Integer, String> bold = new TreeMap<>();
        java.util.Set<String> world = worldOnlyKeys();
        assertTrue(!world.isEmpty(), "头顶名牌那几条 lang 键一条都没认出来 —— 排除规则坏了，不是没有");
        int read = 0;
        for (String lang : List.of("zh_cn", "en_us")) {
            JsonObject json = JsonParser.parseString(Files.readString(LANG_DIR.resolve(lang + ".json"),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            for (var e : json.entrySet()) {
                if (world.contains(e.getKey())) {
                    continue;                            // 头顶名牌画在世界里、用 Minecraft 自带的字，不经 GuiText
                }
                read++;
                String value = e.getValue().getAsString();
                collect(value, lang + ":" + e.getKey(), regular);
                if (CARD_NAME_KEY.matcher(e.getKey()).matches() && !e.getKey().endsWith(".effect")) {
                    collect(value, lang + ":" + e.getKey(), bold);
                }
            }
        }
        assertTrue(read > 200 && bold.size() > 40, "只读到 " + read + " 条 lang、牌名 " + bold.size() + " 个字 —— 没在查");
        List<String> problems = new java.util.ArrayList<>(missing(font("serif.ttf"), regular));
        missing(font("serif_bold.ttf"), bold).forEach(p -> problems.add("粗体 " + p));
        assertEquals(List.of(), problems, String.join("\n", problems));
    }

    @Test
    @DisplayName("红测：把「✓」写回阵容一面那样的字面量 → 必须点名它，且只点名它")
    void redTestCheckMark() throws IOException {
        Map<Integer, String> wanted = new TreeMap<>();
        collect("珠宝商", "对照", wanted);
        assertEquals(List.of(), missing(font("serif.ttf"), wanted), "对照组：子集里本来就有的字不该红");
        collect("✓ ", "RosterScreen.java（注入）", wanted);
        List<String> problems = missing(font("serif.ttf"), wanted);
        assertEquals(1, problems.size(), "只该红在「✓」：" + problems);
        assertTrue(problems.get(0).startsWith("「✓」U+2713"), problems.get(0));
    }

    @Test
    @DisplayName("红测：粗体子集只收 lang 里的字 —— 一个 lang 里没有的常用字必须红在粗体、不红在正文")
    void redTestBoldOnlyHasLangChars() throws IOException {
        // 2026-09-25 实测：「醉者落海」（ADR-0039 加的牌名）在粗体子集里缺「醉」，因为字体是 09-21 按当时的 lang 建的。
        // 这里拿一个 lang 里没有、常用字表里有的字（鲸）重演那个形状：正文有、粗体没有。
        Map<Integer, String> wanted = new TreeMap<>();
        collect("鲸", "注入", wanted);
        assertEquals(List.of(), missing(font("serif.ttf"), wanted), "对照：正文子集收了常用字表，该有「鲸」");
        assertEquals(1, missing(font("serif_bold.ttf"), wanted).size(), "粗体子集不该有 lang 以外的「鲸」");
    }

    /**
     * 只在头顶名牌里用的 lang 键：{@code Nameplates.java} 里出现、客户端源码里一次都没出现的那些。
     * 从源码现取，不抄一张表 —— 表会过期（证伪表「判据里的每个字面量都是一颗定时器」）。
     */
    private static java.util.Set<String> worldOnlyKeys() throws IOException {
        Pattern key = Pattern.compile("\"(heavyseas\\.[a-z0-9_.]+)\"");
        java.util.Set<String> world = new TreeSet<>();
        Matcher m = key.matcher(Files.readString(Path.of("src", "main", "java", "io", "github", "heavyseasmc", "mod",
                "world", "Nameplates.java"), StandardCharsets.UTF_8));
        while (m.find()) {
            world.add(m.group(1));
        }
        try (Stream<Path> files = Files.list(CLIENT_SRC)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher c = key.matcher(Files.readString(f, StandardCharsets.UTF_8));
                while (c.find()) {
                    world.remove(c.group(1));
                }
            }
        }
        return world;
    }

    private static final Map<String, Font> FONTS = new java.util.HashMap<>();

    private static Font font(String file) throws IOException {
        Font cached = FONTS.get(file);
        if (cached != null) {
            return cached;
        }
        try (InputStream in = Files.newInputStream(FONT_DIR.resolve(file))) {
            Font f = Font.createFont(Font.TRUETYPE_FONT, in);
            FONTS.put(file, f);
            return f;
        } catch (FontFormatException e) {
            throw new IOException(file + " 读不成字体：" + e, e);
        }
    }
}
