package io.github.heavyseasmc.mod.client;

import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 航海日志的主题色（用户 2026-10-07：「日志看起来很费劲，行动或者牌的字应该有主题色」）：
 * 参数按自己的 lang 键认类别，排好的行按原文位置把颜色对回去。
 */
class LogInkTest {

    private static Text name(String id, String shown) {
        return Text.translatableWithFallback("heavyseas.character.logink_test_" + id, shown);
    }

    private static String kinds(LogInk.Runs runs) {
        StringBuilder out = new StringBuilder();
        for (LogInk.Kind kind : runs.kinds()) {
            out.append(kind.name().charAt(0));
        }
        return out.toString();
    }

    @Test
    @DisplayName("格式串里嵌的人名按人名着色，其余是普通字；排出来的字与 getString 一个字都不差")
    void namesInsideTranslatable() {
        // Test-only keys retain the production semantic prefixes without inheriting installed translations.
        // Bootstrap may load the real language before this test; the fixture must remain independent of that order.
        Text note = Text.translatableWithFallback("heavyseas.logink_test.fought", "%s vs %s", name("a", "Ann"),
                name("b", "Bo"));
        LogInk.Runs runs = LogInk.runs(note);
        assertEquals(note.getString(), runs.text());
        assertEquals("Ann vs Bo", runs.text());
        assertEquals("NNNPPPPNN", kinds(runs));
    }

    @Test
    @DisplayName("服务端整条标红时：牌名仍按牌名、其余按标的色；天候效果不算天候名")
    void styledMessageKeepsCardKind() {
        Text note = Text.translatableWithFallback("heavyseas.logink_test.stolen_front", "%s took %s",
                name("a", "Al"), Text.translatableWithFallback("heavyseas.provision.logink_test_water", "Water"))
                .formatted(Formatting.RED);
        LogInk.Runs runs = LogInk.runs(note);
        assertEquals("Al took Water", runs.text());
        assertEquals("NNSSSSSSCCCCC", kinds(runs));
        assertEquals(0xFF5555, runs.styledRgb()[3]);

        Text weather = Text.translatableWithFallback("heavyseas.logink_test.weather", "%s: %s",
                Text.translatableWithFallback("heavyseas.weather.logink_test_gale", "Gale"),
                Text.translatableWithFallback("heavyseas.weather.effect.logink_test_gale", "more"));
        assertEquals("WWWWPPPPPP", kinds(LogInk.runs(weather)));
    }

    @Test
    @DisplayName("折行丢掉的空白、并掉的连续空白、截断补上的省略号：颜色照样对得回原文的字")
    void colorsFollowWrappedLines() {
        String text = " ab  cd ef";
        int[] colors = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        int[][] got = GuiText.lineColors(text, colors, List.of("ab cd", "e…"));
        assertArrayEquals(new int[] {1, 2, 3, 5, 6}, got[0]);
        assertArrayEquals(new int[] {8, 8}, got[1]);
    }
    @Test
    @DisplayName("格式串里 §l…§r 标出的动词：记号剥掉、那几个字记为动词；参数里的人名照旧是人名、不算动词")
    void verbsMarkedInFormat() {
        Text note = Text.translatableWithFallback("heavyseas.logink_test.took",
                "%s §ltook§r %s", name("a", "Al"), name("b", "Bo"));
        LogInk.Runs runs = LogInk.runs(note);
        assertEquals("Al took Bo", runs.text());
        StringBuilder verbs = new StringBuilder();
        for (boolean v : runs.verbs()) {
            verbs.append(v ? 'V' : '.');
        }
        assertEquals("...VVVV...", verbs.toString());
        assertEquals("NNPPPPPPNN", kinds(runs));
    }

    @Test
    @DisplayName("人名 · 牌名里的字缝粘在一起（折行不拆名字，2026-10-13 第二轮验收 50「船/长」）；普通字与名字之间照常可断")
    void namesAreGlued() {
        Text note = Text.translatableWithFallback("heavyseas.logink_test.took",
                "%s §ltook§r %s", name("a", "Al"), name("b", "Bo"));
        LogInk.Runs runs = LogInk.runs(note);
        StringBuilder glue = new StringBuilder();
        for (boolean g : LogInk.glue(runs.kinds())) {
            glue.append(g ? 'G' : '.');
        }
        assertEquals("NNPPPPPPNN", kinds(runs), "正向对照：认出了两个名字");
        assertEquals(".G.......G", glue.toString());
    }

    @Test
    @DisplayName("没有记号的播报一个字都不算动词（旧播报、私信照旧）")
    void noMarkerNoVerb() {
        LogInk.Runs runs = LogInk.runs(Text.literal("plain line"));
        for (boolean v : runs.verbs()) {
            assertEquals(false, v);
        }
        assertEquals("plain line", runs.text());
    }
}
