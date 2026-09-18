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
