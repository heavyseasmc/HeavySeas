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
        return Text.translatableWithFallback("heavyseas.character." + id, shown);
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
        Text note = Text.translatableWithFallback("heavyseas.command.fought", "%s vs %s", name("a", "Ann"),
                name("b", "Bo"));
        LogInk.Runs runs = LogInk.runs(note);
        assertEquals(note.getString(), runs.text());
        assertEquals("Ann vs Bo", runs.text());
        assertEquals("NNNPPPPNN", kinds(runs));
    }

    @Test
    @DisplayName("服务端整条标红时：牌名仍按牌名、其余按标的色；天候效果不算天候名")
    void styledMessageKeepsCardKind() {
        Text note = Text.translatableWithFallback("heavyseas.contest.stolen_front", "%s took %s",
                name("a", "Al"), Text.translatableWithFallback("heavyseas.provision.water", "Water"))
                .formatted(Formatting.RED);
        LogInk.Runs runs = LogInk.runs(note);
        assertEquals("Al took Water", runs.text());
        assertEquals("NNSSSSSSCCCCC", kinds(runs));
        assertEquals(0xFF5555, runs.styledRgb()[3]);

        Text weather = Text.translatableWithFallback("heavyseas.game.weather", "%s: %s",
                Text.translatableWithFallback("heavyseas.weather.gale", "Gale"),
                Text.translatableWithFallback("heavyseas.weather.effect.gale", "more"));
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
}
