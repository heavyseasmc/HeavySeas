package io.github.heavyseasmc.mod.rulebook;

import java.util.ArrayList;
import java.util.List;

/**
 * 书页折行：给定每个字多宽、一行最宽多少，在哪儿断行（2026-10-13 真机验收：书里「被指的人有 20」后断行、
 * 一行以逗号开头、单独一行「。」）。
 *
 * <p>Minecraft 自带的折行只认空格：一行里有空格就断在最后一个空格，没有才按字断 —— 对中文两头都错：
 * 「有 20 秒」会断在「20」与「秒」之间，而按字断不管标点，句号可以单独落到下一行行首。这里按中文排版的避头尾来：
 * <ul>
 *   <li>能断的地方：空格之后；两边有一个是中日文字（汉字、假名、全角标点）的字缝。拉丁字母与数字连着的一串是一个词，不拆。</li>
 *   <li>行首不许出现收尾标点（，。、；：？！）」』】》… 与半角的 ,.;:?!)] 等）；行尾不许留开头标点（（「『【《 等）。</li>
 *   <li>数字与紧跟其后的字不拆：「20 秒」「20秒」里数字后面那个缝不断。</li>
 *   <li>一行里一个能断的地方都没有（一个超长的词）才按字硬断，硬断也先让开行首禁则字。</li>
 * </ul>
 *
 * <p>❗不碰 Minecraft 的类：字宽由调用方量好传进来（{@code RulebookBook} 按当前字体一个字一个字量），单测用定宽。
 */
public final class LineBreak {

    private LineBreak() {
    }

    /** 一行：原文里的 {@code [start, end)}（按 {@code char} 下标，已去掉行首行尾的空格）。 */
    public record Line(int start, int end) {
    }

    /** 行首不许出现的字。 */
    static final String NO_START = "，。、；：？！）」』】》〉〕｝］”’…‥·・％ー々〜～,.;:?!)]}%";
    /** 行尾不许留的字。 */
    static final String NO_END = "（「『【《〈〔｛［“‘([{";

    /**
     * @param text   一段文字（不含换行）
     * @param widths 每个 {@code char} 的宽度（与 {@code text} 等长；代理对的低位给 0）
     * @param max    一行最宽多少
     */
    public static List<Line> wrap(String text, float[] widths, float max) {
        return wrap(text, widths, max, null);
    }

    /**
     * 同 {@link #wrap(String, float[], float)}，另给一份「这个字前面不许断」：航海日志里人名、牌名是一个整体
     * （2026-10-13 第二轮验收 50：「船/长」被拆到两行）。整段都是一个名字、一行放不下时照样按字硬断 —— 日志一个字都不能丢。
     *
     * @param glued 与 {@code text} 等长；{@code glued[i]} 为真 = 第 {@code i} 个字与前一个字粘在一起。{@code null} = 没有
     */
    public static List<Line> wrap(String text, float[] widths, float max, boolean[] glued) {
        if (glued != null && glued.length != text.length()) {
            throw new IllegalArgumentException("粘连表要与原文等长：" + glued.length + " ≠ " + text.length());
        }
        if (widths.length != text.length()) {
            throw new IllegalArgumentException("每个字都要有宽度：" + widths.length + " ≠ " + text.length());
        }
        List<Line> lines = new ArrayList<>();
        int n = text.length();
        int start = skipSpaces(text, 0);
        while (start < n) {
            float w = 0;
            int lastBreak = -1;              // 最后一个能断的地方（下一行从这里起）
            int i = start;
            boolean overflow = false;
            for (; i < n; i++) {
                if (i > start && canBreakBefore(text, i) && (glued == null || !glued[i])) {
                    lastBreak = i;
                }
                float next = w + widths[i];
                // 行尾的空格不算宽：它会被去掉
                if (next > max && !Character.isWhitespace(text.charAt(i)) && i > start) {
                    overflow = true;
                    break;
                }
                w = next;
            }
            if (!overflow) {
                lines.add(new Line(start, trimEnd(text, start, n)));
                break;
            }
            int cut;
            if (lastBreak > start) {
                cut = lastBreak;
            } else {
                // 一个能断的地方都没有：硬断在放得下的最后一个字之后，但先让开行首禁则字（往回让，至少留一个字）
                cut = i;
                while (cut - 1 > start && (NO_START.indexOf(text.charAt(cut)) >= 0
                        || NO_END.indexOf(text.charAt(cut - 1)) >= 0 || Character.isLowSurrogate(text.charAt(cut)))) {
                    cut--;
                }
            }
            lines.add(new Line(start, trimEnd(text, start, cut)));
            start = skipSpaces(text, cut);
        }
        return lines;
    }

