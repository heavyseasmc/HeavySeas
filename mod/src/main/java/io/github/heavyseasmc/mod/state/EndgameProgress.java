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
 * 翻恨一轮 → 翻爱一轮 → 计分面板。每一轮按 {@link #order} 逐个翻完全部角色。
 *
 * @param outcome  这一局怎么结束的
 * @param turn     结束在第几回合
 * @param alive    终局时还活着几个人
 * @param order    翻牌次序：最终总分从低到高；同分保持终局座位序，<b>含被移出游戏的人</b>
 * @param stage    正在哪一段
 * @param flipped  这一轮已经翻开了几张（翻的是 {@code order} 的前 {@code flipped} 个）
 * @param scores   四项计分，终局一开始就算好 —— 翻牌不改变任何人的分
 */
public record EndgameProgress(GameState.Outcome outcome, int turn, int alive, List<CharacterId> order,
                              Stage stage, int flipped, Map<CharacterId, ScoreSheet> scores) {

    /** M4's shore approach, followed by the two reveal rounds and scores. */
    public enum Stage {
        /** The fourth gull leads the occupied boat toward the newly visible shore. */
        ARRIVAL,
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
        // ARRIVAL 是一段停顿、不计数（ADR-0034 §5.3.2：船不动，没有「挪了几步」）；翻牌两轮里它是张数。
        // ❗这里原先是字面量 8，与 EndgamePhase 那份 ARRIVAL_STEPS 不同源（ADR-0032 #7）；两处一起消失了。
        //   协作者 965e383 把那个 8 收成 EndgameProgress.ARRIVAL_STEPS，与这里的「不计数」撞上，按本版为准。
        int maximum = stage == Stage.ARRIVAL ? 0 : order.size();
        if (flipped < 0 || flipped > maximum) {
            throw new IllegalArgumentException("翻开张数越界：%d（共 %d 人）".formatted(flipped, order.size()));
        }
        if (!scores.keySet().containsAll(order)) {
            throw new IllegalArgumentException("有人没有算分：翻牌 %s，计分 %s".formatted(order, scores.keySet()));
        }
    }

    public EndgameProgress withFlipped(int n) {
        return new EndgameProgress(outcome, turn, alive, order, stage, n, scores);
    }

    /** 最高分可以并列；并列者都使用胜者揭牌演出。 */
    public boolean isWinner(CharacterId id) {
        int best = order.stream().map(scores::get).mapToInt(ScoreSheet::total).max().orElseThrow();
        ScoreSheet score = scores.get(Objects.requireNonNull(id, "id"));
        return score != null && score.total() == best;
    }

    /** 下一段：恨 → 爱 → 计分。翻开张数清零。 */
    public EndgameProgress nextStage() {
        Stage next = switch (stage) {
            case ARRIVAL -> Stage.HATE;
            case HATE -> Stage.LOVE;
            case LOVE, SCORES -> Stage.SCORES;
        };
        return new EndgameProgress(outcome, turn, alive, order, next, 0, scores);
    }
}
