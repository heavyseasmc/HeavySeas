package io.github.heavyseasmc.engine.sim;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.navigation.Selector;
import io.github.heavyseasmc.engine.state.Condition;
import io.github.heavyseasmc.engine.state.Fight;
import io.github.heavyseasmc.engine.state.GameState;
import io.github.heavyseasmc.engine.thirst.ThirstResolver;
import io.github.heavyseasmc.engine.thirst.ThirstSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/**
 * 随机对局模拟器 —— 用随机策略把状态机跑到终局，每一步核对不变量。
 *
 * <h2>它在找什么</h2>
 * 两类，都是单测抓不到的：
 * <ul>
 *   <li><b>死锁</b>：某个状态下谁也推不动，游戏永远结束不了。
 *       全员昏迷是最典型的候选 —— 没人能行动、没有舵手，但规则要求继续抽牌。</li>
 *   <li><b>非法状态</b>：座位撞车、伤害为负、生死与伤害不符、死而复生、伤害倒退……
 *       见 {@link Invariants}。</li>
 * </ul>
 *
 * <h2>随机策略不是「玩得好」，是「玩得杂」</h2>
 * 它刻意做蠢事：明明有水也可能不喝、能赢的架也可能不打。
 * 目的是<b>把状态空间铺开</b>，不是模拟人类决策 —— 后者需要 AI，而本作不做 AI（这正是
 * 引擎不得依赖 MC 的原因：只有纯 Java 才能在毫秒级跑几千局）。
 *
 * <h2>种子必须能复现</h2>
 * 每一局由一个 {@code long} 种子完全决定。失败时报出种子，重跑那一个种子即可复现 ——
 * 否则「几千局里挂了一局」等于没有信息。
 */
public final class Simulator {

    /** 单局回合数上限。超过即判定为疑似死锁 —— 正常对局远到不了这个数。 */
    public static final int TURN_LIMIT = 500;

    private final Roster roster;
    private final List<NavigationCard> deck;

    public Simulator(Roster roster, List<NavigationCard> deck) {
        this.roster = Objects.requireNonNull(roster, "roster");
        this.deck = List.copyOf(Objects.requireNonNull(deck, "deck"));
        if (this.deck.isEmpty()) {
            throw new IllegalArgumentException("航海牌堆不能为空 —— 没有牌就永远结束不了");
        }
    }

    /**
     * 跑一整局。
     *
     * @throws IllegalStateException 出现非法状态，或超过回合上限（疑似死锁）
     */
    public Result run(long seed) {
        Random rng = new Random(seed);
        GameState g = GameState.start(roster);
        Invariants.requireValid(g, seed, "开局");

        int fights = 0;
        ExposureTally exposure = new ExposureTally();
        while (!g.isOver()) {
            if (g.turn() > TURN_LIMIT) {
                throw new IllegalStateException(
                        "seed=%d 超过 %d 回合仍未结束，疑似死锁（海鸥 %d，存活 %d）"
                                .formatted(seed, TURN_LIMIT, g.gulls(), aliveCount(g)));
            }
            GameState before = g;
            switch (g.phase()) {
                case PROVISION -> g = provision(g);
                case ACTION -> {
                    ActionResult r = action(g, rng, seed);
                    g = r.state();
                    fights += r.fights();
                }
                case NAVIGATION -> g = navigate(g, rng, seed, exposure);
            }
            Invariants.requireValidTransition(before, g, seed, "阶段 " + before.phase());
            if (g.isOver()) {
                break;
            }
            g = g.advancePhase();
            Invariants.requireValid(g, seed, "阶段推进后");
        }
        return new Result(seed, g.turn(), g.outcome().orElseThrow(), aliveCount(g), fights,
                exposure.toMap());
    }

    /**
     * 物资阶段。本模拟器<b>不建物资牌堆</b> —— 手牌与物资的完整建模不属于状态机这一项，
     * 而模拟器要找的是死锁与非法状态，那两者不依赖手里有几张牌。
     * 这里只走个过场，把「抽牌数 = 存活且清醒者数」这条算一遍，确保它不会崩。
     */
    private GameState provision(GameState g) {
        int draws = g.consciousBySeat().size();
        if (draws < 0) {
            throw new IllegalStateException("抽牌数不可能为负");
        }
        return g;
    }

    private record ActionResult(GameState state, int fights) {
    }

