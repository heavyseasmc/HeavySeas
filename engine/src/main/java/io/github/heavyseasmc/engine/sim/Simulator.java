package io.github.heavyseasmc.engine.sim;

import io.github.heavyseasmc.engine.play.Invariants;
import io.github.heavyseasmc.engine.play.NavigationReport;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.play.Table;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Provision;
import io.github.heavyseasmc.engine.model.ProvisionEffect;
import io.github.heavyseasmc.engine.model.Provisions;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.navigation.NavigationDeck;
import io.github.heavyseasmc.engine.state.Condition;
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
    private final Provisions provisions;
    private final NavigationPolicy policy;

    /** 默认用对照组策略（留不留、挑哪张全看运气）。 */
    public Simulator(Roster roster, List<NavigationCard> deck, Provisions provisions) {
        this(roster, deck, provisions, NavigationPolicy.INDIFFERENT);
    }

    public Simulator(Roster roster, List<NavigationCard> deck, Provisions provisions,
                     NavigationPolicy policy) {
        this.roster = Objects.requireNonNull(roster, "roster");
        this.deck = List.copyOf(Objects.requireNonNull(deck, "deck"));
        this.provisions = Objects.requireNonNull(provisions, "provisions");
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
        Session session = new Session(context, roster,
                new Table(new NavigationDeck(deck, rng), provisions, rng));
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
                case PROVISION -> provision(session, rng);
                case ACTION -> fights += action(session, rng);
                case NAVIGATION -> navigate(session, rng, exposure);
            }
            Invariants.requireValidTransition(before, session.state(), seed, "阶段 " + before.phase(),
                    session.healedSincePhaseStart());
            if (session.state().isOver()) {
                break;
            }
            session.advancePhase();
        }
        GameState end = session.state();
        return new Result(seed, end.turn(), end.outcome().orElseThrow(), session.aliveCount(), fights,
                exposure.toMap());
    }

    /**
     * 物资阶段：船头抽 N 张，每人随机留一张传给下一位。
     *
     * <p>❗<b>这一段此前根本不存在</b>：模拟器连物资牌堆都没给，`provisionDraws()` 只被调用来算个数。
     * 于是几千局里没有人有过一张牌，而口渴结算时的回调却在「喝不存在的水」。
     * 物资建模之后这里必须发真牌，否则模拟器验的仍然是一份没人跑的实现（ADR-0021 §5.6）。
     */
    private void provision(Session session, Random rng) {
        List<String> offer = session.beginProvision();
        while (session.provisionInProgress()) {
            List<String> seen = session.provisionOffer();
            session.provisionKeep(seen.get(rng.nextInt(seen.size())));
        }
        if (!offer.isEmpty()) {
            session.requireNoProvisionLost("物资阶段之后");
        }
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
            // 亮牌与喝酒都不占行动，所以在选行动之前随机做。
            // ❗没有这一步，面前那一区永远是空的，被动效果（救生圈 · 阳伞 · 船桨 · 指南针 · 诱饵）
            //   就一次都不会生效 —— 几千局跑下来等于没测。
            maybeReveal(session, actor, rng);
            maybeDrink(session, actor, rng);
            switch (rng.nextInt(5)) {
                case 0 -> { }                                        // 什么都不做
                case 1 -> session.row(actor,
                        (card, state, rower) -> policy.keepWhenRowing(card, state, rower, rng));
                case 2 -> swapSeats(session, actor, rng);
                case 3 -> {
                    if (!maybeSpecial(session, actor, rng) && maybeFight(session, actor, rng)) {
                        fights++;           // 没有特殊行动可做就改打架，别白白浪费这一格
                    }
                }
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

    /** 随机亮出一张手牌。不占行动、不可逆 —— 这正是真人要权衡的那个取舍。 */
    private void maybeReveal(Session session, CharacterId actor, Random rng) {
        List<String> hand = session.state().stateOf(actor).hand();
        if (hand.isEmpty() || rng.nextInt(3) != 0) {
            return;
        }
        session.reveal(actor, hand.get(rng.nextInt(hand.size())));
    }

    /** 随机喝一口酒：本回合体型 +3，代价是回合结束时口渴一次。 */
    private void maybeDrink(Session session, CharacterId actor, Random rng) {
        if (rng.nextInt(4) != 0) {
            return;
        }
        var state = session.state().stateOf(actor);
        for (String cardId : available(session, actor)) {
            Provision card = session.provisions().get(cardId);
            if (card.effect() instanceof ProvisionEffect.BuffSize && !state.usedThisTurn(cardId)) {
                session.drinkRum(actor, cardId);
                return;
            }
        }
    }

    /**
     * 特殊行动：手上有能花行动打出的牌就随机打一张。
     *
     * @return 真的打了吗（手上没有这类牌、或者前提不满足时打不出来）
     */
    private boolean maybeSpecial(Session session, CharacterId actor, Random rng) {
        List<String> playable = new ArrayList<>();
        for (String cardId : available(session, actor)) {
            if (session.provisions().get(cardId).isSpecialAction()) {
                playable.add(cardId);
            }
        }
        while (!playable.isEmpty()) {
            String cardId = playable.remove(rng.nextInt(playable.size()));
            if (playSpecial(session, actor, cardId)) {
                return true;
            }
        }
        return false;
    }

    /** @return 前提满足、真的打出去了吗 */
    private boolean playSpecial(Session session, CharacterId actor, String cardId) {
        GameState g = session.state();
        ProvisionEffect effect = session.provisions().get(cardId).effect();
        if (effect instanceof ProvisionEffect.Heal) {
            // 医疗箱只能治「受了伤而且还没死」的人 —— 治没受伤的人或治尸体，引擎都会抛。
            for (CharacterId target : g.bySeat()) {
                if (g.stateOf(target).damage() > 0 && g.conditionOf(target) != Condition.DEAD) {
                    session.useMedicalKit(actor, target, cardId);
                    return true;
                }
            }
            return false;
        }
        if (effect instanceof ProvisionEffect.PreventThirst) {
            if (g.stateOf(actor).isOpen(cardId)) {
                return false;                 // 已经撑开了，再撑一次只是白花一个行动
            }
            session.openParasol(actor, cardId);
            return true;
        }
        if (effect instanceof ProvisionEffect.HealAll heal) {
            boolean corpse = g.bySeat().stream().anyMatch(id -> g.conditionOf(id) == Condition.DEAD);
            if (heal.requiresCorpse() && !corpse) {
                return false;
            }
            session.useRation(actor, cardId);
            return true;
        }
        if (effect instanceof ProvisionEffect.WeaponOrSpecial) {
            session.fireSignal(actor, cardId);
            return true;
        }
        return false;
    }

    /**
     * 口渴结算：随机喝掉自己手上的一部分水，偶尔有别人替他打一张。
     *
     * <h2>为什么「别人给水」只是偶尔</h2>
     * 规则上谁都可以在这一刻把水打给口渴的人，但那是一次<b>谈判</b>，而模拟器不做 AI。
     * 让全船的水自由流动会让几乎没有人渴死，那条曲线不比「随手数牌」多出任何信息。
     * 所以默认只喝自己的，留一个小概率让「别人替他打水」这条路也被走到 ——
     * ❗<b>一条永远走不到的分支等于没有分支。</b>
     *
     * <p>昏迷者<b>自己不能打水</b>（规则 §9.3），所以他那一份只可能来自别人。
     */
    private static List<CharacterId> water(Session session, Session.ThirstPrompt prompt, Random rng) {
        GameState g = session.state();
        List<CharacterId> donors = new ArrayList<>();
        boolean canSpendOwn = g.conditionOf(prompt.who()).canAct();
        int own = canSpendOwn ? prompt.ownWaters() : 0;
        int drink = Math.min(prompt.remaining(), own == 0 ? 0 : rng.nextInt(own + 1));
        for (int i = 0; i < drink; i++) {
            donors.add(prompt.who());
        }
        if (donors.size() < prompt.remaining() && rng.nextInt(4) == 0) {
            for (CharacterId other : g.bySeat()) {
                if (other.equals(prompt.who()) || !g.conditionOf(other).canAct()) {
                    continue;
                }
                if (session.watersOf(other) > 0) {
                    donors.add(other);
                    break;
                }
            }
        }
        return donors;
    }

    /** 他现在能打出来的牌：手上的 + 面前的（酒与伞在面前照样能用）。 */
    private static List<String> available(Session session, CharacterId actor) {
        var state = session.state().stateOf(actor);
        List<String> all = new ArrayList<>(state.hand());
        all.addAll(state.front());
        return all;
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
        // 打武器：❗<b>只打手上或面前真的有的那几张</b>。
        //   此前这里是 `fight.arm(c, 1 + rng.nextInt(8))` —— 凭空造出一把武器，
        //   与「喝不存在的水」是同一种失真，而战力分布直接决定谁输谁死。
        for (CharacterId c : fight.combatants()) {
            for (String cardId : available(session, c)) {
                if (session.provisions().get(cardId).weaponPower() > 0 && rng.nextInt(3) == 0) {
                    fight = session.playWeapon(fight, c, cardId);
                }
            }
        }
        session.applyFight(fight);
        return true;
    }

    /** 航海阶段：舵手从划船堆里挑一张（没人划船或全员昏迷就翻顶牌），结算后累计曝光。 */
    private void navigate(Session session, Random rng, ExposureTally exposure) {
        session.prepareRowStack();           // 舵手握着指南针时，挑牌之前多抽一张进划船堆（设计决策 §8.1）
        NavigationCard pick = null;
        if (session.helmsmanMayPick()) {
            pick = policy.pick(session.table().rowStack(), session.state(),
                    session.state().helmsman().orElseThrow(), rng);
        }
        NavigationCard card = session.takeCardForNavigation(pick);
        NavigationReport report = session.navigate(card, (prompt, state) -> water(session, prompt, rng));

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
