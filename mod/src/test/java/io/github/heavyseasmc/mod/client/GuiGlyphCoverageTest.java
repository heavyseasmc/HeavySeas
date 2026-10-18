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
import java.util.function.IntPredicate;
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
 * 粗体子集查牌名（牌名是粗体排的）、播报里标出的动词（航海日志里加粗，{@link LogVerbsTest}）与界面上另几处粗体字（{@link #BOLD_UI_KEY}）。与 {@code GuiFontLadderTest} 一样读文件本身，不起客户端。
 */
class GuiGlyphCoverageTest {

    private static final Path CLIENT_SRC = Path.of("src", "client", "java", "io", "github", "heavyseasmc", "mod", "client");
    private static final Path FONT_DIR = Path.of("src", "main", "resources", "assets", "heavyseas", "font");
    private static final Path LANG_DIR = Path.of("src", "main", "resources", "assets", "heavyseas", "lang");
    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\\\n]|\\\\.)*)\"");
    /** 牌名那几族 lang 键：它们在牌面上用粗体排。 */
    private static final Pattern CARD_NAME_KEY = Pattern.compile(
            "heavyseas\\.(provision\\.[a-z0-9_]+|character\\.[a-z0-9_]+|weather\\.[a-z0-9_]+|nav\\.title\\..+|nav\\.name_sep"
                    + "|actioncard\\.[a-z0-9_]+)");
    /**
     * 牌名之外、界面上也用粗体排的那几条（2026-10-13 核对 {@code GuiText} 各处 bold 参数为真的调用）：
     * 世界里准星下那张纸条第一行（{@code WorldInteraction}：「把…递给」「正在与…互动」）· 指人时准星下那一行（{@code DesignationAim}）·
     * 主画面状态牌上的阶段字（{@code GameHud}：「%s阶段」与阶段名）· 头像上的「昏」签（{@code SeatMarks}）·
     * 「爱」「恨」两个字（{@code GameHud} · {@code HandScreen}）· 不限时那枚「∞」（{@code GameScreen}）。
     * 第一版只查牌名与动词：粗体子集一直缺「递」「互」，那两行静默退回常规体（粗体定义后面挂的是同字号的常规体），没有一道判据红过。
     */
    static final Pattern BOLD_UI_KEY = Pattern.compile(
            "heavyseas\\.(world\\.(gift|locked|look)|hud\\.(aim|aim_none|phase_now|unlimited)|phase\\.[a-z_]+"
                    + "|seat\\.mark\\.unconscious|hand\\.(love|hate))");

    /**
     * 方块、物品、实体、创造物品栏页签的名字：游戏自己的界面（物品提示、物品栏、F3）用 Minecraft 自带的字画，
     * 不经 GuiText —— 本模组的字体子集不必、也不该为它们扩（ADR-0058 Q6 起这几族一下子多了三十来条）。
     * 若本模组自己的界面也引用了其中某一条（客户端源码里出现这个键），它照旧要查。
     */
    // Sound subtitles are drawn by Minecraft's subtitle overlay, not GuiText's serif subset.
    private static final Pattern VANILLA_RENDERED_KEY = Pattern.compile("(block|item|entity|itemGroup|subtitles)\\.heavyseas\\..+");

    // ---------------------------------------------------------------- 判据本体

    /** 返回「这个字 → 出现在哪」里字体显示不了的那些。空 = 通过。 */
    static List<String> missing(IntPredicate canDisplay, Map<Integer, String> wanted) {
        return wanted.entrySet().stream()
                .filter(e -> !canDisplay.test(e.getKey()))
                .map(e -> "「" + new String(Character.toChars(e.getKey())) + "」U+"
                        + Integer.toHexString(e.getKey()).toUpperCase() + " —— " + e.getValue())
                .toList();
    }

    private static List<String> coverageProblems(IntPredicate regularFont, IntPredicate boldFont,
                                                 Map<Integer, String> regular, Map<Integer, String> bold) {
        List<String> problems = new java.util.ArrayList<>(missing(regularFont, regular));
        missing(boldFont, bold).forEach(p -> problems.add("粗体 " + p));
        return problems;
    }

    /** 一段文字里要画出来的字（跳过 ASCII、空白、lang 的 %s 占位与格式记号 —— 记号由 GuiText 剥掉，不上屏）。 */
    static void collect(String text, String where, Map<Integer, String> into) {
        GuiText.plain(text).codePoints().filter(cp -> cp > 0x7F && !Character.isWhitespace(cp))
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
        List<String> problems = missing(font("serif.ttf")::canDisplay, wanted);
        assertEquals(List.of(), problems, String.join("\n", problems));
    }

    @Test
    @DisplayName("两种语言 lang 里的每个字都在正文字体子集里；牌名与播报动词还要在粗体子集里")
    void langValuesAreInSubset() throws IOException {
        Map<Integer, String> regular = new TreeMap<>();
        Map<Integer, String> bold = new TreeMap<>();
        java.util.Set<String> world = worldOnlyKeys();
        assertTrue(!world.isEmpty(), "头顶名牌那几条 lang 键一条都没认出来 —— 排除规则坏了，不是没有");
        java.util.Set<String> clientKeys = keysReferencedByClient();
        int read = 0;
        int vanillaRendered = 0;
        int boldVerbs = 0;
        java.util.Set<String> boldUi = new TreeSet<>();
        for (String lang : List.of("zh_cn", "en_us")) {
            JsonObject json = JsonParser.parseString(Files.readString(LANG_DIR.resolve(lang + ".json"),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            for (var e : json.entrySet()) {
                if (world.contains(e.getKey())) {
                    continue;                            // 头顶名牌画在世界里、用 Minecraft 自带的字，不经 GuiText
                }
                if (VANILLA_RENDERED_KEY.matcher(e.getKey()).matches() && !clientKeys.contains(e.getKey())) {
                    vanillaRendered++;
                    continue;                            // 方块 / 物品 / 实体 / 页签名：游戏自己的界面画，见 VANILLA_RENDERED_KEY
                }
                read++;
                String value = e.getValue().getAsString();
                collect(value, lang + ":" + e.getKey(), regular);
                if (CARD_NAME_KEY.matcher(e.getKey()).matches() && !e.getKey().endsWith(".effect")) {
                    collect(value, lang + ":" + e.getKey(), bold);
                }
                if (BOLD_UI_KEY.matcher(e.getKey()).matches()) {
                    collect(value, lang + ":" + e.getKey() + "（粗体界面字）", bold);
                    boldUi.add(e.getKey());
                }
                // 播报里标出的动词在航海日志里用粗体排（LogInk）
                List<String> verbs = LogVerbsTest.verbs(value);
                if (verbs != null) {
                    for (String verb : verbs) {
                        collect(verb, lang + ":" + e.getKey() + "（动词）", bold);
                        boldVerbs++;
                    }
                }
            }
        }
        assertTrue(read > 200 && bold.size() > 40, "只读到 " + read + " 条 lang、牌名 " + bold.size() + " 个字 —— 没在查");
        assertTrue(boldVerbs > 100, "两种语言的播报动词只认出 " + boldVerbs + " 段 —— 没在查");
        // 正向对照：上面点名的那几条键都还在 lang 里（键改了名，这一族就悄悄不查了）
        for (String k : List.of("heavyseas.world.gift", "heavyseas.world.locked", "heavyseas.hud.aim", "heavyseas.hud.phase_now",
                "heavyseas.seat.mark.unconscious", "heavyseas.hand.love", "heavyseas.hud.unlimited")) {
            assertTrue(boldUi.contains(k), "粗体界面字那一族没认出 " + k + " —— 键改名了，或排除规则把它放过了");
        }
        // 正向对照：两种语言里方块 / 物品名至少该认出二十几条；一条都没有时「放过了」与「规则写坏了」长得一样
        assertTrue(vanillaRendered >= 40, "游戏自己画的名字只认出 " + vanillaRendered + " 条 —— 排除规则坏了");
        List<String> problems = coverageProblems(font("serif.ttf")::canDisplay, font("serif_bold.ttf")::canDisplay,
                regular, bold);
        assertEquals(List.of(), problems, String.join("\n", problems));
    }

    /**
     * 右下角按键提示（ADR-0100）由本模组的字体画，而其中的句子不在本模组的 lang 里，是 Minecraft 自己的（1.21.1 自带 lang，
     * 换装样张 {@code src/in/vanilla_zh.json} 记过对象哈希）：句子用常规体，键名（键帽上）用粗体。
     * 粗体子集里没有「脱」—— 所以整句一律常规体（ADR-0100 风险 ④）。
     */
    static final Map<String, List<String>> VANILLA_CORNER_LINES = Map.of(
            "mount.onboard", List.of("按下%1$s来脱离", "Press %1$s to Dismount"));
    /** 键帽上会出现的键名（Minecraft 自带 lang 的 {@code key.keyboard.*}）：坐骑默认是左 Shift。 */
    static final List<String> VANILLA_CORNER_KEYS = List.of("左Shift", "右Shift", "Left Shift", "Right Shift");

    @Test
    @DisplayName("右下角按键提示要画的 Minecraft 自带句子：常规体都有；键帽上的键名粗体都有；每个走右下角的外来键都登记过")
    void vanillaCornerLinesAreInSubset() throws IOException {
        // 走右下角的键里，不是本模组 lang 的（不以 heavyseas. 开头），必须在上面那张表里 —— 否则新加一条外来句子不会有人查它的字
        for (String key : ActionBarEcho.CORNER_KEYS) {
            assertTrue(key.startsWith("heavyseas.") || VANILLA_CORNER_LINES.containsKey(key),
                    key + " 走右下角，却不是本模组的 lang，也没登记它的句子 —— 缺字会静默退回像素字");
        }
        Map<Integer, String> regular = new TreeMap<>();
        Map<Integer, String> bold = new TreeMap<>();
        VANILLA_CORNER_LINES.forEach((k, lines) -> lines.forEach(s -> collect(s, k, regular)));
        VANILLA_CORNER_KEYS.forEach(s -> collect(s, "键帽", bold));
        assertTrue(regular.containsKey((int) '脱') && bold.containsKey((int) '左'), "没收到要查的字 —— 没在查");
        List<String> problems = coverageProblems(font("serif.ttf")::canDisplay, font("serif_bold.ttf")::canDisplay,
                regular, bold);
        assertEquals(List.of(), problems, String.join("\n", problems));
        // 对照：同一句若拿粗体画就缺「脱」—— 这正是整句用常规体的原因；哪天粗体补上了这一条就该改成别的对照
        assertTrue(!font("serif_bold.ttf").canDisplay('脱'), "粗体子集现在有「脱」了：更新这条对照与 ActionBarEcho 的注释");
    }

    /**
     * GB2312 的汉字：区 16–55 是一级（3755 字），区 56–87 是二级（3008 字）。用 JDK 自带的 GB2312 解码现算，不抄字表。
     *
     * @param lastRow 到哪一区为止（55 = 只要一级，87 = 全集）
     */
    static java.util.Set<Integer> gb2312(int lastRow) {
        java.nio.charset.CharsetDecoder dec = java.nio.charset.Charset.forName("GB2312").newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
        java.util.Set<Integer> out = new TreeSet<>();
        for (int row = 16; row <= lastRow; row++) {
            for (int cell = 1; cell <= 94; cell++) {
                byte[] b = {(byte) (0xA0 + row), (byte) (0xA0 + cell)};
                try {
                    String s = dec.decode(java.nio.ByteBuffer.wrap(b)).toString();
                    if (s.codePointCount(0, s.length()) == 1 && Character.isIdeographic(s.codePointAt(0))) {
                        out.add(s.codePointAt(0));
                    }
                } catch (java.nio.charset.CharacterCodingException e) {
                    // 一级末尾 55 区 90–94 是空位
                }
            }
        }
        return out;
    }

    /**
     * 聊天正文用常规体画（2026-10-13 用户定），玩家打的字不在 lang 里：常规体子集收 GB2312 全集。
     * 全集之外的生僻字照旧静默退回像素字 —— 那是已接受的代价，不在这里查。
     */
    @Test
    @DisplayName("常规体子集收齐 GB2312 一级字（3755）")
    void regularCoversGb2312Level1() throws IOException {
        java.util.Set<Integer> level1 = gb2312(55);
        assertEquals(3755, level1.size(), "GB2312 一级字应为 3755 个 —— 区段算错了，不是字体的事");
        assertMissingNone(font("serif.ttf")::canDisplay, level1, "GB2312 一级");
    }

    @Test
    @DisplayName("常规体子集收齐 GB2312 全部汉字（6763）：聊天里玩家打的字")
    void regularCoversGb2312All() throws IOException {
        java.util.Set<Integer> all = gb2312(87);
        assertEquals(6763, all.size(), "GB2312 汉字应为 6763 个 —— 区段算错了，不是字体的事");
        assertMissingNone(font("serif.ttf")::canDisplay, all, "GB2312");
    }

    @Test
    @DisplayName("红测：GB2312 判据在受控字库缺一个一级字、一个二级字时各点名它")
    void redTestGb2312() {
        java.util.Set<Integer> all = gb2312(87);
        assertTrue(all.contains((int) '啊') && all.contains((int) '亍') && !gb2312(55).contains((int) '亍'),
                "对照字不在预期的那一级 —— 区段算错了");
        IntPredicate withoutTwo = cp -> cp != '啊' && cp != '亍';
        Map<Integer, String> wanted = new TreeMap<>();
        all.forEach(cp -> wanted.put(cp, "GB2312"));
        assertEquals(List.of("「亍」U+4E8D —— GB2312", "「啊」U+554A —— GB2312"), missing(withoutTwo, wanted));
    }

    private static void assertMissingNone(IntPredicate font, java.util.Set<Integer> chars, String where) {
        Map<Integer, String> wanted = new TreeMap<>();
        chars.forEach(cp -> wanted.put(cp, where));
        List<String> problems = missing(font, wanted);
        assertEquals(List.of(), problems.size() > 20 ? problems.subList(0, 20) : problems,
                "缺 " + problems.size() + " 个（列前 20 个）");
    }

    @Test
    @DisplayName("红测：只放过游戏自己画的那几族名字（方块 · 物品 · 实体 · 页签），本模组界面的键一条不放")
    void vanillaRenderedKeysAreOnlyThoseFamilies() {
        for (String k : List.of("block.heavyseas.skiff_hull", "item.heavyseas.gull_spawn_egg", "entity.heavyseas.gull",
                "itemGroup.heavyseas.building", "subtitles.heavyseas.bark_captain_thirst")) {
            assertTrue(VANILLA_RENDERED_KEY.matcher(k).matches(), k + " 该放过");
        }
        for (String k : List.of("heavyseas.provision.water", "heavyseas.hud.sea", "heavyseas.block.hull",
                "blockheavyseas.x", "block.heavyseas", "item.minecraft.stick", "subtitlesheavyseas.x", "subtitles.heavyseas")) {
            assertTrue(!VANILLA_RENDERED_KEY.matcher(k).matches(), k + " 不该放过");
        }
    }

    @Test
    @DisplayName("缺字判据：受控字库缺勾号时只报它；补齐后通过，不限制真实字体的扩充")
    void redTestCheckMark() {
        Map<Integer, String> wanted = new TreeMap<>();
        collect("珠宝商", "对照", wanted);
        IntPredicate withoutCheckMark = cp -> cp != 0x2713;
        assertEquals(List.of(), missing(withoutCheckMark, wanted), "对照：可显示的字不该报缺字");
        collect("✓ ", "RosterScreen.java（注入）", wanted);
        assertEquals(List.of("「✓」U+2713 —— RosterScreen.java（注入）"), missing(withoutCheckMark, wanted));
        assertEquals(List.of(), missing(cp -> true, wanted), "字库补齐后不应继续报缺字");
    }

    @Test
    @DisplayName("正文与粗体分别查：同一个字仅粗体缺失时点名粗体，补齐后通过")
    void redTestBoldCoverageIsIndependent() {
        // 正文有、粗体没有是夹具的条件，不是对当前字体资源的永久限制。
        Map<Integer, String> wanted = new TreeMap<>();
        collect("鲸", "注入", wanted);
        IntPredicate complete = cp -> true;
        IntPredicate withoutWhale = cp -> cp != '鲸';
        assertEquals(List.of("粗体 「鲸」U+9CB8 —— 注入"), coverageProblems(complete, withoutWhale, wanted, wanted));
        assertEquals(List.of("「鲸」U+9CB8 —— 注入"), coverageProblems(withoutWhale, complete, wanted, wanted));
        assertEquals(List.of(), coverageProblems(complete, complete, wanted, wanted));
    }

    /**
     * 只在头顶名牌里用的 lang 键：{@code Nameplates.java} 里出现、客户端源码里一次都没出现的那些。
     * 从源码现取，不抄一张表 —— 表会过期（证伪表「判据里的每个字面量都是一颗定时器」）。
     */
    /** 客户端源码里以字面量出现的 lang 键（任何命名空间）：本模组自己的界面会拿去排的那些。 */
    private static java.util.Set<String> keysReferencedByClient() throws IOException {
        Pattern key = Pattern.compile("\"((?:block|item|entity|itemGroup|subtitles)\\.heavyseas\\.[A-Za-z0-9_.]+)\"");
        java.util.Set<String> out = new TreeSet<>();
        try (Stream<Path> files = Files.walk(CLIENT_SRC)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = key.matcher(Files.readString(f, StandardCharsets.UTF_8));
                while (m.find()) {
                    out.add(m.group(1));
                }
            }
        }
        return out;
    }

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
