package io.github.heavyseasmc.mod.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 规则摘录：每一种决定都拼得出、有上限；中英两本挑的是同样的节；节表过期了当场点名。
 */
class RulebookExcerptsTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"zh_cn", "en_us"})
    @DisplayName("每一种决定都有一份不空、不超上限的摘录（打印字数）")
    void everyKindHasABoundedExcerpt(String lang) {
        RulebookExcerpts excerpts = RulebookExcerpts.load(lang);
        int max = 0;
        for (DecisionKind kind : DecisionKind.values()) {
            String text = excerpts.excerpt(kind);
            assertNotNull(text, kind.name());
            assertFalse(text.isBlank(), kind.name());
            assertTrue(text.length() <= RulebookExcerpts.maxChars(lang), kind + "：" + text.length());
            max = Math.max(max, text.length());
            System.out.println("规则摘录 " + lang + " " + kind + "：" + text.length() + " 字");
        }
        System.out.println("规则摘录 " + lang + " 最长 " + max + " 字（上限 " + RulebookExcerpts.maxChars(lang) + "）");
    }

    @Test
    @DisplayName("中英挑的是同样的节：每一种决定，两边的章数与小节数一样")
    void chineseAndEnglishPickTheSameSections() {
        RulebookExcerpts zh = RulebookExcerpts.load("zh_cn");
        RulebookExcerpts en = RulebookExcerpts.load("en_us");
        for (DecisionKind kind : DecisionKind.values()) {
            assertEquals(count(zh.excerpt(kind), "# "), count(en.excerpt(kind), "# "), kind + " 的章数");
            assertEquals(count(zh.excerpt(kind), "## "), count(en.excerpt(kind), "## "), kind + " 的小节数");
        }
    }

    private static long count(String text, String prefix) {
        return text.lines().filter(l -> l.startsWith(prefix)).count();
    }

    @Test
    @DisplayName("共通的几节每一份都有（分怎么算、心里的两个人）；行动那一份带整个第七章；舵手那一份带航海牌")
    void expectedSectionsArePresent() {
        RulebookExcerpts zh = RulebookExcerpts.load("zh_cn");
        RulebookExcerpts en = RulebookExcerpts.load("en_us");
        for (DecisionKind kind : DecisionKind.values()) {
            assertTrue(zh.excerpt(kind).contains("## 计分") && zh.excerpt(kind).contains("# 第四章"), kind + " 缺共通的节");
            assertTrue(en.excerpt(kind).contains("## Scoring") && en.excerpt(kind).contains("# Chapter 4"), kind + " 缺共通的节（en）");
        }
        String action = zh.excerpt(DecisionKind.ACTION);
        for (String h : List.of("轮到谁", "五件事", "划船", "换座位", "抢", "指人", "用物资", "什么也不做", "不占行动的三件事")) {
            assertTrue(action.contains("## " + h + "\n"), "行动那一份缺「" + h + "」");
        }
        assertTrue(zh.excerpt(DecisionKind.HELM).contains("## 挑一张航海牌"));
        assertTrue(zh.excerpt(DecisionKind.PROVISION).contains("# 第十二章"), "补给箱那一份要带物资一览");
        assertTrue(zh.excerpt(DecisionKind.PROVISION).contains("**水**"), "物资一览要从数据展开（@provision）");
        assertFalse(zh.excerpt(DecisionKind.OTHER).contains("# 附"), "附录不进摘录");
    }

    @Test
    @DisplayName("❗节表过期（标题改了、章号错了、漏登记）→ 抛，带章号与标题 —— 不悄悄少一节")
    void staleSectionTableThrows() {
        Map<DecisionKind, List<RulebookExcerpts.Section>> renamed = new EnumMap<>(RulebookExcerpts.SECTIONS);
        renamed.put(DecisionKind.ACTION, List.of(new RulebookExcerpts.Section(7, "不存在的小节")));
        String msg = assertThrows(IllegalStateException.class, () -> RulebookExcerpts.load("en_us", renamed)).getMessage();
        assertTrue(msg.contains("第 7 章") && msg.contains("不存在的小节"), msg);

        Map<DecisionKind, List<RulebookExcerpts.Section>> noChapter = new EnumMap<>(RulebookExcerpts.SECTIONS);
        noChapter.put(DecisionKind.HELM, List.of(new RulebookExcerpts.Section(99, null)));
        msg = assertThrows(IllegalStateException.class, () -> RulebookExcerpts.load("zh_cn", noChapter)).getMessage();
        assertTrue(msg.contains("没有第 99 章"), msg);

        Map<DecisionKind, List<RulebookExcerpts.Section>> missing = new EnumMap<>(RulebookExcerpts.SECTIONS);
        missing.remove(DecisionKind.THIRST);
        msg = assertThrows(IllegalStateException.class, () -> RulebookExcerpts.load("zh_cn", missing)).getMessage();
        assertTrue(msg.contains("THIRST"), msg);
    }

    @Test
    @DisplayName("❗摘录超了上限（有人把整本书挂上去）→ 抛，不悄悄截断")
    void oversizedExcerptThrows() {
        Map<DecisionKind, List<RulebookExcerpts.Section>> wholeBook = new EnumMap<>(RulebookExcerpts.SECTIONS);
        wholeBook.put(DecisionKind.OTHER, java.util.stream.IntStream.rangeClosed(1, 15)
                .mapToObj(c -> new RulebookExcerpts.Section(c, null)).toList());
        String msg = assertThrows(IllegalStateException.class, () -> RulebookExcerpts.load("zh_cn", wholeBook)).getMessage();
        assertTrue(msg.contains("OTHER") && msg.contains("超过上限"), msg);
    }

    @Test
    @DisplayName("角色本事从书里取：小孩的抢法取得到，没有的 id 是空表")
    void characterNotes() {
        RulebookExcerpts zh = RulebookExcerpts.load("zh_cn");
        assertTrue(String.join("\n", zh.characterNotes("kid")).contains("只能拿手牌"), zh.characterNotes("kid").toString());
        assertEquals(List.of(), zh.characterNotes("nobody"));
    }
}
