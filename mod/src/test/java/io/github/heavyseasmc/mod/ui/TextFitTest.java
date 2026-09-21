package io.github.heavyseasmc.mod.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「文字任何情况下不越界」（ADR-0037 §7.2）的机器那一半。
 *
 * <p>假字宽：汉字 = 字号，西文 ≈ 0.55 字号，空格 0.3 字号。真字宽在客户端里由字体给，这里只验规则本身 ——
 * 规则不依赖具体字宽，只依赖「每一步都量过」。
 */
class TextFitTest {

    private static final int[] SIZES = {24, 20, 18, 16, 14};

    private static final TextFit.Measure FAKE = (text, size) -> {
        int w = 0;
        for (int cp : text.codePoints().toArray()) {
            w += cp == ' ' ? Math.round(size * 0.3f) : cp < 0x2E80 ? Math.round(size * 0.55f) : size;
        }
        return w;
    };

    @Test
    @DisplayName("放得下就用想要的字号，一行")
    void fitsAtWantedSize() {
        TextFit.Result r = TextFit.fit("医疗箱", 200, 2, SIZES, FAKE);
        assertEquals(24, r.size());
        assertEquals(List.of("医疗箱"), texts(r));
        assertFalse(r.truncated());
    }

    @Test
    @DisplayName("先缩字号，缩到地板还放不下才折行")
    void shrinksBeforeWrapping() {
        assertEquals(16, TextFit.fit("大把钞票", 70, 2, SIZES, FAKE).size());          // 4 × 16 = 64 ≤ 70
        TextFit.Result wrapped = TextFit.fit("大把钞票", 50, 2, SIZES, FAKE);           // 4 × 14 = 56 > 50
        assertEquals(2, wrapped.lines().size());
        assertEquals(24, wrapped.size(), "折行时重新从想要的字号试起");
        assertFalse(wrapped.truncated());
    }

    @Test
    @DisplayName("段落先折行：折得下就保住想要的字号，不为了挤成一行把字缩小")
    void paragraphsWrapBeforeShrinking() {
        String note = "轮到珠宝商行动";                                                  // 7 × 24 = 168 > 120
        TextFit.Result label = TextFit.fit(note, 120, 3, SIZES, FAKE, TextFit.Policy.SHRINK_FIRST);
        TextFit.Result para = TextFit.fit(note, 120, 3, SIZES, FAKE, TextFit.Policy.WRAP_FIRST);
        assertEquals(16, label.size(), "标签：缩到 16 正好一行（7 × 16 = 112）");
        assertEquals(1, label.lines().size());
        assertEquals(24, para.size(), "段落：字号不动");
        assertEquals(2, para.lines().size());
        para.lines().forEach(l -> assertTrue(l.width() <= 120, l.text()));
    }

    @Test
    @DisplayName("西文在空格处折，不把词劈开")
    void wrapsLatinAtSpaces() {
        TextFit.Result r = TextFit.fit("Life Preserver", 80, 2, new int[] {16, 14}, FAKE);
        assertEquals(List.of("Life", "Preserver"), texts(r));
    }

    @Test
    @DisplayName("避头：句读不落在行首")
    void punctuationNeverStartsALine() {
        TextFit.Result r = TextFit.fit("治疗一人，回复体力。", 5 * 14, 3, new int[] {14}, FAKE);
        for (String line : texts(r)) {
            assertFalse("，。".contains(line.substring(0, 1)), "行首是句读：" + line);
        }
    }

    @Test
    @DisplayName("怎么都放不下：截断、末行补省略号，而且照样不越界")
    void truncatesWithEllipsis() {
        TextFit.Result r = TextFit.fit("这回合忽略各种原因造成的口渴", 60, 2, SIZES, FAKE);
        assertTrue(r.truncated());
        assertEquals(14, r.size());
        assertTrue(texts(r).get(r.lines().size() - 1).endsWith(TextFit.ELLIPSIS));
        r.lines().forEach(l -> assertTrue(l.width() <= 60, l.text()));
    }

    @Test
    @DisplayName("框窄到连省略号都放不下：一行都不出")
    void emptyWhenEvenEllipsisDoesNotFit() {
        TextFit.Result r = TextFit.fit("水", 5, 2, SIZES, FAKE);
        assertTrue(r.lines().isEmpty());
        assertTrue(r.truncated());
    }

    @Test
    @DisplayName("性质：几千组随机文本与框宽，每一行都不超框；没截断就一个字都不丢")
    void neverOverflows() {
        Random rng = new Random(20260921L);
        String pool = "水阳伞医疗箱朗姆酒短刀信号枪指南针大把钞票，。：Medical Kit Flare Gun life-preserver 0123 ";
        int[] cps = pool.codePoints().toArray();
        int checked = 0;
        for (int n = 0; n < 4000; n++) {
            StringBuilder sb = new StringBuilder();
            int len = 1 + rng.nextInt(28);
            for (int i = 0; i < len; i++) {
                sb.appendCodePoint(cps[rng.nextInt(cps.length)]);
            }
            String text = sb.toString();
            int box = rng.nextInt(260);
            int maxLines = 1 + rng.nextInt(3);
            TextFit.Policy policy = rng.nextBoolean() ? TextFit.Policy.SHRINK_FIRST : TextFit.Policy.WRAP_FIRST;
            TextFit.Result r = TextFit.fit(text, box, maxLines, SIZES, FAKE, policy);      // 两种策略都不许越界
            assertTrue(r.lines().size() <= maxLines, () -> "行数超了：" + text);
            for (TextFit.Line line : r.lines()) {
                assertEquals(FAKE.width(line.text(), r.size()), line.width(), "报的宽度要是量出来的宽度");
                assertTrue(line.width() <= box, () -> "越界：「" + line.text() + "」宽 " + line.width() + " > 框 " + box);
                checked++;
            }
            if (!r.truncated()) {
                assertEquals(squash(text), squash(String.join("", texts(r))), "没截断却丢了字");
            }
        }
        assertTrue(checked > 3000, "只核对了 " + checked + " 行 —— 没在查，不是没问题");
    }

    private static List<String> texts(TextFit.Result r) {
        return r.lines().stream().map(TextFit.Line::text).collect(Collectors.toList());
    }

    private static String squash(String s) {
        return s.replaceAll("\\s+", "");
    }
}
