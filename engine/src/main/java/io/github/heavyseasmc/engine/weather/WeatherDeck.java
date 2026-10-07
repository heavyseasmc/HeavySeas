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

    private WeatherDeck(WeatherDeck other, Random rng) {
        this.rng = rng;
        this.pile = new ArrayList<>(other.pile);
        this.discard.addAll(other.discard);
        this.current = other.current;
        this.currentOverride = other.currentOverride;
        this.stacked = other.stacked;
    }

    /**
     * 一副一模一样、互不相干的副本，<b>连洗牌用的随机流也是一份副本</b>：副本以后洗回弃牌堆的次序与原来那一副相同，
     * 而在副本上洗牌不会推动原来那一副的随机流。
     *
     * @throws IllegalStateException 随机流复制不了（不是 {@code java.util.Random} 那种可序列化的）
     */
    public WeatherDeck copy() {
        return new WeatherDeck(this, cloneRandom(rng));
    }

    /**
     * 换一条随机流、换一个次序的副本（往前推演时用：看不见的次序与以后怎么洗，都重新抽过）。
     *
     * @param upcoming 牌堆里还没翻的那几张，新的次序；成员必须与原来的一样
     */
    public WeatherDeck copyWith(List<WeatherCard> upcoming, Random rng) {
        WeatherDeck copy = new WeatherDeck(this, Objects.requireNonNull(rng, "rng"));
        java.util.Map<WeatherCard, Integer> want = new java.util.HashMap<>();
        upcoming.forEach(c -> want.merge(c, 1, Integer::sum));
        java.util.Map<WeatherCard, Integer> have = new java.util.HashMap<>();
        pile.forEach(c -> have.merge(c, 1, Integer::sum));
        if (!want.equals(have)) {
            throw new IllegalArgumentException("重排的天候牌与牌堆里的不是同一批");
        }
        copy.pile.clear();
        copy.pile.addAll(upcoming);
        return copy;
    }

    private WeatherDeck(List<WeatherCard> upcoming, List<WeatherCard> discard, WeatherCard current, Random rng) {
        this.rng = rng;
        this.pile = new ArrayList<>(upcoming);
        this.discard.addAll(discard);
        this.current = current;
    }

    /**
     * 照给定的几样拼一副天候牌堆（推演时重拼一局用：还没翻的那几张的次序是重新抽过的）。
     *
     * @param upcoming 还没翻的，顶上的在前
     * @param discard  弃牌堆，先进的在前
     * @param current  今天这一张；还没翻过时为空
     * @param rng      以后把弃牌堆洗回去时用的随机流
     */
    public static WeatherDeck of(List<WeatherCard> upcoming, List<WeatherCard> discard,
                                 java.util.Optional<WeatherCard> current, Random rng) {
        Objects.requireNonNull(upcoming, "upcoming");
        Objects.requireNonNull(discard, "discard");
        Objects.requireNonNull(current, "current");
        if (upcoming.isEmpty() && discard.isEmpty() && current.isEmpty()) {
            throw new IllegalArgumentException("天候牌堆不能为空");
        }
        return new WeatherDeck(upcoming, discard, current.orElse(null), Objects.requireNonNull(rng, "rng"));
    }

    /** {@code java.util.Random} 可序列化：照原样复制一份内部状态。 */
    private static Random cloneRandom(Random rng) {
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            try (java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(bytes)) {
                out.writeObject(rng);
            }
            try (java.io.ObjectInputStream in = new java.io.ObjectInputStream(
                    new java.io.ByteArrayInputStream(bytes.toByteArray()))) {
                return (Random) in.readObject();
            }
        } catch (java.io.IOException | ClassNotFoundException e) {
            throw new IllegalStateException("天候牌堆的随机流复制不了：" + rng.getClass().getName(), e);
        }
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
