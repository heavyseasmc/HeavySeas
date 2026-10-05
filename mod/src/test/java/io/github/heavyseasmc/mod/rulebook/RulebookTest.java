package io.github.heavyseasmc.mod.rulebook;

import io.github.heavyseasmc.engine.model.Provision;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.weather.WeatherCard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
 */
class RulebookTest {

    private static final List<Rulebook.KeyLine> KEYS = List.of(new Rulebook.KeyLine("查看手牌", "R"));

    private static Rulebook.Facts facts(String lang) {
        Map<String, String> strings = RulebookData.langFile(lang);
        return RulebookData.facts(strings::get, KEYS);
    }

    private static String zh() {
        return RulebookData.source("zh_cn").orElseThrow(() -> new AssertionError("jar 里没有 rulebook/zh_cn.md —— 没在测，不是通过"));
    }

    @Test
    @DisplayName("中文那份整本展开得了：扉页开头、十五章加附录")
    void chineseExpands() {
        List<Rulebook.Block> blocks = Rulebook.parse(zh(), facts("zh_cn"));
        assertInstanceOf(Rulebook.TitlePage.class, blocks.getFirst());
        long chapters = blocks.stream().filter(b -> b instanceof Rulebook.Chapter).count();
        assertEquals(16, chapters, "十五章 + 附");
        System.out.println("规则书 zh_cn：" + blocks.size() + " 块，" + chapters + " 章");
    }

    @Test
    @DisplayName("生成的那几段换成 en_us 的 lang 也展开得了 —— 两份 lang 都有书要的每一个键")
    void generatedPartsExpandInEnglish() {
        assertDoesNotThrow(() -> Rulebook.parse(zh(), facts("en_us")));
    }

    @Test
    @DisplayName("❗数据里的每个角色、每种物资、每张天候在书里各恰好一次 —— 新加一张牌而书没跟上，这里红")
    void everyEntityWrittenOnce() {
        String src = zh();
        Rulebook.Facts f = facts("zh_cn");
        assertExactlyOnce(src, "roster", f.roster().characters().stream().map(s -> s.id().value()).toList());
        assertExactlyOnce(src, "provision", f.provisions().all().stream().map(Provision::id).toList());
        assertExactlyOnce(src, "weather", f.weather().stream().map(WeatherCard::id).toList());
    }

    private static void assertExactlyOnce(String src, String directive, List<String> expected) {
        List<String> written = Rulebook.directiveIds(src, directive);
        assertFalse(expected.isEmpty(), "数据里一个 " + directive + " 都没读到 —— 判据坏了");
        Set<String> dup = written.stream().filter(id -> Collections.frequency(written, id) > 1)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> missing = new TreeSet<>(expected);
        missing.removeAll(written);
        Set<String> extra = new TreeSet<>(written);
        expected.forEach(extra::remove);
        assertEquals(Set.of(), missing, "@" + directive + " 书里没写到");
        assertEquals(Set.of(), extra, "@" + directive + " 书里写了数据里没有的");
        assertEquals(Set.of(), dup, "@" + directive + " 写了两遍");
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

    @Test
    @DisplayName("网页版与书同一串块：每一章都进了目录")
    void htmlHasEveryChapter() {
        String html = RulebookHtml.render("zh_cn", zh());
        List<Rulebook.Block> blocks = Rulebook.parse(zh(), facts("zh_cn"));
        blocks.stream().filter(b -> b instanceof Rulebook.Chapter).map(b -> ((Rulebook.Chapter) b).title())
                .forEach(t -> assertTrue(html.contains(">" + t + "</a>"), "目录里缺 " + t));
        assertTrue(html.indexOf("<nav>") < html.indexOf("<h2"), "目录在正文之前");
    }
}