    /**
     * 同 {@link #wrap(String, float[], float, boolean[])}，再让末行不只剩一个字（2026-10-13 第二轮验收 50：日志末行只剩一个「人」）：
     * 末行只有一个字（行首禁则的标点不算字）时，把上一行最后那个能断的地方往前挪一处，让末行至少两个字 ——
     * 挪了之后末行放不下就不挪。
     */
    public static List<Line> wrapNoWidow(String text, float[] widths, float max, boolean[] glued) {
        List<Line> lines = wrap(text, widths, max, glued);
        if (lines.size() < 2) {
            return lines;
        }
        Line last = lines.get(lines.size() - 1);
        Line prev = lines.get(lines.size() - 2);
        if (letters(text, last) != 1) {
            return lines;
        }
        for (int b = prev.end() - 1; b > prev.start() + 1; b--) {
            if (!canBreakBefore(text, b) || glued != null && glued[b]) {
                continue;
            }
            int from = skipSpaces(text, b);
            float w = 0;
            for (int i = from; i < last.end(); i++) {
                w += widths[i];
            }
            if (w > max) {
                break;                       // 再往前挪只会更宽
            }
            List<Line> out = new ArrayList<>(lines.subList(0, lines.size() - 2));
            out.add(new Line(prev.start(), trimEnd(text, prev.start(), b)));
            out.add(new Line(from, last.end()));
            return out;
        }
        return lines;
    }

    /** 一行里有几个「字」：行首禁则的标点与空格不算。 */
    private static int letters(String text, Line line) {
        int n = 0;
        for (int i = line.start(); i < line.end(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c) && NO_START.indexOf(c) < 0 && !Character.isLowSurrogate(c)) {
                n++;
            }
        }
        return n;
    }

    /** 下一行能不能从第 {@code i} 个字起。 */
    static boolean canBreakBefore(String text, int i) {
        char prev = text.charAt(i - 1);
        char cur = text.charAt(i);
        if (Character.isLowSurrogate(cur)) {
            return false;
        }
        if (Character.isWhitespace(cur)) {
            return false;                   // 空格跟着上一行走，断在空格之后
        }
        if (NO_START.indexOf(cur) >= 0 || NO_END.indexOf(prev) >= 0) {
            return false;
        }
        if (Character.isWhitespace(prev)) {
            // 空格之后：数字与后面的字不拆（「20 秒」）
            int before = i - 1;
            while (before > 0 && Character.isWhitespace(text.charAt(before))) {
                before--;
            }
            return !(Character.isDigit(text.charAt(before)) && cjk(cur));
        }
        if (Character.isDigit(prev) && cjk(cur)) {
            return false;                   // 「20秒」
        }
        return cjk(prev) || cjk(cur);
    }

    /** 中日文字：汉字、假名、全角标点与符号 —— 字与字之间本来就能断。 */
    static boolean cjk(char c) {
        Character.UnicodeBlock b = Character.UnicodeBlock.of(c);
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || b == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || b == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || b == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || b == Character.UnicodeBlock.HIRAGANA
                || b == Character.UnicodeBlock.KATAKANA
                || b == Character.UnicodeBlock.GENERAL_PUNCTUATION && (c == '“' || c == '”' || c == '‘' || c == '’'
                || c == '…' || c == '—');
    }

    private static int skipSpaces(String text, int from) {
        int i = from;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int trimEnd(String text, int start, int end) {
        int e = end;
        while (e > start && Character.isWhitespace(text.charAt(e - 1))) {
            e--;
        }
        return e;
    }
}
