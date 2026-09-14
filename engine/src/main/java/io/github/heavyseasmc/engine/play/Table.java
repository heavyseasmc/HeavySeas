package io.github.heavyseasmc.engine.play;

import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.navigation.NavigationDeck;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * 桌面：牌堆与划船堆。
 *
 * <h2>为什么不塞进 GameState</h2>
 * {@link io.github.heavyseasmc.engine.state.GameState} 是不可变的，为的是「状态推进可回放」。
 * 而牌堆每抽一张都在变 —— 把它塞进不可变状态，就要每抽一张复制一整副牌。
 * 两者性质不同，分开放。
 *
 * <h2>牌只在两个地方</h2>
 * 牌堆里，或划船堆里。少一张的表现**不是报错，是某些名单再也不出现** ——
 * 那种错能安静地跑完几千局。所以每一步之后都对账。
 */
public final class Table {

    private final NavigationDeck pile;

    /** 划船堆。面朝下，只有舵手看得到全部；结算完清空。 */
    private final List<NavigationCard> rowStack = new ArrayList<>();

    /** 物资牌堆。**抽完即止，不洗回重用**（规则明写），所以它只会变短。 */
    private final Deque<String> provisions = new ArrayDeque<>();

    /** 只有航海牌堆：物资阶段没有牌可发，会被直接跳过。模拟器走的就是这条。 */
    public Table(NavigationDeck pile) {
        this.pile = Objects.requireNonNull(pile, "pile");
    }

    /**
     * 连物资牌堆一起。
     *
     * @param provisionCards 展开后的整副（47 张，水出现 16 次），由本方法洗牌
     */
    public Table(NavigationDeck pile, List<String> provisionCards, Random rng) {
        this(pile);
        List<String> shuffled = new ArrayList<>(Objects.requireNonNull(provisionCards, "provisionCards"));
        Collections.shuffle(shuffled, Objects.requireNonNull(rng, "rng"));
        provisions.addAll(shuffled);
    }

    public int provisionsLeft() {
        return provisions.size();
    }

    /**
     * 从物资牌堆顶抽最多 {@code n} 张。
     *
     * <p>不够就有几张抽几张 —— 规则写的是「抽完即止」，而不是「不够就不发」。
     */
    public List<String> drawProvisions(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("抽牌数不能为负: " + n);
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n && !provisions.isEmpty(); i++) {
            out.add(provisions.removeFirst());
        }
        return out;
    }

    public NavigationDeck pile() {
        return pile;
    }

    /** 划船堆的只读视图。舵手挑牌时看的就是它。 */
    public List<NavigationCard> rowStack() {
        return List.copyOf(rowStack);
    }

    public boolean rowStackIsEmpty() {
        return rowStack.isEmpty();
    }

    void addToRowStack(NavigationCard card) {
        rowStack.add(card);
    }

    boolean removeFromRowStack(NavigationCard card) {
        return rowStack.remove(card);
    }

    /** 划船堆整堆回到牌堆底部并清空。 */
    void recycleRowStack() {
        rowStack.forEach(pile::bottom);
        rowStack.clear();
    }

    /**
     * 对账：牌堆 + 划船堆必须等于总张数。
     *
     * @param context 出处（种子或对局标识），报错时靠它定位
     */
    public void requireNoCardLost(String context, String where) {
        int accounted = pile.size() + rowStack.size();
        if (accounted != pile.total()) {
            throw new IllegalStateException(
                    "%s %s：牌对不上，牌堆 %d + 划船堆 %d ≠ 共 %d 张"
                            .formatted(context, where, pile.size(), rowStack.size(), pile.total()));
        }
    }
}
