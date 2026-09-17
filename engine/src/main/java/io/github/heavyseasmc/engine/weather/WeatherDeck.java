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
    private WeatherCard currentOverride;

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
        // 开发验收覆盖只活到下一次正式抽牌。它不进入弃牌堆，也不改变牌堆顺序。
        currentOverride = null;
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
        return Optional.ofNullable(currentOverride != null ? currentOverride : current);
    }

    /**
     * 临时覆盖桌面上的天候，供开发环境做客户端视觉验收。
     *
     * <p>覆盖牌不从牌堆取、不进弃牌堆；下一次 {@link #draw()} 自动清除覆盖并继续原来的随机序列。
     * 这样规则查询与 HUD 都能看到同一张牌，但验收夹具不会污染正式洗牌状态。
     */
    public void overrideCurrentUntilNextDraw(WeatherCard card) {
        currentOverride = Objects.requireNonNull(card, "card");
    }

    public int remaining() {
        return pile.size();
    }

    public int discarded() {
        return discard.size();
    }
}
