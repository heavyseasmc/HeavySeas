package io.github.heavyseasmc.engine.seat;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.play.Contest;

import java.util.Objects;
import java.util.Optional;

/**
 * 轮到我行动时的一件事（规则第七章「五件事」）。
 *
 * <p>值语义（记录）：驱动者拿策略交回来的那一项去合法清单里 {@code contains}，对不上就退回默认 ——
 * 所以两项「是同一件事」必须等于 {@code equals}。
 */
public sealed interface ActionChoice {

    /** 什么也不做。 */
    ActionChoice PASS = new Pass();

    /** 划船。 */
    ActionChoice ROW = new Row();

    record Pass() implements ActionChoice {
    }

    record Row() implements ActionChoice {
    }

    /** 换座位或抢（{@link Contest.Kind#SWAP} / {@link Contest.Kind#STEAL}），对着 {@code target}。 */
    record Declare(Contest.Kind kind, CharacterId target) implements ActionChoice {
        public Declare {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(target, "target");
            if (kind == Contest.Kind.RATION) {
                throw new IllegalArgumentException("分食是打出一张牌（Play），不是指人");
            }
        }
    }

    /**
     * 用物资：花掉这一天的行动打出 {@code card}。只有医疗箱要指人（{@code target}），其余为空。
     */
    record Play(String card, Optional<CharacterId> target) implements ActionChoice {
        public Play {
            Objects.requireNonNull(card, "card");
            Objects.requireNonNull(target, "target");
        }
    }
}
