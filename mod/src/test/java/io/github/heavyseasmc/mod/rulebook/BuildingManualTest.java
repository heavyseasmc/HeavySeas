package io.github.heavyseasmc.mod.rulebook;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 搭建手册（方案 A14）：两种语言都在、都解析得了、章对得上；手册叫人摆的那个结构真在 jar 里。 */
class BuildingManualTest {

    private static final String SAMPLES = "heavyseas:liner_samples";

    private static List<Rulebook.Block> parse(String lang) {
        String src = RulebookData.manualSource(lang).orElseThrow(() -> new AssertionError("jar 里没有 manual/" + lang + ".md"));
        return ShelfBook.parse(src);
    }

    private static List<String> chapters(List<Rulebook.Block> blocks) {
        return blocks.stream().filter(b -> b instanceof Rulebook.Chapter).map(b -> ((Rulebook.Chapter) b).title()).toList();
    }

    @Test
    @DisplayName("中英两份都以扉页开头，章数相同（至少 8 章）")
    void bothLanguagesMatch() {
        List<Rulebook.Block> zh = parse("zh_cn");
        List<Rulebook.Block> en = parse("en_us");
        assertInstanceOf(Rulebook.TitlePage.class, zh.getFirst());
        assertInstanceOf(Rulebook.TitlePage.class, en.getFirst());
        assertTrue(chapters(zh).size() >= 8, "中文只有 " + chapters(zh));
        assertEquals(chapters(zh).size(), chapters(en).size(), "中文 " + chapters(zh) + " · 英文 " + chapters(en));
    }

    @Test
    @DisplayName("手册叫人摆的样件区结构在 jar 里（data/heavyseas/structure/liner_samples.nbt）")
    void samplesStructureShips() {
        for (String lang : List.of("zh_cn", "en_us")) {
            assertTrue(RulebookData.manualSource(lang).orElseThrow().contains("/place template " + SAMPLES), lang + " 没写摆样件区的指令");
        }
        assertNotNull(BuildingManualTest.class.getResource("/data/heavyseas/structure/liner_samples.nbt"),
                "手册写了 " + SAMPLES + "，jar 里却没有那个结构");
    }
}
