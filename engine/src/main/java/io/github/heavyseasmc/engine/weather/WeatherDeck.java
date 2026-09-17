package io.github.heavyseasmc.engine.weather;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

/** 每日翻一张的天候牌堆；旧天候进弃牌堆，牌堆空时自动洗回。 */
public final class WeatherDeck {
    private final Random rng;
    private final List<WeatherCard> pile;
    private final List<WeatherCard> discard = new ArrayList<>();
    private WeatherCard current;

    public WeatherDeck(List<WeatherCard> cards, Random rng) {
        if (cards == null || cards.isEmpty()) {
            throw new IllegalArgumentException("天候牌堆不能为空");
        }
        this.rng = Objects.requireNonNull(rng, "rng");
        this.pile = new ArrayList<>(cards);
        Collections.shuffle(this.pile, rng);
    }

    /** 翻开今天的牌；昨天的牌先进入弃牌堆。 */
    public WeatherCard draw() {
        if (current != null) {
            discard.add(current);
        }
        if (pile.isEmpty()) {
            reshuffleDiscard();
        }
        current = pile.removeFirst();
        return current;
    }

    /** “晴空”：把弃牌堆洗回牌库；今天这张仍留在桌面上。 */
    public void reshuffleDiscard() {
        if (discard.isEmpty()) {
            return;
        }
        pile.addAll(discard);
        discard.clear();
        Collections.shuffle(pile, rng);
    }

    public Optional<WeatherCard> current() {
        return Optional.ofNullable(current);
    }

    public int remaining() {
        return pile.size();
    }

    public int discarded() {
        return discard.size();
    }
}
