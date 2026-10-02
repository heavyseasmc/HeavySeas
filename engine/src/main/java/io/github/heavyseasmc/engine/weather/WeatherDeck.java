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
    /** {@link #stackNext} 定下的下一张（id）；翻出来就清。 */
    private String stacked;

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
        if (stacked != null) {
            String want = stacked;
            stacked = null;
            // 定下时核对过摸得到；摸不到说明那道核对写错了 —— 当场说，不悄悄翻别的牌
            if (!moveToFront(want)) {
                throw new IllegalStateException("下一张天候定为 " + want + "，翻牌时牌堆里却没有它");
            }
        }
        current = pile.removeFirst();
        return current;
    }

    /**
     * 调试口（ADR-0060，{@code /seas debug weather next}）：下一次 {@link #draw()} 翻出指定的那一张。
     *
     * <p><b>只调顺序，不造牌</b>：那一张必须是下一次翻牌时摸得到的 —— 牌堆里有它；牌堆已空时，下一次翻牌会先把
     * 弃牌堆连同今天这张洗回来，那就在那几张里找。翻不出就什么也不改，返回 {@code false}。
     * 记下 id 而不只是挪一下：挪完之后若有人把弃牌堆洗回牌库（晴空），翻牌那一刻还会再挪到顶上。
     *
     * @return 下一次翻得出这一张吗
     */
    public boolean stackNext(String id) {
        Objects.requireNonNull(id, "id");
        if (!pile.isEmpty()) {
            if (!moveToFront(id)) {
                return false;
            }
        } else {
            boolean returning = discard.stream().anyMatch(card -> card.id().equals(id))
                    || (current != null && current.id().equals(id));
            if (!returning) {
                return false;
            }
        }
        stacked = id;
        return true;
    }

    private boolean moveToFront(String id) {
        for (int i = 0; i < pile.size(); i++) {
            if (pile.get(i).id().equals(id)) {
                pile.addFirst(pile.remove(i));
                return true;
            }
        }
        return false;
    }

    /** 牌堆里还没翻的那几张，顶上的在前。只读的一份拷贝（调试查看与导出用）。 */
    public List<WeatherCard> upcoming() {
        return List.copyOf(pile);
    }

    /** 弃牌堆，先进的在前。只读的一份拷贝。 */
    public List<WeatherCard> discardedCards() {
        return List.copyOf(discard);
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
