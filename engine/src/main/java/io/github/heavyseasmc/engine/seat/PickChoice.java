package io.github.heavyseasmc.engine.seat;

import java.util.Objects;

/**
 * 抢到手之后挑哪一张：指名他面前的某一张，或者从他手里随机摸一张（规则第七章「抢」）。
 *
 * <p>❗摸手牌<b>只选「摸」，不选摸哪一张</b>：手牌是暗的，摸到哪张由驱动者用这一局的随机流定（规则：随机摸一张）。
 */
public sealed interface PickChoice {

    /** 从他手里随机摸一张。 */
    PickChoice FROM_HAND = new FromHand();

    record FromHand() implements PickChoice {
    }

    /** 指名拿他面前的 {@code card}。 */
    record FromFront(String card) implements PickChoice {
        public FromFront {
            Objects.requireNonNull(card, "card");
        }
    }
}