    /** 行动阶段：按「最靠船头且未行动」取人，每人随机做一件事。 */
    private ActionResult action(GameState g, Random rng, long seed) {
        int fights = 0;
        int guard = 0;
        while (true) {
            var next = g.nextActor();
            if (next.isEmpty()) {
                break;                       // ❗没人能行动是合法状态，不是死锁
            }
            if (++guard > roster.size() * 4) {
                throw new IllegalStateException(
                        "seed=%d 行动阶段推不动：nextActor 一直返回同一个人，标记没写回".formatted(seed));
            }
            CharacterId actor = next.get();
            switch (rng.nextInt(4)) {
                case 0 -> { }                                        // 什么都不做
                case 1 -> g = g.withState(actor, g.stateOf(actor).thirstFrom(ThirstSource.ROWED));
                case 2 -> g = swapSeats(g, actor, rng);
                default -> {
                    GameState after = maybeFight(g, actor, rng);
                    if (after != g) {
                        fights++;
                    }
                    g = after;
                }
            }
            g = g.withState(actor, g.stateOf(actor).markActed());
            Invariants.requireValid(g, seed, "行动后");
        }
        return new ActionResult(g, fights);
    }

    /** 换座位：与任意角色交换，不限相邻。昏迷与死亡者不能拒绝。 */
    private GameState swapSeats(GameState g, CharacterId actor, Random rng) {
        List<CharacterId> others = new ArrayList<>(g.bySeat());
        others.remove(actor);
        if (others.isEmpty()) {
            return g;
        }
        CharacterId target = others.get(rng.nextInt(others.size()));
        int a = g.stateOf(actor).seat();
        int b = g.stateOf(target).seat();
        return g.withState(actor, g.stateOf(actor).withSeat(b))
                .withState(target, g.stateOf(target).withSeat(a));
    }

    /**
     * 抢夺被拒 → 打一架。
     *
     * <p>目标必须<b>清醒</b>才能拒绝，所以昏迷与死亡者不会触发战斗（可以被随意搜刮）。
     */
    private GameState maybeFight(GameState g, CharacterId actor, Random rng) {
        List<CharacterId> targets = g.consciousBySeat().stream()
                .filter(id -> !id.equals(actor))
                .toList();
        if (targets.isEmpty()) {
            return g;
        }
        CharacterId defender = targets.get(rng.nextInt(targets.size()));
        Fight fight = Fight.between(actor, defender);

        // 随机助拳。加入后不得反悔，所以只在这里一次性决定。
        for (CharacterId helper : targets) {
            if (helper.equals(defender) || rng.nextInt(3) != 0) {
                continue;
            }
            fight = fight.join(helper, rng.nextBoolean() ? Fight.Side.ATTACK : Fight.Side.DEFEND);
        }
        // 随机打武器，加值取实际数据里的范围 1..8。
        for (CharacterId c : fight.combatants()) {
            if (rng.nextInt(3) == 0) {
                fight = fight.arm(c, 1 + rng.nextInt(8));
            }
        }

        Fight.Outcome outcome = fight.resolve(id -> g.roster().get(id).size());
        GameState next = g;
        for (CharacterId loser : outcome.losers()) {
            next = next.withState(loser, next.stateOf(loser).hurt(outcome.damagePerLoser()));
        }
        // 战斗标记发给全部参战者，含助拳者；同回合多次参战也只算一个（集合幂等）。
        for (CharacterId c : fight.combatants()) {
            next = next.withState(c, next.stateOf(c).thirstFrom(ThirstSource.FOUGHT));
        }
        return next;
    }

