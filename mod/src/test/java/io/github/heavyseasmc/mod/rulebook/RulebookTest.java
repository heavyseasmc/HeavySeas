package io.github.heavyseasmc.mod.rulebook;

import io.github.heavyseasmc.engine.model.Provision;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.weather.WeatherCard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 规则书（ADR-0087 §2）：源文件能展开，数据里的每一个人、每一张牌都写到了，写坏了当场点名。
 * 中英两份源文件各查一遍；英文那份还要与中文同构（生成指令同序、章与小节一样多）。
 */
class RulebookTest {

    private static final List<Rulebook.KeyLine> KEYS = List.of(new Rulebook.KeyLine("查看手牌", "R"));

    private static Rulebook.Facts facts(String lang) {
        Map<String, String> strings = RulebookData.langFile(lang);
        return RulebookData.facts(strings::get, KEYS);
    }

    private static String src(String lang) {
        return RulebookData.source(lang)
                .orElseThrow(() -> new AssertionError("jar 里没有 rulebook/" + lang + ".md —— 没在测，不是通过"));
    }

    private static String zh() {
        return src("zh_cn");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"zh_cn", "en_us"})
    @DisplayName("整本展开得了：扉页开头、十五章加附录（中英各一份）")
    void wholeBookExpands(String lang) {
        List<Rulebook.Block> blocks = Rulebook.parse(src(lang), facts(lang));
        assertInstanceOf(Rulebook.TitlePage.class, blocks.getFirst());
        long chapters = blocks.stream().filter(b -> b instanceof Rulebook.Chapter).count();
        assertEquals(16, chapters, lang + "：十五章 + 附");
        System.out.println("规则书 " + lang + "：" + blocks.size() + " 块，" + chapters + " 章");
    }

    @Test
    @DisplayName("生成的那几段换成 en_us 的 lang 也展开得了 —— 两份 lang 都有书要的每一个键")
    void generatedPartsExpandInEnglish() {
        assertDoesNotThrow(() -> Rulebook.parse(zh(), facts("en_us")));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"zh_cn", "en_us"})
    @DisplayName("❗数据里的每个角色、每种物资、每张天候在书里各恰好一次（中英各查）—— 新加一张牌而书没跟上，这里红")
    void everyEntityWrittenOnce(String lang) {
        String src = src(lang);
        Rulebook.Facts f = facts(lang);
        assertExactlyOnce(lang, src, "roster", f.roster().characters().stream().map(s -> s.id().value()).toList());
        assertExactlyOnce(lang, src, "provision", f.provisions().all().stream().map(Provision::id).toList());
        assertExactlyOnce(lang, src, "weather", f.weather().stream().map(WeatherCard::id).toList());
    }

    private static void assertExactlyOnce(String lang, String src, String directive, List<String> expected) {
        List<String> written = Rulebook.directiveIds(src, directive);
        assertFalse(expected.isEmpty(), "数据里一个 " + directive + " 都没读到 —— 判据坏了");
        Set<String> dup = written.stream().filter(id -> Collections.frequency(written, id) > 1)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(written);
        Set<String> extra = new TreeSet<>(written);
        expected.forEach(extra::remove);
        assertEquals(Set.of(), missing, lang + " @" + directive + " 书里没写到");
        assertEquals(Set.of(), extra, lang + " @" + directive + " 书里写了数据里没有的");
        assertEquals(Set.of(), dup, lang + " @" + directive + " 写了两遍");
    }

    @Test
    @DisplayName("英文与中文同构：生成指令一条不差、次序相同，章与小节一样多 —— 中文改了结构而英文没跟上，这里红")
    void englishMirrorsChinese() {
        assertEquals(generators(zh()), generators(src("en_us")), "en_us 的 @ 指令与 zh_cn 不同（缺、多或次序不同）");
        List<Rulebook.Block> zhBlocks = Rulebook.parse(zh(), facts("zh_cn"));
        List<Rulebook.Block> enBlocks = Rulebook.parse(src("en_us"), facts("en_us"));
        assertEquals(count(zhBlocks, Rulebook.Chapter.class), count(enBlocks, Rulebook.Chapter.class), "章数");
        assertEquals(count(zhBlocks, Rulebook.Heading.class), count(enBlocks, Rulebook.Heading.class), "小节数");
    }

    /** 从数据展开的那几条 @ 指令，按出现次序（扉页三行是各语言自己的字，不比）。 */
    private static List<String> generators(String source) {
        return Arrays.stream(source.split("\n")).map(String::strip)
                .filter(l -> l.startsWith("@"))
                .filter(l -> !l.startsWith("@title") && !l.startsWith("@subtitle") && !l.startsWith("@motto"))
                .toList();
    }

    private static long count(List<Rulebook.Block> blocks, Class<?> kind) {
        return blocks.stream().filter(kind::isInstance).count();
    }

    @Test
    @DisplayName("讲台按客户端语言挑源文件：en_us 用英文那份、zh_cn 用中文那份，别的语言退到 en_us")
    void pickFollowsClientLanguage() {
        Map.Entry<String, String> en = RulebookData.pick("en_us");
        assertEquals("en_us", en.getKey());
        assertEquals(src("en_us"), en.getValue());
        Map.Entry<String, String> zh = RulebookData.pick("zh_cn");
        assertEquals("zh_cn", zh.getKey());
        assertEquals(zh(), zh.getValue());
        assertEquals("en_us", RulebookData.pick("fr_fr").getKey(), "没有那一种语言时依次退到 FALLBACK_LANGUAGES，第一个是 en_us");
    }

    @Test
    @DisplayName("名字与数字来自数据和 lang：第三章那一行与 data/roster 一致")
    void rosterLineComesFromData() {
        Rulebook.Facts f = facts("zh_cn");
        Survivor kid = f.roster().characters().stream().filter(s -> s.id().value().equals("kid")).findFirst().orElseThrow();
        List<Rulebook.Block> blocks = Rulebook.parse("@title a\n@subtitle b\n@motto c\n@roster kid\n", f);
        Rulebook.Para line = assertInstanceOf(Rulebook.Para.class, blocks.get(1));
        String text = line.spans().stream().map(Rulebook.Span::text).collect(Collectors.joining());
        assertTrue(text.startsWith("小孩"), text);
        assertTrue(text.contains("体型 " + kid.size()) && text.contains("生存分 " + kid.survival())
                && text.contains("座位 " + kid.seat()), text);
    }

    @Test
    @DisplayName("写坏了当场点名：认不出的指令 · 没有的 id · 没配对的粗体 · 缺扉页 · 缺 lang")
    void brokenSourceThrowsWithLine() {
        Rulebook.Facts f = facts("zh_cn");
        String head = "@title a\n@subtitle b\n@motto c\n";
        assertThrowsAt(() -> Rulebook.parse(head + "@provisions water\n", f), 4, "认不出");
        assertThrowsAt(() -> Rulebook.parse(head + "@provision grog\n", f), 4, "grog");
        assertThrowsAt(() -> Rulebook.parse(head + "\n**一半\n", f), 5, "没有配对");
        assertThrowsAt(() -> Rulebook.parse(head + "  缩进的散文\n", f), 4, "列表项");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> Rulebook.parse("# 一\n正文\n", f))
                .getMessage().contains("扉页"));
        Function<String, String> missing = key -> null;
        Rulebook.Facts noLang = RulebookData.facts(missing, KEYS);
        assertThrowsAt(() -> Rulebook.parse(head + "@roster kid\n", noLang), 4, "lang 里没有");
    }

    private static void assertThrowsAt(org.junit.jupiter.api.function.Executable run, int line, String fragment) {
        String msg = assertThrows(IllegalArgumentException.class, run).getMessage();
        assertTrue(msg.contains("第 " + line + " 行") && msg.contains(fragment), msg);
    }

    @Test
    @DisplayName("讲台的钩子：客户端没登记（专用服务端）时开不了、不抛；登记了才开")
    void viewDoesNothingUntilTheClientInstallsIt() {
        RulebookView.install(null);
        assertFalse(RulebookView.open());
        int[] opened = {0};
        try {
            RulebookView.install(() -> opened[0]++);
            assertTrue(RulebookView.open());
            assertEquals(1, opened[0]);
        } finally {
            RulebookView.install(null);
        }
        assertFalse(RulebookView.open());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"zh_cn", "en_us"})
    @DisplayName("网页版与书同一串块：每一章都进了目录（中英各一份）")
    void htmlHasEveryChapter(String lang) {
        String html = RulebookHtml.render(lang, src(lang));
        List<Rulebook.Block> blocks = Rulebook.parse(src(lang), facts(lang));
        blocks.stream().filter(b -> b instanceof Rulebook.Chapter).map(b -> ((Rulebook.Chapter) b).title())
                .forEach(t -> assertTrue(html.contains(">" + esc(t) + "</a>"), lang + " 目录里缺 " + t));
        assertTrue(html.indexOf("<nav>") < html.indexOf("<h2"), "目录在正文之前");
        assertTrue(html.contains("<html lang=\"" + lang.replace('_', '-') + "\">"), "页面的语言标记");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"zh_cn", "en_us"})
    @DisplayName("头像说明签取的那一段（notesUnder）：八个人每人都取得到、不串到下一个人、列表项换成「· 」、不留 ** —— 中英各查")
    void rosterNotesForEveryCharacter(String lang) {
        String src = src(lang);
        List<String> ids = facts(lang).roster().characters().stream().map(s -> s.id().value()).toList();
        for (String id : ids) {
            List<String> notes = Rulebook.notesUnder(src, "roster", id);
            assertFalse(notes.isEmpty(), lang + "：" + id + " 底下没取到一行 —— 说明签会是空的");
            for (String line : notes) {
                assertFalse(line.startsWith("@") || line.startsWith("#") || line.startsWith("- ") || line.contains("**"),
                        lang + "：" + id + " 取到了不该有的一行：" + line);
            }
        }
        // 不串：每个人取到的第一行，在别人那一段里不出现（取过头的话，上一个人的段落会把下一个人的头一行吞进来）
        for (String id : ids) {
            String first = Rulebook.notesUnder(src, "roster", id).getFirst();
            for (String other : ids) {
                if (!other.equals(id)) {
                    assertFalse(Rulebook.notesUnder(src, "roster", other).contains(first),
                            lang + "：" + other + " 那一段串进了 " + id + " 的「" + first + "」");
                }
            }
        }
        // 正向对照：一个不存在的 id 必须取不到 —— 否则「取到了」可能只是取到了别的东西
        assertTrue(Rulebook.notesUnder(src, "roster", "nobody_here").isEmpty());
        // 列表项真的换成了「· 」：陪酒女那一段是列表（中英两份书都是）
        assertTrue(Rulebook.notesUnder(src, "roster", "hostess").stream().anyMatch(l -> l.startsWith("· ")),
                lang + "：陪酒女那一段的列表项没换成「· 」");
    }

    /** 章名进 HTML 前转义过，比对时照同一规则转义。 */
    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
