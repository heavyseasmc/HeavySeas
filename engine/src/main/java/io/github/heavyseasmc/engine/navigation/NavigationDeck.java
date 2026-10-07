package io.github.heavyseasmc.engine.navigation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * 航海牌堆：从顶抽，放回一律进底。
 *
 * <h2>为什么不是「每回合随机抽一张」</h2>
 * 随机抽等于有放回抽样，而真实牌堆<b>一局之内不放回</b>：这一局已经翻过的牌要绕一圈才会再来。
 * 更要紧的是划船 —— 划船者抽 2 张<b>看过</b>之后选 1 张放进划船堆，其余塞回底部，
 * 舵手再从划船堆里挑 1 张。「看过再挑」这件事随机抽样根本表达不了，
 * 而它正是 O1 说的「静态张数 ≠ 实际落水频率」的原因。
 *
 * <h2>牌不会凭空消失</h2>
 * 牌任何时候都只在两个地方：牌堆里，或划船堆里。模拟器每回合核对这条守恒 ——
 * 少一张牌的表现是某些名单永远不再出现，那种偏差会安静地污染整份统计。
 */
public final class NavigationDeck {

    private final Deque<NavigationCard> pile = new ArrayDeque<>();
    private final int total;

    /**
     * 洗好的一副牌。
     *
     * @param rng 同一个种子必须洗出同一副牌，否则失败复现不了
     */
    public NavigationDeck(List<NavigationCard> cards, Random rng) {
        Objects.requireNonNull(rng, "rng");
        List<NavigationCard> shuffled = new ArrayList<>(Objects.requireNonNull(cards, "cards"));
        if (shuffled.isEmpty()) {
            throw new IllegalArgumentException("航海牌堆不能为空");
        }
        Collections.shuffle(shuffled, rng);
        pile.addAll(shuffled);
        this.total = shuffled.size();
    }

    private NavigationDeck(List<NavigationCard> order, int total) {
        pile.addAll(order);
        this.total = total;
    }

    /** 一副次序一模一样、互不相干的副本（往前推演时用：在副本上抽牌不动原来那一副）。 */
    public NavigationDeck copy() {
        return new NavigationDeck(List.copyOf(pile), total);
    }

    /**
     * 照给定的次序拼一副牌堆（推演时重拼一局用：牌堆次序是重新抽过的）。
     *
     * @param order 牌堆里的牌，顶上的在前
     * @param total 这一副一共几张 —— 牌堆加上划船堆与划船者手上的（对账的分母），不小于 {@code order} 的张数
     */
    public static NavigationDeck ofOrder(List<NavigationCard> order, int total) {
        Objects.requireNonNull(order, "order");
        if (total < order.size() || total < 1) {
            throw new IllegalArgumentException("一副共 %d 张，牌堆里却有 %d 张".formatted(total, order.size()));
        }
        return new NavigationDeck(List.copyOf(order), total);
    }

    /**
     * 照给定的次序重排这一副（推演时把看不见的次序重新洗过）。张数与成员必须不变。
     *
     * @throws IllegalArgumentException 给的牌与牌堆里的不是同一批
     */
    public void reorder(List<NavigationCard> order) {
        java.util.Map<NavigationCard, Integer> want = new java.util.HashMap<>();
        order.forEach(c -> want.merge(c, 1, Integer::sum));
        java.util.Map<NavigationCard, Integer> have = new java.util.HashMap<>();
        pile.forEach(c -> have.merge(c, 1, Integer::sum));
        if (!want.equals(have)) {
            throw new IllegalArgumentException("重排的牌与牌堆里的不是同一批");
        }
        pile.clear();
        pile.addAll(order);
    }

    /** 牌堆里还剩几张（不含已经进了划船堆的）。 */
    public int size() {
        return pile.size();
    }

    /** 这副牌一共几张。守恒检查用。 */
    public int total() {
        return total;
    }

    public boolean isEmpty() {
        return pile.isEmpty();
    }

    /** 牌堆此刻的顺序，顶上的在前。只读的一份拷贝（调试查看与导出用，ADR-0060）。 */
    public List<NavigationCard> order() {
        return List.copyOf(pile);
    }

    /**
     * 从顶抽一张。
     *
     * @throws IllegalStateException 牌堆空了。<b>不自动重洗</b> ——
     *                               真实对局里牌堆空不了（牌都在划船堆里，本回合就会放回），
     *                               空了说明有牌被弄丢了，那是要立刻知道的事
     */
    public NavigationCard draw() {
        NavigationCard card = pile.pollFirst();
        if (card == null) {
            throw new IllegalStateException("牌堆空了 —— 有牌没放回，本副共 " + total + " 张");
        }
        return card;
    }

    /** 放回底部：划船者不要的、舵手没挑中的、以及结算完的那张，都走这里。 */
    public void bottom(NavigationCard card) {
        pile.addLast(Objects.requireNonNull(card, "card"));
    }
}
