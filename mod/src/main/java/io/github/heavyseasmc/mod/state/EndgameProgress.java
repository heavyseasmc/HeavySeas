package io.github.heavyseasmc.mod.state;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.scoring.ScoreSheet;
import io.github.heavyseasmc.engine.state.GameState;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 终局序列走到哪了（ADR-0022）。服务端的运行时状态，<b>不持久化</b> —— 理由同补给箱与舵手那几项。
 *
 * <h2>三段</h2>
 * 翻恨一轮 → 翻爱一轮 → 计分面板。每一轮按 {@link #order} 逐个翻，<b>最后一张不翻</b>（决策 ⑪ 补丁二）：
 * 翻到只剩最后一人时停下，{@link #withheld} 置位，停一会儿再进下一段。
 *
 * @param outcome  这一局怎么结束的
 * @param turn     结束在第几回合
 * @param alive    终局时还活着几个人
 * @param order    翻牌次序：终局那一刻的座位序（船头 → 船尾），<b>含被移出游戏的人</b> ——
 *                 少翻一个，「最后一张靠置换推出来」就不成立了
 * @param stage    正在哪一段
 * @param flipped  这一轮已经翻开了几张（翻的是 {@code order} 的前 {@code flipped} 个）
 * @param withheld 这一轮已经走到最后一张、而且决定了不翻
 * @param scores   四项计分，终局一开始就算好 —— 翻牌不改变任何人的分
 */
public record EndgameProgress(GameState.Outcome outcome, int turn, int alive, List<CharacterId> order,
                              Stage stage, int flipped, boolean withheld, Map<CharacterId, ScoreSheet> scores) {

    /** 终局的三段。 */
    public enum Stage {
        /** 第一轮：揭「恨」。 */
        HATE,
        /** 第二轮：揭「爱」。两个置换各自独立，悬念完整重置。 */
        LOVE,
        /** 计分面板。 */
        SCORES
    }

    public EndgameProgress {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(stage, "stage");
        order = List.copyOf(Objects.requireNonNull(order, "order"));
        scores = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(scores, "scores")));
        if (order.isEmpty()) {
            throw new IllegalArgumentException("终局没有人可翻");
        }
        // 最多翻到倒数第二张：最后一张永远不翻。
        if (flipped < 0 || flipped > order.size() - 1) {
            throw new IllegalArgumentException("翻开张数越界：%d（共 %d 人，最后一张不翻）".formatted(flipped, order.size()));
        }
        if (!scores.keySet().containsAll(order)) {
            throw new IllegalArgumentException("有人没有算分：翻牌 %s，计分 %s".formatted(order, scores.keySet()));
        }
    }

    /** 这一轮的最后一个人（他的牌不翻）。 */
    public CharacterId last() {
        return order.get(order.size() - 1);
    }

    public EndgameProgress withFlipped(int n) {
        return new EndgameProgress(outcome, turn, alive, order, stage, n, withheld, scores);
    }

    public EndgameProgress withWithheld() {
        return new EndgameProgress(outcome, turn, alive, order, stage, flipped, true, scores);
    }

    /** 下一段：恨 → 爱 → 计分。翻开张数与「不翻」一起清零。 */
    public EndgameProgress nextStage() {
        Stage next = switch (stage) {
            case HATE -> Stage.LOVE;
            case LOVE, SCORES -> Stage.SCORES;
        };
        return new EndgameProgress(outcome, turn, alive, order, next, 0, false, scores);
    }
}
