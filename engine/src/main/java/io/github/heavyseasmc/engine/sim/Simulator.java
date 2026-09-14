package io.github.heavyseasmc.engine.sim;

import io.github.heavyseasmc.engine.play.Invariants;
import io.github.heavyseasmc.engine.play.NavigationReport;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.play.Table;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.navigation.NavigationDeck;
import io.github.heavyseasmc.engine.state.Fight;
import io.github.heavyseasmc.engine.state.GameState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

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

    /** 划船一次看几张牌。规则本身在 {@link Session}，这里只转发，不另写一个 2。 */
    public static final int CARDS_DRAWN_WHEN_ROWING = Session.CARDS_DRAWN_WHEN_ROWING;

    private final Roster roster;
    private final List<NavigationCard> deck;
    private final NavigationPolicy policy;

    /** 默认用对照组策略（留不留、挑哪张全看运气）。 */
    public Simulator(Roster roster, List<NavigationCard> deck) {
        this(roster, deck, NavigationPolicy.INDIFFERENT);
    }

    public Simulator(Roster roster, List<NavigationCard> deck, NavigationPolicy policy) {
        this.roster = Objects.requireNonNull(roster, "roster");
        this.deck = List.copyOf(Objects.requireNonNull(deck, "deck"));
        this.policy = Objects.requireNonNull(policy, "policy");
        if (this.deck.isEmpty()) {
            throw new IllegalArgumentException("航海牌堆不能为空 —— 没有牌就永远结束不了");
        }
    }

    public NavigationPolicy policy() {
        return policy;
    }

    /**
     * 跑一整局。
     *
     * @throws IllegalStateException 出现非法状态，或超过回合上限（疑似死锁）
     */
    public Result run(long seed) {
        // 一条随机流：洗牌与全部决策共用它，同一个种子才能完整复现一局。
        // ❗回调的调用次序与次数是可复现性的一部分，改动 Session 里的循环结构会让同一个种子跑出不同的局。
        Random rng = new Random(seed);
        String context = "seed=%d".formatted(seed);
        Session session = new Session(context, roster, new Table(new NavigationDeck(deck, rng)));
        ExposureTally exposure = new ExposureTally();

        int fights = 0;
        while (!session.state().isOver()) {
            if (session.state().turn() > TURN_LIMIT) {
                throw new IllegalStateException(
                        "seed=%d 超过 %d 回合仍未结束，疑似死锁（海鸥 %d，存活 %d）"
                                .formatted(seed, TURN_LIMIT, session.state().gulls(), session.aliveCount()));
            }
            GameState before = session.state();
            switch (before.phase()) {
                case PROVISION -> session.provisionDraws();
                case ACTION -> fights += action(session, rng);
                case NAVIGATION -> navigate(session, rng, exposure);
            }
            Invariants.requireValidTransition(before, session.state(), seed, "阶段 " + before.phase());
            if (session.state().isOver()) {
                break;
            }
            session.advancePhase();
        }
        GameState end = session.state();
        return new Result(seed, end.turn(), end.outcome().orElseThrow(), session.aliveCount(), fights,
                exposure.toMap());
    }

    /** 行动阶段：按「最靠船头且未行动」取人，每人随机做一件事。 */
    private int action(Session session, Random rng) {
        int fights = 0;
        int guard = 0;
        while (true) {
            var next = session.nextActor();
            if (next.isEmpty()) {
                break;                       // ❗没人能行动是合法状态，不是死锁
            }
            if (++guard > roster.size() * 4) {
                throw new IllegalStateException(
                        "%s 行动阶段推不动：nextActor 一直返回同一个人，标记没写回"
                                .formatted(session.context()));
            }
            CharacterId actor = next.get();
            switch (rng.nextInt(4)) {
                case 0 -> { }                                        // 什么都不做
                case 1 -> session.row(actor,
                        (card, state, rower) -> policy.keepWhenRowing(card, state, rower, rng));
                case 2 -> swapSeats(session, actor, rng);
                default -> {
                    if (maybeFight(session, actor, rng)) {
                        fights++;
                    }
                }
            }
            session.markActed(actor);
        }
        return fights;
    }

    /** 换座位：与任意角色交换，不限相邻。❗没有可换的人时<b>不消耗随机数</b>。 */
    private void swapSeats(Session session, CharacterId actor, Random rng) {
        List<CharacterId> others = new ArrayList<>(session.state().bySeat());
        others.remove(actor);
        if (others.isEmpty()) {
            return;
        }
        session.swapSeats(actor, others.get(rng.nextInt(others.size())));
    }

    /**
     * 抢夺被拒 → 打一架。
     *
     * @return 是否真的打了（没有清醒的对手时打不起来）
     */
    private boolean maybeFight(Session session, CharacterId actor, Random rng) {
        List<CharacterId> targets = session.fightTargets(actor);
        if (targets.isEmpty()) {
            return false;
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
        session.applyFight(fight);
        return true;
    }

    /** 航海阶段：舵手从划船堆里挑一张（没人划船或全员昏迷就翻顶牌），结算后累计曝光。 */
    private void navigate(Session session, Random rng, ExposureTally exposure) {
        NavigationCard pick = null;
        if (session.helmsmanMayPick()) {
            pick = policy.pick(session.table().rowStack(), session.state(),
                    session.state().helmsman().orElseThrow(), rng);
        }
        NavigationCard card = session.takeCardForNavigation(pick);
        NavigationReport report = session.navigate(card,
                (who, effective, state) -> rng.nextInt(effective.count() + 1));

        // 曝光率从结算报告里数，而不是在结算过程中埋钩子 ——
        // 埋钩子的话，模拟器看到的与模组看到的可能不是同一件事。
        report.overboardCandidates().forEach(exposure::overboardChance);
        report.overboardSelected().forEach(exposure::overboard);
        report.thirstCandidates().forEach(exposure::thirstChance);
        report.thirstSelected().forEach(exposure::thirst);
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
