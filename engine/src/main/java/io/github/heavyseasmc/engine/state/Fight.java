package io.github.heavyseasmc.engine.state;

import io.github.heavyseasmc.engine.model.CharacterId;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 战斗 —— 行动阶段的子状态。
 *
 * <h2>四条最容易写错的</h2>
 * <ol>
 *   <li><b>战斗力按满体型算，与伤害无关。</b>「攻击力 = 剩余血量」是村规。
 *       所以本类只收 {@code size}，<b>根本不看伤害</b> —— 看不到就写不错。</li>
 *   <li><b>平手防守方胜。</b>不是重打，也不是双方都输。</li>
 *   <li><b>败方每一个参战者各受 1 点伤害</b>，不是只有主将受伤，也不是平摊。</li>
 *   <li><b>无论胜负，进攻方的行动就此结束。</b>赢了也不能接着做别的。</li>
 * </ol>
 *
 * <h2>战斗不是行动</h2>
 * 它不消耗任何人的行动 —— 包括助拳者。进攻方的行动是被「发起换座位 / 抢夺」那一下消耗掉的，
 * 不是被战斗消耗掉的。这个区别在实现上很实在：助拳者本回合照样能轮到自己行动。
 *
 * <h2>触发条件唯一</h2>
 * 只有「换座位」或「抢夺物资」的目标是<b>清醒且不同意</b>时才会打起来。
 * <b>不能因为讨厌某人就主动开战</b>；昏迷 / 死亡者既不能发起也不能加入，更不能拒绝。
 * 这条由调用方（行动阶段）把关，本类只在构造时校验双方不同人。
 */
public final class Fight {

    private final CharacterId attacker;
    private final CharacterId defender;
    private final Set<CharacterId> attackSide;
    private final Set<CharacterId> defendSide;
    private final Map<CharacterId, Integer> weapons;

    private Fight(CharacterId attacker, CharacterId defender,
                  Set<CharacterId> attackSide, Set<CharacterId> defendSide,
                  Map<CharacterId, Integer> weapons) {
        this.attacker = attacker;
        this.defender = defender;
        this.attackSide = Collections.unmodifiableSet(new LinkedHashSet<>(attackSide));
        this.defendSide = Collections.unmodifiableSet(new LinkedHashSet<>(defendSide));
        this.weapons = Collections.unmodifiableMap(new LinkedHashMap<>(weapons));
    }

    /** 开战：进攻方与防守方各自成阵营。 */
    public static Fight between(CharacterId attacker, CharacterId defender) {
        Objects.requireNonNull(attacker, "attacker");
        Objects.requireNonNull(defender, "defender");
        if (attacker.equals(defender)) {
            throw new IllegalArgumentException("不能和自己打: " + attacker);
        }
        return new Fight(attacker, defender,
                new LinkedHashSet<>(Set.of(attacker)),
                new LinkedHashSet<>(Set.of(defender)),
                Map.of());
    }

    public CharacterId attacker() {
        return attacker;
    }

    public CharacterId defender() {
        return defender;
    }

    public Set<CharacterId> attackSide() {
        return attackSide;
    }

    public Set<CharacterId> defendSide() {
        return defendSide;
    }

    /** 全部参战者（含助拳者）。战斗标记与战斗口渴按这个集合发。 */
    public Set<CharacterId> combatants() {
        Set<CharacterId> all = new LinkedHashSet<>(attackSide);
        all.addAll(defendSide);
        return Collections.unmodifiableSet(all);
    }

    public Side sideOf(CharacterId id) {
        if (attackSide.contains(id)) {
            return Side.ATTACK;
        }
        if (defendSide.contains(id)) {
            return Side.DEFEND;
        }
        throw new IllegalArgumentException(id + " 没有参战");
    }

