package io.github.heavyseasmc.mod.rulebook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 规则签（ADR-0043 §11 第六组）按锚点从书里切一节：{@link Rulebook#section}。
 */
class RulebookSectionTest {

    /**
     * 各面的 {@code rulesAnchor()} / {@code rulesAnchors()} 用到的锚点（client 包里那几处 {@code return "…"}）。
     * 签上找不到会退回打开整本书并报一行 —— 这里在构建时先挡一道。设置一面的 {@code contents} 是目录，不在这张表里。
     */
    private static final List<String> FACE_ANCHORS = List.of("actions", "cards", "navigation-pick", "steal", "provisions",
            "healing", "row", "taking-sides", "thirst", "weapons", "consent", "overboard", "section-39");

    private static List<Rulebook.Block> book(String lang) {
        Map<String, String> strings = RulebookData.langFile(lang);
        String src = RulebookData.source(lang).orElseThrow(() -> new AssertionError("jar 里没有 rulebook/" + lang + ".md"));
        return Rulebook.parse(src, RulebookData.facts(strings::get, List.of(new Rulebook.KeyLine("查看手牌", "R"))));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"zh_cn", "en_us"})
    @DisplayName("各面用到的锚点在中英两本书里都切得出一节，而且不是空的")
    void everyFaceAnchorResolves(String lang) {
        List<Rulebook.Block> blocks = book(lang);
        for (String anchor : FACE_ANCHORS) {
            var section = Rulebook.section(blocks, anchor);
            assertTrue(section.isPresent(), lang + " 里没有锚点 " + anchor);
            assertTrue(section.get().blocks().size() >= 2, lang + " 的 " + anchor + " 只有标题");
            assertFalse(section.get().chapter().isBlank(), anchor + " 没取到所在那一章");
        }
    }

    @Test
    @DisplayName("小节锚点：从那个标题切到下一个标题为止；章锚点：整一章；没有的锚点是空")
    void boundaries() {
        List<Rulebook.Block> blocks = book("zh_cn");
        var consent = Rulebook.section(blocks, "consent").orElseThrow();
        assertInstanceOf(Rulebook.Heading.class, consent.blocks().getFirst());
        assertEquals("consent", ((Rulebook.Heading) consent.blocks().getFirst()).anchor());
        assertEquals(1, consent.blocks().stream().filter(b -> b instanceof Rulebook.Heading).count(),
                "小节只切到下一个小节之前，不该把「站队」也带进来");
        assertTrue(consent.chapter().contains("打架"), consent.chapter());

        var fights = Rulebook.section(blocks, "fights").orElseThrow();
        assertInstanceOf(Rulebook.Chapter.class, fights.blocks().getFirst());
        assertTrue(fights.blocks().stream().filter(b -> b instanceof Rulebook.Heading).count() >= 4, "章锚点要带上整章的小节");
        assertTrue(fights.blocks().stream().skip(1).noneMatch(b -> b instanceof Rulebook.Chapter), "章锚点到下一章为止");

        assertTrue(Rulebook.section(blocks, "no-such-anchor").isEmpty());
    }
}
