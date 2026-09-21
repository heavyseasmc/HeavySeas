package io.github.heavyseasmc.mod.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 把一段字排进一个宽度固定的框里，<b>任何情况下每一行都不比框宽</b>（ADR-0037 §7.2：字越界就显得廉价）。
 *
 * <p>顺序是「缩 → 折 → 截」：先从想要的字号往下试，单行放得下就用；都放不下再从大到小试折行；
 * 连最小字号折满行数也放不下，才在最小字号上截断、末行补省略号。框窄到连省略号都放不下时一行都不出 ——
 * 空着也不越界。
 *
 * <p>这里不碰 Minecraft：宽度由调用方给的 {@link Measure} 量，所以整套规则能在单测里拿假字宽做性质测试。
 * 字宽不是拍的（证伪表：「字号按字数分三级」那次 31 张里 5 张戳出卡框），每一步都量。
 */
public final class TextFit {

    /** 省略号：截断时补在末行。 */
    public static final String ELLIPSIS = "…";

    /** 不能出现在行首的标点（避头）：折行时跟着前一个字走。 */
    private static final String NO_LINE_START = "，。、：；？！）」』》】…—·,.:;?!)]}%";

    /** 按某一级字号量一段字有多宽；单位由调用方定（客户端用物理像素）。 */
    @FunctionalInterface
    public interface Measure {
        int width(String text, int size);
    }

    /** 放不下时先动哪一样。 */
    public enum Policy {
        /** 先缩字号，缩到地板还放不下才折行：牌名、标签 —— 一行读完比字大重要。 */
        SHRINK_FIRST,
        /** 先折行，折满行数还放不下才缩：段落 —— 同一栏里字号忽大忽小比多一行难看得多。 */
        WRAP_FIRST
    }

    /** 排出来的一行及其宽度。 */
    public record Line(String text, int width) {
    }

    /**
     * @param size      最后用的字号
     * @param lines     各行；每一行的 {@code width} 都不超过框宽
     * @param truncated 是否截断过 —— 我们自己随仓库发的语言不许走到这一步（另有闸门）
     */
    public record Result(int size, List<Line> lines, boolean truncated) {
    }

    private TextFit() {
    }

    /**
     * @param sizes 候选字号，<b>从大到小</b>：第一个是想要的，最后一个是可读性的地板
     */
    public static Result fit(String text, int boxWidth, int maxLines, int[] sizes, Measure measure) {
        return fit(text, boxWidth, maxLines, sizes, measure, Policy.SHRINK_FIRST);
    }

    public static Result fit(String text, int boxWidth, int maxLines, int[] sizes, Measure measure, Policy policy) {
        if (sizes.length == 0 || maxLines < 1) {
            throw new IllegalArgumentException("至少要一级字号、一行");
        }
        for (int i = 1; i < sizes.length; i++) {
            if (sizes[i] >= sizes[i - 1]) {
                throw new IllegalArgumentException("字号要从大到小给");
            }
        }
        String clean = text.strip();
        int floor = sizes[sizes.length - 1];
        if (clean.isEmpty() || boxWidth <= 0) {
            return new Result(floor, List.of(), !clean.isEmpty());
        }
        if (policy == Policy.WRAP_FIRST && maxLines > 1) {
            for (int size : sizes) {
                int w = measure.width(clean, size);
                if (w <= boxWidth) {
                    return new Result(size, List.of(new Line(clean, w)), false);
                }
                List<String> wrapped = wrap(clean, boxWidth, size, measure);
                if (wrapped != null && wrapped.size() <= maxLines) {
                    return new Result(size, measured(wrapped, size, measure), false);
                }
            }
            return truncate(clean, boxWidth, maxLines, floor, measure);
        }
        for (int size : sizes) {
            int w = measure.width(clean, size);
            if (w <= boxWidth) {
                return new Result(size, List.of(new Line(clean, w)), false);
            }
        }
        if (maxLines > 1) {
            for (int size : sizes) {
                List<String> wrapped = wrap(clean, boxWidth, size, measure);
                if (wrapped != null && wrapped.size() <= maxLines) {
                    return new Result(size, measured(wrapped, size, measure), false);
                }
            }
        }
        return truncate(clean, boxWidth, maxLines, floor, measure);
    }

