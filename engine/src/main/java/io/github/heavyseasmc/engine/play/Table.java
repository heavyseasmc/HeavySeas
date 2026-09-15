package io.github.heavyseasmc.engine.play;

import io.github.heavyseasmc.engine.model.Provisions;
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
 * 桌面：两副牌堆、划船堆、物资弃牌堆，外加物资的效果目录。
 *
 * <h2>为什么不塞进 GameState</h2>
 * {@link io.github.heavyseasmc.engine.state.GameState} 是不可变的，为的是「状态推进可回放」。
 * 而牌堆每抽一张都在变 —— 把它塞进不可变状态，就要每抽一张复制一整副牌。
 * 两者性质不同，分开放。
 *
 * <h2>航海牌只在三个地方</h2>
 * 牌堆里、划船者手上（抽出来看过、还没定去向），或划船堆里。少一张的表现**不是报错，是某些名单再也不出现** ——
 * 那种错能安静地跑完几千局。所以每一步之后都对账。
 *
 * <p>「划船者手上」是划船拆成「先抽 / 再定」两步之后才有的：真人要一张一张想，
 * 两张牌在他手上停留的时间可能很长。不把这一处记进账，对账在他想的时候就会报牌丢了。
 *
 * <h2>物资牌在五个地方</h2>
 * 牌堆里、补给箱在传的那几张、某人手上、某人面前、弃牌堆。前两处与弃牌堆在本类，
 * 后两处在每个人的 {@code SurvivorState} 里，所以物资的对账在 {@link Session} 上 —— 见
 * {@code Session#requireNoProvisionLost}。
 *
 * <h2>效果目录也在这里</h2>
 * 牌堆由目录展开（{@link Provisions#deck()}），所以两者必须同源；分开传就会出现
 * 「牌堆里有一张目录不认识的牌」这种只有运行时才发现的错。
 */
public final class Table {

    private final NavigationDeck pile;

    /** 划船堆。面朝下，只有舵手看得到全部；结算完清空。 */
    private final List<NavigationCard> rowStack = new ArrayList<>();

    /** 划船者手上：抽出来、还没定去向的牌。只有划船者本人看得到。 */
    private final List<NavigationCard> rowerHand = new ArrayList<>();

    /** 物资的效果目录。整副牌由它展开，所以两者同源。 */
    private final Provisions provisions;

    /** 物资牌堆。**抽完即止，不洗回重用**（规则明写），所以它只会变短。 */
    private final Deque<String> provisionPile = new ArrayDeque<>();

    /** 物资弃牌堆。用掉的水、医疗箱、信号枪都到这里 —— 有它，物资才对得上账。 */
    private final List<String> provisionDiscard = new ArrayList<>();

    /**
     * 只有航海牌堆：物资阶段没有牌可发，会被直接跳过。
     *
     * <p>❗<b>目录仍然是必须的</b>：没有目录就无法回答「这张牌有什么效果」，
     * 而「查不到就当没效果」会让「数据少了一张」与「这张牌本来就没效果」在运行时完全相同。
     */
    public Table(NavigationDeck pile, Provisions provisions) {
        this.pile = Objects.requireNonNull(pile, "pile");
        this.provisions = Objects.requireNonNull(provisions, "provisions");
    }

    /**
     * 连物资牌堆一起：整副按目录展开（47 张，水出现 16 次）并洗牌。
     *
     * <p>❗<b>牌堆不单独传</b>：它就是 {@code provisions.deck()}。分开传等于允许
     * 「牌堆与目录不是同一套」，而那种错只有在某个人打出那张牌时才会露头。
     */
    public Table(NavigationDeck pile, Provisions provisions, Random rng) {
        this(pile, provisions);
        List<String> shuffled = new ArrayList<>(provisions.deck());
        Collections.shuffle(shuffled, Objects.requireNonNull(rng, "rng"));
        provisionPile.addAll(shuffled);
    }

    /** 物资的效果目录。 */
    public Provisions provisions() {
        return provisions;
    }

    public int provisionsLeft() {
        return provisionPile.size();
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
        for (int i = 0; i < n && !provisionPile.isEmpty(); i++) {
            out.add(provisionPile.removeFirst());
        }
        return out;
    }

    /**
     * 从物资牌堆里抽走指定的一张（<b>夹具</b>，不是规则）。
     *
     * <p>❗它存在的唯一理由是：有些东西<b>不摆好局面就验不了</b> —— 口渴那一面要有人既渴着又手里有水，
     * 而发牌是随机的，等它自己出现的验收脚本一定会时灵时不灵。
     *
     * <p>它<b>不是凭空造牌</b>：牌真的从牌堆里少一张，所以对账照样成立。
     * 这与「调用方自己往手牌里塞一张」有本质区别，后者会让 {@code requireNoProvisionLost} 当场红。
     *
     * @return 牌堆里有没有这张
     */
    boolean takeFromProvisionPile(String cardId) {
        return provisionPile.remove(cardId);
    }

    /** 弃掉一张物资。用后即弃的牌（水、医疗箱、信号枪、绝境）走这里，<b>不是凭空消失</b>。 */
    public void discardProvision(String cardId) {
        provisionDiscard.add(Objects.requireNonNull(cardId, "cardId"));
    }

    /** 弃牌堆的只读视图。物资不洗回重用，所以它只会变长。 */
    public List<String> provisionDiscard() {
        return List.copyOf(provisionDiscard);
    }

    /** 物资牌一共几张（发出去的 + 还在堆里的 + 弃掉的）。对账的分母。 */
    public int provisionTotal() {
        return provisions.total();
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

    /** 划船者手上还没定去向的牌（只读视图）。 */
    public List<NavigationCard> rowerHand() {
        return List.copyOf(rowerHand);
    }

    void takeIntoRowerHand(NavigationCard card) {
        rowerHand.add(card);
    }

    boolean releaseFromRowerHand(NavigationCard card) {
        return rowerHand.remove(card);
    }

    /** 划船堆整堆回到牌堆底部并清空。 */
    void recycleRowStack() {
        rowStack.forEach(pile::bottom);
        rowStack.clear();
    }

    /**
     * 航海牌对账：牌堆 + 划船者手上 + 划船堆必须等于总张数。
     *
     * @param context 出处（种子或对局标识），报错时靠它定位
     */
    public void requireNoCardLost(String context, String where) {
        int accounted = pile.size() + rowerHand.size() + rowStack.size();
        if (accounted != pile.total()) {
            throw new IllegalStateException(
                    "%s %s：牌对不上，牌堆 %d + 划船者手上 %d + 划船堆 %d ≠ 共 %d 张"
                            .formatted(context, where, pile.size(), rowerHand.size(), rowStack.size(), pile.total()));
        }
    }
}
