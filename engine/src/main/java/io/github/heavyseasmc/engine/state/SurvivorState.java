package io.github.heavyseasmc.engine.state;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.thirst.ThirstSource;
import io.github.heavyseasmc.engine.thirst.ThirstTally;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 一名角色<b>会变的</b>那一半。不变的那一半在 {@link io.github.heavyseasmc.engine.model.Survivor}。
 *
 * <p>刻意<b>不</b>把体型抄进来：体型整局不变，抄一份就有了两个真相源。
 * 需要判定生死时由 {@link GameState} 把两半配起来，那里只有一处配对逻辑。
 *
 * <p>同理刻意<b>不</b>存 {@link Condition} —— 它由伤害与体型推导，理由见那个枚举。
 *
 * <h2>牌在两个区，不是一个（ADR-0021）</h2>
 * <b>手牌</b>只有自己看得到；<b>面前</b>是亮出来的，全船看得见，而且<b>亮出不可逆</b>（规则 §5.2）。
 * 这不是显示差异：规则里一大半效果<b>只有亮在面前才算</b>（救生圈挡落水、指南针多抽、武器加战斗力），
 * 而落水会冲走面前的全部（救生圈除外）。两个区分开存，「这张牌现在有没有用」才有唯一答案。
 *
 * @param id          角色主键
 * @param seat        当前座位。换座位会改它，所以它在「会变的一半」里
 * @param damage      累计伤害。只增不减，唯一的例外是治疗（医疗箱、绝境）
 * @param thirst      本回合累积的口渴来源。航海阶段结束时清空
 * @param actedThisTurn 本回合是否已经行动过。行动阶段按「最靠船头且未行动」取人
 * @param hand        手牌（物资 id，可重复 —— 水有 16 张）。
 *                    <b>只存 id 不存效果</b>：效果在 {@code data/provisions} 里，
 *                    抄一份进状态就有了两个真相源
 * @param front       亮在面前的牌（物资 id，可重复）。<b>公开信息</b>
 * @param opened      面前哪些牌已经「打开」并持续生效 —— 目前只有撑开的阳伞。
 *                    <b>持续到被冲走</b>，不随回合清
 * @param usedThisTurn 面前哪些牌本回合用过了 —— 目前只有喝过的酒（{@code once_per_turn}）。
 *                    <b>回合结束清空</b>；航海牌 nav_04 的 {@code used_rum} 问的就是它
 */
