package io.github.heavyseasmc.mod.llm;

import java.util.List;
import java.util.OptionalInt;

/**
 * 一次决定的结果：要么模型挑了第几项，要么「没有可用的选择 + 为什么」。{@link LlmService#choose} 的 future 永远正常完成成这个，
 * 从不异常完成。
 *
 * <p>调用方的写法只有一行 —— 没挑成就用算法替身自己的选择（不是「什么也不做」）：
 * <pre>{@code
 * int pick = outcome.choice().orElseGet(() -> 算法替身挑的那一项);
 * }</pre>
 *
 * @param index     挑中的选项（从 0 数，对 {@link ChoiceRequest#options()}）；没挑成是 -1
 * @param fallback  没挑成的原因；挑中了是 {@code null}
 * @param detail    一句给人看的说明（「HTTP 503 · 已试 3 次」「回答「我选三」认不出是一个编号」）；不含密钥
 * @param answer    模型最后一次回的原话（去掉思考、截到 200 字）；没收到回答是 {@code null}
 * @param latencyMs 从 {@code choose} 被调用到有结果
 * @param usage     这一次决定一共花的 token（几次尝试的回包加起来）
 * @param trail     每一次真正发出去的请求各落了个什么，按先后：{@code 503 → 503 → ok}（记号见 {@link #TOKENS}）；一次也没发是空表
 */
public record ChoiceOutcome(int index, Fallback fallback, String detail, String answer, long latencyMs, Usage usage,
                            List<String> trail) {

    /**
     * {@link #trail} 里的记号：HTTP 状态码（{@code 429} {@code 503} {@code 401} …）、{@code ok} 选中、{@code timeout} 单次超时、
     * {@code cut} 截止时还在等、{@code conn} 连不上 / 连接断了、{@code err200} 200 但回包是一条错误、{@code bad} 200 但回包认不出、
     * {@code empty} 空回答、{@code unparsed} 认不出编号、{@code range} 编号越界。
     */
    public static final String TOKENS = "状态码 · ok · timeout · cut · conn · err200 · bad · empty · unparsed · range";

    public ChoiceOutcome {
        trail = List.copyOf(trail);
    }

    /**
     * 没挑成的原因。每一种都是日志里那一行「路=退路:XXX」，数日志就数得出走了哪条路（ADR-0096 §6）。
     * 最后收成哪一种，看的是<b>最后一次</b>尝试落了个什么；前面几次在 {@link #trail} 里。
     */
    public enum Fallback {
        /** 配置关着（没有配置 · enabled 不是 true · 写坏了）：不发请求。 */
        DISABLED,
        /** 请求本身不合规矩（没有选项、选项太多……）：不发请求。 */
        INVALID_REQUEST,
        /** 排队的人太多（超过 maxQueued）：不发请求。 */
        QUEUE_FULL,
        /** 熔断着（连续失败太多次，还在冷却）：不发请求。 */
        CIRCUIT_OPEN,
        /** 到了截止时间还没有答案（排队没排到、每次都超时、剩下的预算不到地板）。 */
        TIMEOUT,
        /** 连不上、连接断了（重试过了或来不及重试）。 */
        NETWORK_ERROR,
        /** 429 · 5xx（重试过了或来不及重试），或者别的认不出的状态码。 */
        HTTP_ERROR,
        /** 401 / 403：密钥被拒。不重试；之后的决定也不再发，改好后 reload。 */
        AUTH,
        /** 402：余额不足。不重试；之后的决定也不再发，充值后 reload。 */
        BALANCE,
        /** 400 · 404 · 422 之类：请求本身被拒（参数、模型名、地址）。不重试。 */
        BAD_REQUEST,
        /** 回了 2xx，但回包不是认得的样子（不是 JSON、没有 choices、200 里装着一条错误）—— 重试过了。 */
        BAD_RESPONSE,
        /** 回答认不出是一个编号（含空回答）—— 已经再问过一遍。 */
        UNPARSEABLE,
        /** 是一个编号，但不在 1…选项数 里 —— 已经再问过一遍。 */
        OUT_OF_RANGE,
        /** 服务关了（服务端停了、重读了配置）：还没结果的一律收成这个。 */
        SHUTDOWN,
        /** 代码自己出了错：接住了，记一行，退回 —— 替身卡住比替身做错更糟。 */
        INTERNAL_ERROR
    }

    /** token 用量；不知道的记 -1（有的服务端不回 usage）。 */
    public record Usage(int prompt, int completion, int reasoning) {

        public static final Usage UNKNOWN = new Usage(-1, -1, -1);

        public boolean known() {
            return prompt >= 0 || completion >= 0;
        }

        /** 几次尝试的用量加起来：不知道的那一份不算。 */
        public Usage plus(Usage other) {
            if (!other.known()) {
                return this;
            }
            if (!known()) {
                return other;
            }
            return new Usage(sum(prompt, other.prompt), sum(completion, other.completion), sum(reasoning, other.reasoning));
        }

        private static int sum(int a, int b) {
            return a < 0 ? b : b < 0 ? a : a + b;
        }

        @Override
        public String toString() {
            if (!known()) {
                return "-";
            }
            return prompt + "+" + completion + (reasoning > 0 ? "（思考 " + reasoning + "）" : "");
        }
    }

    public boolean chosen() {
        return fallback == null;
    }

    /** 挑中的下标；没挑成是空 —— 调用方 {@code orElseGet} 接上自己的选择。 */
    public OptionalInt choice() {
        return chosen() ? OptionalInt.of(index) : OptionalInt.empty();
    }

    /** 真正发出去的请求数。 */
    public int attempts() {
        return trail.size();
    }

    /** {@code 503→503→ok}；一次也没发是 {@code -}。 */
    public String trailText() {
        return trail.isEmpty() ? "-" : String.join("→", trail);
    }
}
