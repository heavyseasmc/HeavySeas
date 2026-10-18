package io.github.heavyseasmc.engine.seat;

import io.github.heavyseasmc.engine.model.CharacterId;

import java.util.Objects;

/**
 * 送一张牌（规则第五章「送牌」，不占行动）：手里的进对方手里，面前的进对方面前、照样亮着。
 *
 * @param card      哪一张
 * @param fromFront 从面前送（{@code false} = 从手里）
 * @param to        送给谁
 */
public record Gift(String card, boolean fromFront, CharacterId to) {
    public Gift {
        Objects.requireNonNull(card, "card");
        Objects.requireNonNull(to, "to");
    }
}