    /** 贪心折行；有一个词单独一行也放不下时返回 {@code null}（留给更小的字号或截断）。 */
    private static List<String> wrap(String text, int boxWidth, int size, Measure measure) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String token : tokens(text)) {
            boolean space = token.isBlank();
            if (line.isEmpty()) {
                if (space) {
                    continue;                                   // 行首不留空格
                }
                if (measure.width(token, size) > boxWidth) {
                    return null;
                }
                line.append(token);
                continue;
            }
            if (measure.width(line + token, size) <= boxWidth) {
                line.append(token);
                continue;
            }
            if (space) {
                continue;                                       // 放不下的空格正好是折行处，丢掉
            }
            lines.add(line.toString().stripTrailing());
            if (measure.width(token, size) > boxWidth) {
                return null;
            }
            line.setLength(0);
            line.append(token);
        }
        if (!line.isEmpty()) {
            lines.add(line.toString().stripTrailing());
        }
        return lines;
    }

    /**
     * 能折行的最小单位：一个汉字（连同紧跟着的避头标点）、一段空白，或一串连着的非汉字（一个西文词）。
     */
    static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            String ch = new String(Character.toChars(cp));
            if (NO_LINE_START.contains(ch) && (!word.isEmpty() || !out.isEmpty())) {
                if (!word.isEmpty()) {
                    word.append(ch);
                } else {
                    out.set(out.size() - 1, out.get(out.size() - 1) + ch);
                }
                continue;
            }
            if (Character.isWhitespace(cp)) {
                flush(out, word);
                if (out.isEmpty() || !out.get(out.size() - 1).isBlank()) {
                    out.add(" ");                               // 连着的空白并成一个
                }
            } else if (isWide(cp)) {
                flush(out, word);
                out.add(ch);
            } else {
                word.append(ch);
            }
        }
        flush(out, word);
        return out;
    }

    private static void flush(List<String> out, StringBuilder word) {
        if (!word.isEmpty()) {
            out.add(word.toString());
            word.setLength(0);
        }
    }

    /** 汉字、假名、谚文、全角标点：字与字之间随处可折。 */
    private static boolean isWide(int cp) {
        return (cp >= 0x2E80 && cp <= 0x9FFF) || (cp >= 0xAC00 && cp <= 0xD7AF)
                || (cp >= 0xF900 && cp <= 0xFAFF) || (cp >= 0xFF00 && cp <= 0xFFEF) || cp >= 0x20000;
    }

    /** 最后一招：最小字号，一个字符一个字符往下排，排满行数后末行砍到「字 + 省略号」放得下为止。 */
    private static Result truncate(String text, int boxWidth, int maxLines, int size, Measure measure) {
        if (measure.width(ELLIPSIS, size) > boxWidth) {
            return new Result(size, List.of(), true);           // 连省略号都放不下：空着，也不越界
        }
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            boolean last = lines.size() == maxLines - 1;
            String candidate = line + ch;
            boolean more = i + ch.length() < text.length();
            // 末行要给省略号留地方 —— 除非这就是全文最后一个字
            String probe = last && more ? candidate + ELLIPSIS : candidate;
            if (measure.width(probe, size) <= boxWidth) {
                line.append(ch);
                i += ch.length();
                continue;
            }
            if (last) {
                break;
            }
            if (line.isEmpty()) {
                return new Result(size, measured(lines, size, measure), true);   // 一个字都放不下
            }
            lines.add(line.toString().strip());
            line.setLength(0);
        }
        boolean cut = i < text.length();
        String tail = line.toString().stripTrailing() + (cut ? ELLIPSIS : "");
        if (!tail.isEmpty()) {
            lines.add(tail);
        }
        return new Result(size, measured(lines, size, measure), cut);
    }

    private static List<Line> measured(List<String> lines, int size, Measure measure) {
        List<Line> out = new ArrayList<>(lines.size());
        for (String l : lines) {
            out.add(new Line(l, measure.width(l, size)));
        }
        return List.copyOf(out);
    }
}