public record SurvivorState(
        CharacterId id,
        int seat,
        int damage,
        ThirstTally thirst,
        boolean actedThisTurn,
        List<String> hand,
        List<String> front,
        Set<String> opened,
        Set<String> usedThisTurn
) {

    public SurvivorState {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(thirst, "thirst");
        hand = List.copyOf(Objects.requireNonNull(hand, "hand"));
        front = List.copyOf(Objects.requireNonNull(front, "front"));
        opened = Set.copyOf(Objects.requireNonNull(opened, "opened"));
        usedThisTurn = Set.copyOf(Objects.requireNonNull(usedThisTurn, "usedThisTurn"));
        if (seat < 1) {
            throw new IllegalArgumentException("座位号从 1 起（船头），实际: " + seat);
        }
        if (damage < 0) {
            throw new IllegalArgumentException("伤害不能为负，实际: " + damage);
        }
        for (String open : opened) {
            if (!front.contains(open)) {
                // 「撑开的伞不在面前」= 某一步冲走了牌却忘了收标记，之后它会一直白挡口渴。
                throw new IllegalArgumentException(
                        "%s 的 %s 标着已打开，却不在他面前：%s".formatted(id, open, front));
            }
        }
    }

    /** 开局状态：未受伤、未渴、未行动、手上与面前都没有牌。 */
    public static SurvivorState fresh(CharacterId id, int seat) {
        return new SurvivorState(id, seat, 0, ThirstTally.none(), false,
                List.of(), List.of(), Set.of(), Set.of());
    }

    public SurvivorState withDamage(int newDamage) {
        return newDamage == damage
                ? this
                : new SurvivorState(id, seat, newDamage, thirst, actedThisTurn, hand, front, opened, usedThisTurn);
    }

    /** 受伤。{@code points} 为 0 时原样返回，省掉一次无意义的分配。 */
    public SurvivorState hurt(int points) {
        if (points < 0) {
            throw new IllegalArgumentException("受伤点数不能为负，实际: " + points);
        }
        return withDamage(damage + points);
    }

    /**
     * 治疗：减 1 点伤害（医疗箱、绝境）。
     *
     * <p>伤害为 0 时拒绝 —— 「治疗一个没受伤的人」几乎一定是调用方算错了，
     * 静默当成 0 会把那个错误藏起来。规则上医疗箱也只用来救醒昏迷者。
     *
     * <p>❗<b>绝境走的不是这里</b>：它给「每个有意识的角色」各回 1 点，其中必然有没受伤的人。
     * 那种情形没有伤害标记可移除，是<b>什么也不发生</b>，不是错误 —— 见 {@link #healIfHurt}。
     */
    public SurvivorState heal(int points) {
        if (points < 1) {
            throw new IllegalArgumentException("治疗点数必须为正，实际: " + points);
        }
        if (damage == 0) {
            throw new IllegalArgumentException("对未受伤的 " + id + " 使用治疗，调用方多半算错了");
        }
        return withDamage(Math.max(0, damage - points));
    }

    /** 受伤了才治，没受伤就原样返回。全体回血（绝境）用这个。 */
    public SurvivorState healIfHurt(int points) {
        return damage == 0 ? this : heal(points);
    }

    public SurvivorState withSeat(int newSeat) {
        return newSeat == seat
                ? this
                : new SurvivorState(id, newSeat, damage, thirst, actedThisTurn, hand, front, opened, usedThisTurn);
    }

    public SurvivorState thirstFrom(ThirstSource source) {
        ThirstTally next = thirst.with(source);
        return next == thirst
                ? this
                : new SurvivorState(id, seat, damage, next, actedThisTurn, hand, front, opened, usedThisTurn);
    }

    /**
     * 拿到一张物资，进手牌。
     *
     * <p>❗手牌<b>允许重复</b>：水有 16 张，同一个 id 拿两张是常态。
     * 用 List 而不是 Set 正是为此 —— 用 Set 的话第二张水会被悄悄吞掉。
     */
    public SurvivorState withCard(String cardId) {
        Objects.requireNonNull(cardId, "cardId");
        List<String> next = new ArrayList<>(hand);
        next.add(cardId);
        return withHand(next);
    }

    /**
     * 从手牌里去掉一张（打出、送人、被夺走）。
     *
     * @throws IllegalArgumentException 手里没有这张 —— 静默忽略会让「牌凭空消失」查不出来
     */
    public SurvivorState withoutCard(String cardId) {
        List<String> next = new ArrayList<>(hand);
        if (!next.remove(cardId)) {
            throw new IllegalArgumentException("%s 手里没有 %s".formatted(id, cardId));
        }
        return withHand(next);
    }

    /**
     * 亮出：手牌 → 面前。**不可逆**（规则 §5.2：亮出的牌不能收回手牌）。
     *
     * @throws IllegalArgumentException 手里没有这张
     */
    public SurvivorState reveal(String cardId) {
        List<String> nextHand = new ArrayList<>(hand);
        if (!nextHand.remove(cardId)) {
            throw new IllegalArgumentException("%s 手里没有 %s，亮不出来".formatted(id, cardId));
        }
        List<String> nextFront = new ArrayList<>(front);
        nextFront.add(cardId);
        return new SurvivorState(id, seat, damage, thirst, actedThisTurn,
                nextHand, nextFront, opened, usedThisTurn);
    }

    /** 直接把一张牌放到面前（别人替你亮、或者用后留在面前）。 */
    public SurvivorState withCardInFront(String cardId) {
        Objects.requireNonNull(cardId, "cardId");
        List<String> next = new ArrayList<>(front);
        next.add(cardId);
        return withFront(next);
    }

    /**
     * 从面前去掉一张（用掉、被夺走、被冲走）。同时收掉它的「已打开」与「本回合用过」标记。
     *
     * @throws IllegalArgumentException 面前没有这张
     */
    public SurvivorState withoutCardInFront(String cardId) {
        List<String> next = new ArrayList<>(front);
        if (!next.remove(cardId)) {
            throw new IllegalArgumentException("%s 面前没有 %s".formatted(id, cardId));
        }
        return withFront(next);
    }

    /** 面前有没有这张（亮出来了才算）。 */
    public boolean hasInFront(String cardId) {
        return front.contains(cardId);
    }

    public boolean hasInHand(String cardId) {
        return hand.contains(cardId);
    }

    /** 手上有几张这个 id（水要数张数）。 */
    public int countInHand(String cardId) {
        return (int) hand.stream().filter(cardId::equals).count();
    }

    /**
     * 打开面前的一张，让它开始持续生效（撑开阳伞）。
     *
     * @throws IllegalArgumentException 这张不在面前
     */
    public SurvivorState open(String cardId) {
        if (!front.contains(cardId)) {
            throw new IllegalArgumentException("%s 面前没有 %s，打不开".formatted(id, cardId));
        }
        if (opened.contains(cardId)) {
            return this;
        }
        Set<String> next = new LinkedHashSet<>(opened);
        next.add(cardId);
        return new SurvivorState(id, seat, damage, thirst, actedThisTurn, hand, front, next, usedThisTurn);
    }

    /** 这张是不是已经打开并持续生效（撑开的伞）。 */
    public boolean isOpen(String cardId) {
        return opened.contains(cardId);
    }

    /** 记下这张牌本回合用过了（喝过的酒）。回合结束时清空。 */
    public SurvivorState markUsedThisTurn(String cardId) {
        if (usedThisTurn.contains(cardId)) {
            return this;
        }
        Set<String> next = new LinkedHashSet<>(usedThisTurn);
        next.add(cardId);
        return new SurvivorState(id, seat, damage, thirst, actedThisTurn, hand, front, opened, next);
    }

    /** 这张牌本回合用过了没有。nav_04 的 {@code used_rum} 问的就是它。 */
    public boolean usedThisTurn(String cardId) {
        return usedThisTurn.contains(cardId);
    }

    public SurvivorState markActed() {
        return actedThisTurn
                ? this
                : new SurvivorState(id, seat, damage, thirst, true, hand, front, opened, usedThisTurn);
    }

    /**
     * 航海阶段结束时的清理：清除划船与战斗标记、行动标记复位，
     * 并清掉「本回合用过」（酒每回合可以再喝一次）。
     *
     * <p>❗<b>不清 {@link #opened}</b>：撑开的伞一直撑着，直到落水被冲走。两种寿命不同，所以是两个字段。
     *
     * <p>❗<b>开放项 O7 正是在问这件事</b>：天候「无风」跳过整个航海阶段时，
     * 这一步清不清。口渴是跨天累积的，不清就会让昨天划过船的人今天平白口渴。
     * 本方法只负责「被调用时清干净」，<b>何时调用由阶段推进决定</b>，
     * 这样 O7 定案时只改一处调用点，不用动状态模型。
     */
    public SurvivorState endOfTurn() {
        return thirst.isEmpty() && !actedThisTurn && usedThisTurn.isEmpty()
                ? this
                : new SurvivorState(id, seat, damage, ThirstTally.none(), false,
                        hand, front, opened, Set.of());
    }

    private SurvivorState withHand(List<String> nextHand) {
        return new SurvivorState(id, seat, damage, thirst, actedThisTurn, nextHand, front, opened, usedThisTurn);
    }

    /** 换掉整个「面前」，并把已经不在面前的标记一起收掉。 */
    public SurvivorState withFront(List<String> nextFront) {
        Set<String> keptOpen = new LinkedHashSet<>(opened);
        keptOpen.retainAll(nextFront);
        Set<String> keptUsed = new LinkedHashSet<>(usedThisTurn);
        keptUsed.retainAll(nextFront);
        return new SurvivorState(id, seat, damage, thirst, actedThisTurn, hand, nextFront, keptOpen, keptUsed);
    }
}
