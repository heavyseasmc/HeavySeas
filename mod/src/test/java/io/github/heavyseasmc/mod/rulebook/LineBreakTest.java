package io.github.heavyseasmc.mod.rulebook;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 书页避头尾（2026-10-13 真机验收：「被指的人有 20」后断行、一行以逗号开头、单独一行「。」）。
 * 定宽：汉字与全角标点 9、其余 6、空格 4 —— 与 Minecraft 默认字体量级相近，判据不依赖具体数。
 */
class LineBreakTest {

    private static float[] widths(String s) {
        float[] w = new float[s.length()];
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            w[i] = c == ' ' ? 4 : LineBreak.cjk(c) ? 9 : 6;
        }
        return w;
    }

    private static List<String> lines(String s, float max) {
        List<String> out = new ArrayList<>();
        for (LineBreak.Line l : LineBreak.wrap(s, widths(s), max)) {
            out.add(s.substring(l.start(), l.end()));
        }
        return out;
    }

    private static void assertKinsoku(List<String> lines, String whole, float max) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            assertFalse(line.isEmpty(), "空行：" + lines);
            if (i > 0) {
                assertTrue(LineBreak.NO_START.indexOf(line.charAt(0)) < 0, "行首是禁则字：" + lines);
            }
            assertTrue(LineBreak.NO_END.indexOf(line.charAt(line.length() - 1)) < 0 || i == lines.size() - 1,
                    "行尾留了开头标点：" + lines);
            float w = 0;
            for (float f : widths(line)) w += f;
            assertTrue(w <= max, "超宽 " + w + " > " + max + "：" + line);
        }
        assertEquals(whole.replace(" ", ""), String.join("", lines).replace(" ", ""), "丢字或多字");
    }

    @Test
    void numberStaysWithWhatFollowsAndCommaNeverStartsALine() {
        String s = "被指的人有 20 秒决定同不同意。时间到了，算他同意。";
        // 114 是书页宽；这一宽度下自带折行会断成「被指的人有 20」/「秒决定同不同意。时间到了」/「，算他同意。」
        List<String> lines = lines(s, 114);
        assertKinsoku(lines, s, 114);
        for (String line : lines) {
            assertFalse(line.endsWith("20"), "「20」与「秒」被拆开了：" + lines);
        }
    }

    @Test
    void lonePeriodPullsThePreviousCharacterDown() {
        String s = "已经行动过的人也能加入；跟这件事无关的人也能加入。";
        for (float max = 40; max <= 140; max += 1) {
            List<String> lines = lines(s, max);
            assertKinsoku(lines, s, max);
            for (String line : lines) {
                assertFalse(line.equals("。") || line.equals("；"), "单独一行标点（宽 " + max + "）：" + lines);
            }
        }
    }

    @Test
    void openingBracketNeverEndsALine() {
        String s = "这一回合结束时（不论谁先走）都要喝水。";
        for (float max = 40; max <= 140; max += 1) {
            assertKinsoku(lines(s, max), s, max);
        }
    }

    @Test
    void latinWordsBreakOnlyAtSpaces() {
        String s = "The person named has 20 seconds to decide. When time runs out, they agree.";
        List<String> lines = lines(s, 114);
        assertKinsoku(lines, s, 114);
        for (String line : lines) {
            for (String word : line.split(" ")) {
                assertTrue(s.contains(" " + word + " ") || s.startsWith(word + " ") || s.endsWith(" " + word)
                        || s.equals(word), "词被拆开：" + word + " in " + lines);
            }
        }
    }

    @Test
    void anOverlongWordIsHardBrokenWithoutLosingCharacters() {
        String s = "Supercalifragilisticexpialidocious";
        List<String> lines = lines(s, 60);
        assertKinsoku(lines, s, 60);
        assertTrue(lines.size() > 1);
    }

    @Test
    void mixedTextAllWidths() {
        String s = "站队：两边站定之前，船上任何清醒、在线、还没上场的人都可以加入一边（体型 3 以上）。";
        for (float max = 30; max <= 160; max += 1) {
            assertKinsoku(lines(s, max), s, max);
        }
    }

    /** 航海日志的折法（{@link LineBreak#wrapNoWidow}）：{@code names} 里每一段是一个名字，名字里的字缝不许断。 */
    private static List<String> logLines(String s, float max, String... names) {
        boolean[] glued = new boolean[s.length()];
        for (String name : names) {
            int at = s.indexOf(name);
            assertTrue(at >= 0, "样本里没有「" + name + "」");
            for (int i = at + 1; i < at + name.length(); i++) {
                glued[i] = true;
            }
        }
        List<String> out = new ArrayList<>();
        for (LineBreak.Line l : LineBreak.wrapNoWidow(s, widths(s), max, glued)) {
            out.add(s.substring(l.start(), l.end()));
        }
        return out;
    }

    @Test
    void logNamesStayWholeAtEveryWidth() {
        // 2026-10-13 第二轮验收 50：「船/长」被拆到两行
        String s = "收藏家从大副手里随机拿走一张，船长没有拦。";
        int checked = 0;
        for (float max = 40; max <= 200; max += 1) {
            List<String> lines = logLines(s, max, "收藏家", "大副", "船长");
            assertKinsoku(lines, s, max);
            for (String line : lines) {
                assertFalse(line.endsWith("船") || line.startsWith("长") || line.endsWith("大") || line.startsWith("副")
                        || line.endsWith("收") || line.endsWith("收藏") || line.startsWith("藏"),
                        "名字被拆开了（宽 " + max + "）：" + lines);
            }
            checked++;
        }
        assertTrue(checked > 100);
    }

    @Test
    void logLastLineIsNeverASingleCharacter() {
        // 2026-10-13 第二轮验收 50：末行只剩一个「人」；52：「化解 1 / 次。」数字与量词分家
        String s = "水手替陪酒女化解 1 次。救起了落水的人";
        for (float max = 40; max <= 200; max += 1) {
            List<String> lines = logLines(s, max, "水手", "陪酒女");
            assertKinsoku(lines, s, max);
            if (lines.size() > 1) {
                String last = lines.get(lines.size() - 1).replace("。", "");
                assertTrue(last.codePointCount(0, last.length()) > 1, "末行只剩一个字（宽 " + max + "）：" + lines);
            }
            for (String line : lines) {
                assertFalse(line.endsWith("1"), "「1」与「次」被拆开了（宽 " + max + "）：" + lines);
            }
        }
    }
}
