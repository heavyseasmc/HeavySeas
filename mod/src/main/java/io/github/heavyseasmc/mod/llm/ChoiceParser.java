package io.github.heavyseasmc.mod.llm;

import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把模型的回答认成一个选项编号 —— <b>只认光秃秃的一个整数</b>，外面可以包空白与标点。
 *
 * <p>判据取严（TLM 的同一条：「只回一个词，认不出就当 NONE」）：「我选 3」「Option 3」「3 或 4」「三」一律认不出。
 * 认错的代价不对称 —— 认不出只是这一窗走默认，认错了是替身当着真人做了一件它没打算做的事。
 * 这里只报「认不出」；要不要再问一遍、来不来得及，归 {@link LlmService}（只再问一遍，见它的类注释）。
 *
 * <ul>
 *   <li>先做 NFKC：全角的「３」「（３）」与半角一样认。</li>
 *   <li>去掉 {@code <think>…</think>}（有的本地模型把思考直接写进回答；没收尾的那一段整个丢掉）。
 *       只有收尾标签的（开标签在服务端的提示词模板里，回答里只剩「思考……{@code </think>}答案」）：取最后一个 {@code </think>} 之后的文字
 *       （审查 2026-10-07 U12：原先这种回答一律认不出，每个决定都退回动脑）。</li>
 *   <li>负号算数字的一部分：「-1」是越界，不是「1」。</li>
 * </ul>
 */
public final class ChoiceParser {

    private ChoiceParser() {
    }

    /** 认的结果：{@code number} 是从 1 数的编号（只在 {@link Kind#OK} 时有意义）。 */
    public record Result(Kind kind, int number) {
        public enum Kind { OK, UNPARSEABLE, OUT_OF_RANGE }
    }

    /** 包在数字外面、可以不管的字：空白、各种引号括号、句末标点、markdown 的 * 与 _、编号前的 #。 */
    private static final String WRAP = "[\\s\"'`*_#()\\[\\]{}<>.,:;!?。、「」『』【】〈〉《》‘’“”]*";
    private static final Pattern BARE = Pattern.compile("^" + WRAP + "(-?\\d+)" + WRAP + "$");
    private static final Pattern THINK = Pattern.compile("(?s)<think>.*?</think>");
    private static final String THINK_END = "</think>";

    /** 回答里真正要认的那一截：去掉思考、去掉首尾空白。 */
    public static String visible(String answer) {
        if (answer == null) {
            return "";
        }
        String text = THINK.matcher(answer).replaceAll("");
        int close = text.lastIndexOf(THINK_END);
        if (close >= 0) {
            text = text.substring(close + THINK_END.length());   // 只剩收尾标签：它前面全是思考
        }
        int open = text.indexOf("<think>");
        if (open >= 0) {
            text = text.substring(0, open);       // 思考没写完就被 max_tokens 截断了：后面全是思考
        }
        return text.strip();
    }

    /**
     * @param answer  模型回的 {@code content}
     * @param options 选项有几个（编号 1…options）
     */
    public static Result parse(String answer, int options) {
        String text = Normalizer.normalize(visible(answer), Normalizer.Form.NFKC);
        Matcher m = BARE.matcher(text);
        if (!m.matches()) {
            return new Result(Result.Kind.UNPARSEABLE, 0);
        }
        String digits = m.group(1);
        if (digits.startsWith("-") || digits.replaceFirst("^0+(?=\\d)", "").length() > 9) {
            return new Result(Result.Kind.OUT_OF_RANGE, 0);
        }
        int n = Integer.parseInt(digits);
        if (n < 1 || n > options) {
            return new Result(Result.Kind.OUT_OF_RANGE, n);
        }
        return new Result(Result.Kind.OK, n);
    }
}