    /**
     * 助拳。
     *
     * <p>❗<b>加入后不得反悔</b>，所以本类没有「退出」方法 —— 不提供就写不出那个 bug。
     * 「宣布中立」只是口头承诺，没有规则效力，因此也不是一种状态。
     *
     * @throws IllegalArgumentException 此人已经在场上（不论哪一边）
     */
    public Fight join(CharacterId helper, Side side) {
        Objects.requireNonNull(helper, "helper");
        Objects.requireNonNull(side, "side");
        if (attackSide.contains(helper) || defendSide.contains(helper)) {
            throw new IllegalArgumentException(helper + " 已经参战，加入后不得反悔、也不能换边");
        }
        Set<CharacterId> atk = new LinkedHashSet<>(attackSide);
        Set<CharacterId> def = new LinkedHashSet<>(defendSide);
        (side == Side.ATTACK ? atk : def).add(helper);
        return new Fight(attacker, defender, atk, def, weapons);
    }

    /**
     * 打出武器，加到某个参战者身上。
     *
     * <p>武器<b>可叠加</b>，所以同一人多次打出是累加而不是取最大。
     * 「亮出后也可以选择本场不使用」意味着调用方可以干脆不调用本方法 ——
     * 引擎不替它做这个选择。
     *
     * @throws IllegalArgumentException 目标没有参战，或加值非正
     */
    public Fight arm(CharacterId target, int power) {
        Objects.requireNonNull(target, "target");
        if (power < 1) {
            throw new IllegalArgumentException("武器加值必须为正，实际: " + power);
        }
        sideOf(target);         // 没参战就抛
        Map<CharacterId, Integer> next = new LinkedHashMap<>(weapons);
        next.merge(target, power, Integer::sum);
        return new Fight(attacker, defender, attackSide, defendSide, next);
    }

    /** 某个参战者身上累计的武器加值。 */
    public int weaponPowerOf(CharacterId id) {
        return weapons.getOrDefault(id, 0);
    }

    /**
     * 结算。
     *
     * @param sizeOf 取<b>满体型</b>的函数。❗传剩余血量就错了 —— 受伤不降低战斗力
     */
    public Outcome resolve(java.util.function.ToIntFunction<CharacterId> sizeOf) {
        Objects.requireNonNull(sizeOf, "sizeOf");
        int atk = strengthOf(attackSide, sizeOf);
        int def = strengthOf(defendSide, sizeOf);
        // 平手防守方胜 —— 不是重打，也不是双方都输。
        Side winner = atk > def ? Side.ATTACK : Side.DEFEND;
        Set<CharacterId> losers = winner == Side.ATTACK ? defendSide : attackSide;
        return new Outcome(winner, atk, def, Collections.unmodifiableSet(new LinkedHashSet<>(losers)));
    }

    private int strengthOf(Set<CharacterId> side, java.util.function.ToIntFunction<CharacterId> sizeOf) {
        int total = 0;
        for (CharacterId id : side) {
            total += sizeOf.applyAsInt(id) + weaponPowerOf(id);
        }
        return total;
    }

    /** 阵营。 */
    public enum Side {
        ATTACK, DEFEND
    }

    /**
     * 战斗结果。
     *
     * @param winner        胜方
     * @param attackPower   进攻方总战力（Σ满体型 + Σ武器）
     * @param defendPower   防守方总战力
     * @param losers        败方<b>全部</b>参战者 —— 每人各受 1 点伤害，不是只伤主将，也不是平摊
     */
    public record Outcome(Side winner, int attackPower, int defendPower, Set<CharacterId> losers) {

        /** 进攻方是否取得了最初索求的东西（换位成功 / 拿到那张物资）。 */
        public boolean attackerGetsWhatTheyWanted() {
            return winner == Side.ATTACK;
        }

        /** 败方每人受到的伤害点数。 */
        public int damagePerLoser() {
            return 1;
        }

        /** 是不是平手 —— 平手时防守方胜，这个谓词只用来解释结果，不改变判定。 */
        public boolean wasTie() {
            return attackPower == defendPower;
        }
    }
}