    /** 航海阶段：抽一张牌，按 海鸥 → 落海 → 口渴 结算。 */
    private GameState navigate(GameState g, Random rng, long seed, ExposureTally exposure) {
        NavigationCard card = deck.get(rng.nextInt(deck.size()));

        // a) 海鸥。凑够 4 只就地结束，该牌的落海与口渴一律不再结算。
        GameState next = g.withGulls(card.gull());
        Invariants.requireValid(next, seed, "海鸥结算后");
        if (next.isOver()) {
            return next;
        }

        // b) 落海。候选含尸体 —— 死者被冲下去会彻底退出游戏。
        Set<CharacterId> overboardCandidates = new LinkedHashSet<>(next.bySeat());
        Selector.ConditionResolver noConditions = (c, who) -> false;
        // 分母：这一步真正执行时还活着的人。先记分母再点名，两者必须同一个时刻取，
        // 否则「落水少」与「早就死了」会算成同一件事。
        for (CharacterId id : overboardCandidates) {
            if (next.conditionOf(id) != Condition.DEAD) {
                exposure.overboardChance(id);
            }
        }
        for (CharacterId id : card.overboard().select(overboardCandidates, noConditions)) {
            if (next.conditionOf(id) != Condition.DEAD) {
                exposure.overboard(id);          // 数的是下水，不是受伤：水手落水不受伤
            }
            boolean immune = isOverboardImmune(next, id);
            if (!immune) {
                next = next.withState(id, next.stateOf(id).hurt(1));
            }
        }
        Invariants.requireValid(next, seed, "落海结算后");
        if (next.isOver()) {
            return next;
        }

        // c) 口渴。候选不含死者，但含昏迷者 —— 他仍会口渴，只是不能自己打水。
        Set<CharacterId> thirstCandidates = new LinkedHashSet<>();
        for (CharacterId id : next.bySeat()) {
            if (next.conditionOf(id).suffersThirst()) {
                thirstCandidates.add(id);
            }
        }
        thirstCandidates.forEach(exposure::thirstChance);
        for (CharacterId id : card.thirst().select(thirstCandidates, noConditions)) {
            exposure.thirst(id);                 // 只数牌面点名，划船与战斗的口渴不在内
            next = next.withState(id, next.stateOf(id).thirstFrom(ThirstSource.NAMED));
        }

        // 按结算次序逐个算账。带「最后结算」标记的排在最后。
        for (var survivor : ThirstResolver.resolutionOrder(next.roster())) {
            CharacterId id = survivor.id();
            if (!next.conditionOf(id).suffersThirst()) {
                continue;
            }
            var tally = next.stateOf(id).thirst();
            // 牌上没有船桨/战斗图示时，对应的标记不产生口渴。
            var effective = tally;
            if (!card.thirstRowers()) {
                effective = withoutSource(effective, ThirstSource.ROWED);
            }
            if (!card.thirstFighters()) {
                effective = withoutSource(effective, ThirstSource.FOUGHT);
            }
            int water = rng.nextInt(effective.count() + 1);      // 随机决定喝几张
            var outcome = ThirstResolver.resolveSpendingUpTo(effective, 0, water);
            if (outcome.damage() > 0) {
                next = next.withState(id, next.stateOf(id).hurt(outcome.damage()));
            }
        }
        Invariants.requireValid(next, seed, "口渴结算后");
        return next;
    }

    private static io.github.heavyseasmc.engine.thirst.ThirstTally withoutSource(
            io.github.heavyseasmc.engine.thirst.ThirstTally t, ThirstSource s) {
        if (!t.has(s)) {
            return t;
        }
        Set<ThirstSource> kept = new LinkedHashSet<>(t.sources());
        kept.remove(s);
        return new io.github.heavyseasmc.engine.thirst.ThirstTally(kept);
    }

    /** 落海免伤（水手）。❗<b>要求清醒</b> —— 昏迷的水手落水照样受伤。 */
    private static boolean isOverboardImmune(GameState g, CharacterId id) {
        var ability = g.roster().get(id).ability();
        if (!(ability instanceof io.github.heavyseasmc.engine.model.Ability.OverboardImmune oi)) {
            return false;
        }
        return !oi.requiresConscious() || g.conditionOf(id) == Condition.CONSCIOUS;
    }

    private static int aliveCount(GameState g) {
        return (int) g.bySeat().stream().filter(id -> g.conditionOf(id) != Condition.DEAD).count();
    }

    /**
     * 一局的结果。
     *
     * @param seed     种子，失败时靠它复现
     * @param turns    走了几回合
     * @param outcome  终局原因
     * @param alive    终局时还活着几个人
     * @param fights   打了几架
     * @param exposure 每个角色被航海牌点到的次数与机会数，O1 的分布曲线由它汇总而来。
     *                 ❗<b>只含真的被结算过的回合</b>：海鸥当场结束一局时那张牌不结算落海，
     *                 所以那一回合两边都不计
     */
    public record Result(long seed, int turns, GameState.Outcome outcome, int alive, int fights,
                         Map<CharacterId, Exposure> exposure) {

        public Result {
            exposure = Map.copyOf(Objects.requireNonNull(exposure, "exposure"));
        }
    }

    /**
     * 累计点名次数与机会数。
     *
     * <p>可变，且只在一局之内活着 —— {@link Result} 拿到的是它的不可变快照。
     * 做成可变是因为它要被航海阶段每一步更新，而 {@link GameState} 的不可变性
     * 是为了「状态推进可回放」，与统计计数不是一回事，混在一起会让每次计数都复制一遍全局状态。
     */
    private static final class ExposureTally {

        private final Map<CharacterId, int[]> counts = new LinkedHashMap<>();

        private int[] of(CharacterId id) {
            return counts.computeIfAbsent(id, key -> new int[4]);
        }

        void overboardChance(CharacterId id) {
            of(id)[1]++;
        }

        void overboard(CharacterId id) {
            of(id)[0]++;
        }

        void thirstChance(CharacterId id) {
            of(id)[3]++;
        }

        void thirst(CharacterId id) {
            of(id)[2]++;
        }

        Map<CharacterId, Exposure> toMap() {
            Map<CharacterId, Exposure> out = new LinkedHashMap<>();
            counts.forEach((id, c) -> out.put(id, new Exposure(c[0], c[1], c[2], c[3])));
            return Map.copyOf(out);
        }
    }
}
