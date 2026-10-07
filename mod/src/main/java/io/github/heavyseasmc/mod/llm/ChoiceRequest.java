package io.github.heavyseasmc.mod.llm;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次决定：交给 {@link LlmService#choose} 的全部东西。<b>不带历史</b> —— 每一次都是一条独立的请求（ADR-0096 §1.2：
 * TLM 实测，弱档模型只有在「空历史」时才肯好好照单子答）。
 *
 * @param seat      这一座叫什么（进提示词「你是：…」与日志；替身那一侧给角色名）
 * @param kind      哪一种决定：决定挑哪几节规则、问哪一句
 * @param options   编好次序的合法选项（提示词里从 1 编号；{@link ChoiceOutcome#index()} 从 0 数，对的是这张表）
 * @param situation 这一座此刻看得到的局面（调用方按收件人裁剪好；❗别把别的座位的手牌与爱恨放进来）
 * @param deadline  这一窗什么时候收（绝对时刻）。服务会再往前留 {@code deadlineMarginMs}，赶在这之前给出结果
 */
public record ChoiceRequest(String seat, DecisionKind kind, List<String> options, String situation, Instant deadline) {

    /** 一次最多给几个选项：再多就不是「挑一个」了，多半是调用方把整副牌摊了进来。 */
    public static final int MAX_OPTIONS = 40;

    public ChoiceRequest {
        // 不用 List.copyOf：它见 null 元素就抛，而「第几个选项是空的」该由 problem() 说成一句人话、回 INVALID_REQUEST
        options = options == null ? null : Collections.unmodifiableList(new ArrayList<>(options));
    }

    /** 哪里不合规矩（一句人话）；合规矩是 {@code null}。服务拿它直接回 {@code INVALID_REQUEST}，不抛。 */
    public String problem() {
        if (seat == null || seat.isBlank()) {
            return "没有座位名";
        }
        if (kind == null) {
            return "没有决定的种类";
        }
        if (deadline == null) {
            return "没有截止时间";
        }
        if (situation == null) {
            return "没有局面（可以是空串，不能是 null）";
        }
        if (options == null || options.isEmpty()) {
            return "一个选项都没有";
        }
        if (options.size() > MAX_OPTIONS) {
            return "选项有 " + options.size() + " 个，超过 " + MAX_OPTIONS;
        }
        for (int i = 0; i < options.size(); i++) {
            String option = options.get(i);
            if (option == null || option.isBlank()) {
                return "第 " + (i + 1) + " 个选项是空的";
            }
            if (option.contains("\n")) {
                return "第 " + (i + 1) + " 个选项里有换行（一个选项一行）";
            }
        }
        return null;
    }
}
