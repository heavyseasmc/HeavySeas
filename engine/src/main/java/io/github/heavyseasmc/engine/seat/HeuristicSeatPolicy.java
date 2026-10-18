package io.github.heavyseasmc.engine.seat;

import io.github.heavyseasmc.engine.model.Ability;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Provision;
import io.github.heavyseasmc.engine.model.ProvisionEffect;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.play.Contest;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.state.Condition;
import io.github.heavyseasmc.engine.state.Fight;
import io.github.heavyseasmc.engine.weather.WeatherEffect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * 会动脑的替身，第一层：<b>按处境打分</b>。不往前推演，只看此刻。
 *
 * <h2>怎么想</h2>
 * 每个决定的每个合法选项，都折算成「我最后大概多拿或少拿几分」（{@link Outlook#ev()}）：
 * 照着做一遍会变成什么样（谁挨了伤、谁淹死、谁少了一张水、我多了什么财宝），再估一次分，减去不做时的那一次。
 * 于是常识不必一条条写死，而是从计分规则里长出来：
 * <ul>
 *   <li>口渴有水就喝（挨伤是确定的，留水是不确定的）；快昏的人先喝；</li>
 *   <li>不打爱的人（他挨伤、他少一张牌，都是我的分）；站队站爱的人那边、站恨的人对面；</li>
 *   <li>恨的人死了我拿他的<b>体型</b> —— 所以舵手挑牌偏爱把恨的人送下水、别把自己和爱的人送下水；</li>
 *   <li>押武器只在押了能翻盘时押；明知打不赢的架不接，打得赢的架才拒绝；</li>
 *   <li>补给箱里挑此刻最值钱的：渴的时候是水，爱替我翻倍的那一类财宝……</li>
 * </ul>
 *
 * <h2>记得谁帮过我</h2>
 * 公开记事里谁递过水、送过牌、站过我这边、扔过救生圈，谁抢过我、打过我、站过对面、扔过血饵 ——
 * 折成一点「盼他活 / 盼他死」的倾向（{@link Outlook#social}，分量与衰减在 {@link SeatPolicySettings}）。
 * 它进同一个估分，所以帮过我的人渴了我更愿意递水、站队更愿意站他那边、抢的时候先抢害过我的人。
 *
 * <h2>随机性</h2>
 * 打分最高的未必总是它：{@link SeatPolicySettings#temperature()} 大于 0 时按 softmax 挑（分差越大越少挑次的），
 * 等于 0 时永远挑最高的那一项、一个随机数都不消费。
 *
 * <h2>只读视角</h2>
 * 拿到的只有 {@link SeatView}，别人手里有几张水只能估 —— 它不会、也不可能偷看。
 *
 * <h2>没有按局的状态：一个实例全船共用、哪个线程都能调</h2>
 * 它不记任何东西 —— 「谁帮过我」读的是视角里的公开记事（{@link SeatView#deeds()}），估值每次现算。
 * 所以一个实例可以给全船每个座位、每一局用，几个工作线程同时调也不相干扰（唯一的共享是按牌堆构成缓存的统计，线程安全）。
 * 随机数只从参数里的 {@code rng} 拿（温度为 0 时一个都不拿）：要可复现，就别让几个线程共用同一个 {@code Random}。
 *
 * <p>它只用 {@link SeatPolicySettings} 里的 {@code temperature} 与三项恩怨；推演的那几项（{@code search} 往后）归第二层。
 */
public final class HeuristicSeatPolicy implements SeatPolicy {

    // ---------------------------------------------------------------- 设计常数（不给服主调）

    /** 「打爱的人」的硬罚分：别的选项永远比它好。 */
    static final double HARM_LOVED = 10.0;
    /** 抢一个不是我恨的人，日后可能被报复：每次扣这么多。 */
    static final double RETALIATION = 0.2;
    /** 押下一张武器（从此亮在面前，落水会被卷走、会被人指名抢）的代价。 */
    static final double EXPOSURE = 0.1;
    /** 送牌至少要多出这么多分才送（送出去收不回来）。 */
    static final double GIFT_THRESHOLD = 0.35;
    /** 落海时打牌至少要多出这么多分才打。 */
    static final double OVERBOARD_THRESHOLD = 0.15;
    /** 估「舵手挑牌」值多少时抽样的次数。 */
    static final int HELM_SAMPLES = 24;

    private final SeatPolicySettings settings;
    private final Outlook.Objective objective;

    public HeuristicSeatPolicy(SeatPolicySettings settings) {
        this(settings, Outlook.Objective.NORMAL);
    }

    /** 只给红测用：爱恨对调，或者整个估分反过来（「盼自己输」）。 */
    HeuristicSeatPolicy(SeatPolicySettings settings, Outlook.Objective objective) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.objective = Objects.requireNonNull(objective, "objective");
    }

    public SeatPolicySettings settings() {
        return settings;
    }

    Outlook outlook(SeatView view) {
        return new Outlook(view, settings, objective);
    }

    /** 常数分的正负：估分整个反过来时，那些「这张牌大概值多少」的常数也跟着反。 */
    private double sign() {
        return objective == Outlook.Objective.INVERTED ? -1 : 1;
    }

    // ---------------------------------------------------------------- 物资

    @Override
    public String keepProvision(SeatView view, List<String> offer, Random rng) {
        List<String> options = provisionOptions(offer);
        return options.get(choose(scoreProvisions(view, options), rng));
    }

    /** 补给箱里不同的几张（同 id 没有区别），按出现的先后。 */
    static List<String> provisionOptions(List<String> offer) {
        return List.copyOf(new LinkedHashSet<>(offer));
    }

    /** 每一张留下来值几分（推演层拿它剪枝）。 */
    double[] scoreProvisions(SeatView view, List<String> options) {
        Outlook o = outlook(view);
        double base = o.ev();
        double[] scores = new double[options.size()];
        for (int i = 0; i < scores.length; i++) {
            scores[i] = hold(o, base, options.get(i));
        }
        return scores;
    }

    /**
     * 我多拿到一张 {@code card} 值几分。
     *
     * <p>同一个决定里会反复问同一张（抢谁都要估「摸到一张水值几分」）：按这一份估值记下来。
     * 估值 {@code o} 在一个决定里从不改（推想改的都是副本），所以记下来的与现算的逐位相同。
     */
    double hold(Outlook o, double base, String card) {
        if (o.holdMemo == null || o.holdBase != base) {
            o.holdMemo = new java.util.HashMap<>();
            o.holdBase = base;
        }
        Double memo = o.holdMemo.get(card);
        if (memo == null) {
            memo = holdNow(o, base, card);
            o.holdMemo.put(card, memo);
        }
        return memo;
    }

    private double holdNow(Outlook o, double base, String card) {
        SeatView view = o.view;
        Provision p = view.catalog().get(card);
        ProvisionEffect e = p.effect();
        if (e instanceof ProvisionEffect.ScoreFlat || e instanceof ProvisionEffect.ScoreSet) {
            Outlook c = o.copy();
            List<String> hand = new ArrayList<>(view.hand());
            hand.add(card);
            c.myTreasure = Outlook.treasureOf(view, hand, view.me().front());
            return c.ev() - base;
        }
        if (Outlook.isWater(p)) {
            Outlook c = o.copy();
            c.water[o.me] += 1;
            return c.ev() - base + 0.05 * sign();              // 水还能递给别人：同分时偏向水
        }
        if (e instanceof ProvisionEffect.PreventOverboardDamage) {
            if (o.ring[o.me]) {
                return 0.2 * sign() * usable(o);
            }
            Outlook c = o.copy();
            c.ring[o.me] = true;
            return c.ev() - base;
        }
        if (e instanceof ProvisionEffect.Heal heal) {
            double best = 0.5 * sign() * usable(o);
            for (int j : List.of(o.me, o.love)) {
                if (j >= 0 && o.alive[j] && o.margin[j] < o.size[j]) {
                    Outlook c = o.copy();
                    c.heal(j, heal.amount());
                    best = Math.max(best, c.ev() - base);
                }
            }
            return best + (heal.discardedBy(view.self()) ? 0 : 0.5 * sign() * usable(o));
        }
        if (e instanceof ProvisionEffect.PreventThirst cover && cover.persistent()) {
            Outlook c = o.copy();
            c.water[o.me] += Math.min(o.days(), 4) * 0.6;     // 撑开以后每天挡一次，要花一个行动撑开
            return c.ev() - base - 0.2 * sign() * usable(o);
        }
        // 下面这几样要人活着、醒着才用得上：按我活到用它的那一天的可能打折（快死的人拿把短棍没什么用）
        double usable = usable(o) * sign();
        if (e instanceof ProvisionEffect.HealAll) {
            return 0.3 * usable;
        }
        if (e instanceof ProvisionEffect.BuffSize) {
            return 0.4 * usable;
        }
        if (e instanceof ProvisionEffect.NavigatorExtraDraw) {
            return (view.helmsman().map(view.self()::equals).orElse(false) ? 0.6 : 0.15) * usable;
        }
        if (e instanceof ProvisionEffect.WeaponOrSpecial) {
            return 1.2 * usable;
        }
        if (e instanceof ProvisionEffect.WeaponAndRowBonus) {
            return 0.35 * usable;
        }
        if (e instanceof ProvisionEffect.Weapon w) {
            return 0.2 * w.power() * usable;
        }
        if (e instanceof ProvisionEffect.DamageInWater) {
            boolean hatedLow = o.hate >= 0 && o.hate != o.me && o.alive[o.hate] && o.margin[o.hate] <= 2;
            return (hatedLow ? 0.55 : 0.25) * usable;
        }
        return 0;
    }

    /** 我能活到、醒着用上一张牌的可能（用它打折那些「留着以后用」的牌）。 */
    private static double usable(Outlook o) {
        return o.alive[o.me] && o.margin[o.me] > 0 ? 1 - o.pDie(o.me) : 0;
    }

    // ---------------------------------------------------------------- 不占行动的三件事

    /** 手里有救生圈、面前还没有：亮出来（攥在手里不挡落海）。别的都不亮 —— 亮了会被浪卷走、会被人指名抢。 */
    @Override
    public Optional<String> reveal(SeatView view, List<String> revealable, Random rng) {
        if (objective == Outlook.Objective.INVERTED) {
            return Optional.empty();
        }
        boolean shown = false;
        for (String c : view.me().front()) {
            if (view.catalog().get(c).effect() instanceof ProvisionEffect.PreventOverboardDamage) {
                shown = true;
            }
        }
        if (shown) {
            return Optional.empty();
        }
        for (String c : revealable) {
            if (view.catalog().get(c).effect() instanceof ProvisionEffect.PreventOverboardDamage) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    /** 开始行动之前不喝 —— 酒留到真打起来、押武器之前再喝（{@link #drinkForFight}），那时才知道值不值。 */
    @Override
    public Optional<String> drink(SeatView view, List<String> drinkable, Random rng) {
        return Optional.empty();
    }

    /** 送牌：把水、救生圈送给更需要的人（多半是爱的人、帮过我的人），只在多出的分够多时送。 */
    @Override
    public Optional<Gift> give(SeatView view, List<Gift> gifts, Random rng) {
        Outlook o = outlook(view);
        double base = o.ev();
        Gift best = null;
        double bestGain = GIFT_THRESHOLD;
        for (Gift g : gifts) {
            Provision p = view.catalog().get(g.card());
            boolean water = Outlook.isWater(p);
            boolean ring = p.effect() instanceof ProvisionEffect.PreventOverboardDamage;
            if (!water && !ring) {
                continue;
            }
            int to = o.index(g.to());
            if (!o.alive[to]) {
                continue;
            }
            Outlook c = o.copy();
            if (water) {
                c.water[o.me] -= 1;
                c.water[to] += 1;
            } else {
                if (o.ring[to]) {
                    continue;
                }
                c.ring[o.me] = ringsLeft(view, g) > 0;
                c.ring[to] = g.fromFront();                     // 手里的救生圈送过去也在他手里，要他自己亮出来
            }
            double gain = c.ev() - base;
            if (gain > bestGain) {
                bestGain = gain;
                best = g;
            }
        }
        return Optional.ofNullable(best);
    }

    /** 送掉这一张之后，我手里与面前还剩几个救生圈。 */
    private static int ringsLeft(SeatView view, Gift g) {
        int n = 0;
        for (String c : view.hand()) {
            if (view.catalog().get(c).effect() instanceof ProvisionEffect.PreventOverboardDamage) {
                n++;
            }
        }
        for (String c : view.me().front()) {
            if (view.catalog().get(c).effect() instanceof ProvisionEffect.PreventOverboardDamage) {
                n++;
            }
        }
        return n - 1;
    }

    // ---------------------------------------------------------------- 行动

    @Override
    public ActionChoice act(SeatView view, List<ActionChoice> legal, Random rng) {
        Outlook o = outlook(view);
        double base = o.ev();
        CardValues cards = new CardValues(o, base);
        double[] scores = new double[legal.size()];
        for (int i = 0; i < scores.length; i++) {
            scores[i] = score(o, base, cards, legal.get(i));
        }
        return legal.get(choose(scores, rng));
    }

    double score(Outlook o, double base, CardValues cards, ActionChoice choice) {
        return switch (choice) {
            case ActionChoice.Pass pass -> 0;
            case ActionChoice.Row row -> rowValue(o, base, cards);
            case ActionChoice.Declare d -> d.kind() == Contest.Kind.SWAP
                    ? swapValue(o, base, cards, o.index(d.target()))
                    : stealValue(o, base, o.index(d.target()));
            case ActionChoice.Play p -> playValue(o, base, p);
        };
    }

    /**
     * 划船：多摸两张、挑一张塞进划船堆。我是舵手时等于多了一张可挑；不是时我塞进去的那张可能被挑中。
     * 代价是背上划船标记 —— 执行的牌印着船桨图示就渴一次。
     */
    double rowValue(Outlook o, double base, CardValues cards) {
        SeatView view = o.view;
        int draws = Session.CARDS_DRAWN_WHEN_ROWING;
        for (String c : view.me().front()) {
            if (view.catalog().get(c).effect() instanceof ProvisionEffect.WeaponAndRowBonus oar) {
                draws += oar.rowExtraDraw();
            }
        }
        boolean helm = view.helmsman().map(view.self()::equals).orElse(false);
        int stack = view.table().rowStack();
        double gain = helm ? cards.helmGain(stack, draws) : cards.rowerGain(stack, draws);
        return gain - o.oarIcon * thirstCost(o, base);
    }

    /** 我多渴一次要付的分：有水就是一张水，没水就是一点伤。 */
    double thirstCost(Outlook o, double base) {
        Outlook c = o.copy();
        c.thirst(o.me, 1, 1);
        return base - c.ev();
    }

    /** 我打一架背上的标记：执行的牌印着打架图示就渴一次。 */
    double foughtCost(Outlook o, double base) {
        return o.fightIcon * thirstCost(o, base);
    }

    /**
     * 换座位：最要紧的是舵手（最靠船尾的清醒的人）。对方昏迷或是尸体就直接换；清醒的话他可能拒绝、打起来。
     */
    double swapValue(Outlook o, double base, CardValues cards, int t) {
        SeatView view = o.view;
        if (t == o.love && o.alive[t] && view.seats().get(t).canAct()) {
            return -HARM_LOVED * sign();
        }
        double seat = seatValue(o, cards, t);
        if (!view.seats().get(t).canAct()) {
            return seat - 0.05 * sign();
        }
        double attack = myStrength(o);
        double defend = o.strength(t) + o.hiddenPower(t);
        double pWin = Outlook.pAttackWins(attack, defend, Outlook.FIGHT_SIGMA_OPEN);
        double pRefuse = refuseOdds(pWin, seat);
        double win = delta(o, base, c -> c.hurt(t, 1)) + seat;
        double lose = delta(o, base, c -> c.hurt(o.me, 1));
        double fight = pWin * win + (1 - pWin) * lose - foughtCost(o, base);
        return (1 - pRefuse) * seat + pRefuse * fight - (t == o.hate ? 0 : RETALIATION * sign());
    }

    /** 和 {@code t} 换了座位之后，舵手是谁、对我值几分。 */
    double seatValue(Outlook o, CardValues cards, int t) {
        SeatView view = o.view;
        List<SeatView.SeatInfo> active = new ArrayList<>();
        int mySeat = view.me().seat();
        int theirSeat = view.seats().get(t).seat();
        CharacterId before = view.helmsman().orElse(null);
        // 换过以后最靠船尾的清醒在线者
        CharacterId after = null;
        int bestSeat = -1;
        for (SeatView.SeatInfo s : view.seats()) {
            if (!s.canAct()) {
                continue;
            }
            int seat = s.id().equals(view.self()) ? theirSeat : s.id().equals(o.ids[t]) ? mySeat : s.seat();
            if (seat > bestSeat) {
                bestSeat = seat;
                after = s.id();
            }
        }
        if (Objects.equals(before, after)) {
            return 0;
        }
        double helmValue = cards.helmValue();
        return helmValue * (stake(o, after) - stake(o, before));
    }

    /** 某人当舵手，对我有多大好处（-1..1）：我自己 1，爱的人 0.6，恨的人 -0.6，其余按恩怨。 */
    private double stake(Outlook o, CharacterId who) {
        if (who == null) {
            return 0;
        }
        int j = o.index(who);
        if (j == o.me) {
            return sign();
        }
        if (j == o.love) {
            return 0.6 * sign();
        }
        if (j == o.hate) {
            return -0.6 * sign();
        }
        return 0.3 * o.social[j] / Outlook.SOCIAL * sign();
    }

    /** 估对方会不会拒绝：他越可能打赢、我要的东西对他越要紧，越会拒绝。 */
    private static double refuseOdds(double pAttackWins, double stakeForMe) {
        double p = 0.25 + 0.6 * (1 - pAttackWins) + 0.1 * Math.min(1, Math.abs(stakeForMe));
        return Math.max(0.05, Math.min(0.95, p));
    }

    /** 我在一架里的力气：体型 + 酒（喝过的，或者手里还没喝、打起来能喝的那瓶）+ 我能押的全部武器。 */
    double myStrength(Outlook o) {
        SeatView view = o.view;
        double s = view.me().strength();
        if (!view.me().used().stream().anyMatch(c -> view.catalog().get(c).effect() instanceof ProvisionEffect.BuffSize)) {
            for (String c : available(view)) {
                if (view.catalog().get(c).effect() instanceof ProvisionEffect.BuffSize buff) {
                    s += buff.amount();
                    break;
                }
            }
        }
        return s + o.myWeaponPower();
    }

    /**
     * 抢：拿到一张对我有用的牌（他面前挑最好的，或者从他手里摸一张），他少了它；
     * 对方清醒就可能拒绝、打起来。小孩的偷窃不问、不打，只摸手牌。
     */
    double stealValue(Outlook o, double base, int t) {
        SeatView view = o.view;
        SeatView.SeatInfo victim = view.seats().get(t);
        if (t == o.love && o.alive[t]) {
            return -HARM_LOVED * sign();
        }
        boolean handOnly = view.me().ability() instanceof Ability.StealUncontested steal && "hand".equals(steal.zone());
        double front = Double.NEGATIVE_INFINITY;
        if (!handOnly) {
            for (String c : victim.front()) {
                front = Math.max(front, hold(o, base, c) + denial(o, base, t, c, true));
            }
        }
        double hand = victim.handCount() > 0 ? handPickValue(o, base, t) : Double.NEGATIVE_INFINITY;
        double gain = Math.max(front, hand);
        if (gain == Double.NEGATIVE_INFINITY) {
            return -0.05 * sign();                          // 他身上什么也没有：白白用掉行动
        }
        double grudge = t == o.hate ? 0 : RETALIATION * sign();
        if (handOnly || !victim.canAct()) {
            return gain - grudge;
        }
        double attack = myStrength(o);
        double defend = o.strength(t) + o.hiddenPower(t);
        double pWin = Outlook.pAttackWins(attack, defend, Outlook.FIGHT_SIGMA_OPEN);
        double pRefuse = refuseOdds(pWin, gain);
        double win = gain + delta(o, base, c -> c.hurt(t, 1));
        double lose = delta(o, base, c -> c.hurt(o.me, 1));
        double fight = pWin * win + (1 - pWin) * lose - foughtCost(o, base);
        return (1 - pRefuse) * gain + pRefuse * fight - grudge;
    }

    /** 从他手里随机摸一张值几分：按没见过的牌的构成估。 */
    double handPickValue(Outlook o, double base, int t) {
        if (o.unseenMemo == null) {
            o.unseenMemo = unseenPool(o.view);                // 同一个视角：抢谁都一样
        }
        Map<String, Integer> pool = o.unseenMemo;
        int total = pool.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) {
            return 0;
        }
        double v = 0;
        for (Map.Entry<String, Integer> e : pool.entrySet()) {
            if (e.getValue() <= 0) {
                continue;
            }
            v += e.getValue() * (hold(o, base, e.getKey()) + denial(o, base, t, e.getKey(), false));
        }
        return v / total;
    }

    /** 他少了这一张，对我值几分（他是我恨的人时为正，是我爱的人时为负）。只算会改生死的那几种。 */
    double denial(Outlook o, double base, int t, String card, boolean fromFront) {
        Provision p = o.view.catalog().get(card);
        if (Outlook.isWater(p)) {
            return delta(o, base, c -> c.water[t] = Math.max(0, c.water[t] - 1));
        }
        if (fromFront && p.effect() instanceof ProvisionEffect.PreventOverboardDamage) {
            return delta(o, base, c -> c.ring[t] = false);
        }
        return 0;
    }

    /** 用物资：医疗箱治谁、撑伞、分食、信号枪。 */
    double playValue(Outlook o, double base, ActionChoice.Play play) {
        SeatView view = o.view;
        ProvisionEffect e = view.catalog().get(play.card()).effect();
        if (e instanceof ProvisionEffect.Heal heal) {
            int t = o.index(play.target().orElseThrow());
            double cost = heal.discardedBy(view.self()) ? 0.4 * sign() : 0;
            return delta(o, base, c -> c.heal(t, heal.amount())) - cost;
        }
        if (e instanceof ProvisionEffect.PreventThirst) {
            return delta(o, base, c -> c.water[o.me] += Math.min(o.days(), 4) * 0.6);
        }
        if (e instanceof ProvisionEffect.HealAll heal) {
            double gain = delta(o, base, c -> {
                for (int j = 0; j < c.n; j++) {
                    if (view.seats().get(j).condition() == Condition.CONSCIOUS && c.margin[j] < c.size[j]) {
                        c.heal(j, heal.amount());
                    }
                }
            });
            return gain - 0.3 * sign();                     // 有人反对就要打一架，牌也拿不回来
        }
        if (e instanceof ProvisionEffect.WeaponOrSpecial w) {
            return signalValue(o, base, w) - 1.0 * sign();  // 当信号用掉，就没有 +8 的那一张了
        }
        return 0;
    }

    /** 信号枪：翻三张、只算海鸥。按整副牌里 +1 / 0 / -1 的比例估。浓雾天海鸥不算。 */
    double signalValue(Outlook o, double base, ProvisionEffect.WeaponOrSpecial w) {
        if (o.view.weather().map(x -> x.effect() == WeatherEffect.IGNORE_GULLS).orElse(false)) {
            return 0;
        }
        double up = 0;
        double down = 0;
        for (NavigationCard c : o.view.navDeck()) {
            if (c.gull() > 0) {
                up++;
            } else if (c.gull() < 0) {
                down++;
            }
        }
        int cards = Math.max(1, o.view.navDeck().size());
        double pu = up / cards;
        double pd = w.special().includesGullRemoval() ? down / cards : 0;
        double p0 = 1 - pu - pd;
        double total = 0;
        int draws = w.special().draw();
        // 三张（一般情况）逐一枚举：+1 / 0 / -1 各自的概率连乘
        int[] steps = {1, 0, -1};
        double[] probs = {pu, p0, pd};
        int combos = (int) Math.pow(3, draws);
        for (int k = 0; k < combos; k++) {
            int sum = 0;
            double prob = 1;
            int x = k;
            for (int d = 0; d < draws; d++) {
                sum += steps[x % 3];
                prob *= probs[x % 3];
                x /= 3;
            }
            if (prob == 0) {
                continue;
            }
            int s = sum;
            total += prob * delta(o, base, c -> c.gull(s));
        }
        return total;
    }

    // ---------------------------------------------------------------- 划船与掌舵

    @Override
    public int keepRowCard(SeatView view, List<NavigationCard> drawn, Random rng) {
        return choose(scoreCards(view, drawn), rng);
    }

    @Override
    public NavigationCard steer(SeatView view, List<NavigationCard> rowStack, Random rng) {
        return rowStack.get(choose(scoreCards(view, rowStack), rng));
    }

    /** 每一张航海牌若执行，此刻对我值几分（划船留牌与舵手挑牌共用；推演层拿它剪枝）。 */
    double[] scoreCards(SeatView view, List<NavigationCard> cards) {
        Outlook o = outlook(view);
        double base = o.ev();
        double[] scores = new double[cards.size()];
        for (int i = 0; i < scores.length; i++) {
            NavigationCard card = cards.get(i);
            scores[i] = delta(o, base, c -> c.apply(card));
        }
        return scores;
    }

    // ---------------------------------------------------------------- 这一场

    /**
     * 被指了：同意就让他拿走一张 / 换走座位；拒绝就打一架（平手算我赢），打输了照样被拿走、还挨一点伤。
     * 分食的反对：同意就让大家（含我的仇人）各回一点；反对就打一架。
     */
    @Override
    public boolean refuse(SeatView view, Random rng) {
        return choose(scoreRefuse(view), rng) == 1;
    }

    /** {@code [同意, 拒绝]} 各值几分。 */
    double[] scoreRefuse(SeatView view) {
        SeatView.ContestInfo c = view.contest().orElseThrow();
        Outlook o = outlook(view);
        double base = o.ev();
        int a = o.index(c.attacker());
        double attack = o.strength(a) + o.hiddenPower(a);
        double defend = myStrength(o);
        double pAtt = Outlook.pAttackWins(attack, defend, Outlook.FIGHT_SIGMA_OPEN);
        double fought = foughtCost(o, base);
        double agree;
        double refuse;
        if (c.kind() == Contest.Kind.RATION) {
            ProvisionEffect.HealAll heal = (ProvisionEffect.HealAll) view.catalog().get(c.rationCard()).effect();
            java.util.function.Consumer<Outlook> ration = x -> {
                for (int j = 0; j < x.n; j++) {
                    if (view.seats().get(j).condition() == Condition.CONSCIOUS && x.margin[j] < x.size[j]) {
                        x.heal(j, heal.amount());
                    }
                }
            };
            agree = delta(o, base, ration);
            refuse = pAtt * delta(o, base, x -> {
                ration.accept(x);
                x.hurt(o.me, 1);
            }) + (1 - pAtt) * delta(o, base, x -> x.hurt(a, 1)) - fought;
        } else if (c.kind() == Contest.Kind.STEAL) {
            double loss = expectedLoss(o, base);
            agree = -loss;
            refuse = pAtt * (delta(o, base, x -> x.hurt(o.me, 1)) - loss)
                    + (1 - pAtt) * delta(o, base, x -> x.hurt(a, 1)) - fought;
        } else {
            CardValues cards = new CardValues(o, base);
            double seat = seatValue(o, cards, a);              // 同一对人换座位，谁发起都一样
            agree = seat;
            refuse = pAtt * (delta(o, base, x -> x.hurt(o.me, 1)) + seat)
                    + (1 - pAtt) * delta(o, base, x -> x.hurt(a, 1)) - fought;
        }
        return new double[]{agree, refuse};
    }

    /** 被抢时大概会丢掉几分：他多半拿走我面前最值钱的那张，否则从我手里随机摸一张。 */
    double expectedLoss(Outlook o, double base) {
        SeatView view = o.view;
        double front = 0;
        for (String c : view.me().front()) {
            front = Math.max(front, hold(o, base, c));
        }
        double hand = 0;
        for (String c : view.hand()) {
            hand += hold(o, base, c);
        }
        if (!view.hand().isEmpty()) {
            hand /= view.hand().size();
        }
        if (view.me().front().isEmpty()) {
            return Math.max(0, hand);
        }
        if (view.hand().isEmpty()) {
            return Math.max(0, front);
        }
        return Math.max(0, 0.5 * front + 0.5 * hand);
    }

    /** 站队：站哪边让我爱的人、帮过我的人那边更可能赢，同时别让自己挨打。 */
    /** 站队的三个选项：不站 · 进攻方 · 防守方（{@link #scoreStances} 按这个次序打分）。 */
    static final List<Optional<Fight.Side>> STANCES = List.of(Optional.empty(), Optional.of(Fight.Side.ATTACK),
            Optional.of(Fight.Side.DEFEND));

    @Override
    public Optional<Fight.Side> joinStance(SeatView view, Random rng) {
        return STANCES.get(choose(scoreStances(view), rng));
    }

    /** 不站 · 进攻方 · 防守方，各值几分。 */
    double[] scoreStances(SeatView view) {
        SeatView.ContestInfo c = view.contest().orElseThrow();
        Outlook o = outlook(view);
        double base = o.ev();
        double[] scores = new double[3];
        Fight.Side[] sides = {null, Fight.Side.ATTACK, Fight.Side.DEFEND};
        for (int k = 0; k < 3; k++) {
            scores[k] = fightValue(o, base, c, sides[k], 0.5 * o.myWeaponPower(), Outlook.FIGHT_SIGMA_OPEN * 0.75)
                    - (sides[k] == null ? 0 : foughtCost(o, base));
        }
        return scores;
    }

    /**
     * 这一架打完我大概多拿几分。
     *
     * @param mySide  我站哪边（{@code null} = 不在场上；已经在场上时以场上为准）
     * @param myExtra 我在体型之外还加多少：押下的武器（还没押时按能押的一半估），打架前喝的酒
     */
    double fightValue(Outlook o, double base, SeatView.ContestInfo c, Fight.Side mySide, double myExtra,
                      double sigma) {
        List<Integer> att = new ArrayList<>();
        List<Integer> def = new ArrayList<>();
        c.attackSide().forEach(id -> att.add(o.index(id)));
        c.defendSide().forEach(id -> def.add(o.index(id)));
        if (mySide != null && !att.contains(o.me) && !def.contains(o.me)) {
            (mySide == Fight.Side.ATTACK ? att : def).add(o.me);
        }
        double avgCommitted = averageWeaponPower(o.view);
        double attack = 0;
        double defend = 0;
        for (int j : att) {
            attack += o.strength(j) + (j == o.me ? myExtra : committedOrHidden(o, c, j, avgCommitted));
        }
        for (int j : def) {
            defend += o.strength(j) + (j == o.me ? myExtra : committedOrHidden(o, c, j, avgCommitted));
        }
        double p = Outlook.pAttackWins(attack, defend, sigma);
        double win = delta(o, base, x -> {
            def.forEach(j -> x.hurt(j, 1));
            attackerGets(x, c);
        });
        double lose = delta(o, base, x -> att.forEach(j -> x.hurt(j, 1)));
        return p * win + (1 - p) * lose;
    }

    /** 发起的一方打赢了，他想要的那件事发生：分食生效；抢 —— 被抢的人少一张（估一张水的分量）。换座位按 0 估。 */
    private void attackerGets(Outlook x, SeatView.ContestInfo c) {
        if (c.kind() == Contest.Kind.RATION) {
            ProvisionEffect.HealAll heal = (ProvisionEffect.HealAll) x.view.catalog().get(c.rationCard()).effect();
            for (int j = 0; j < x.n; j++) {
                if (x.view.seats().get(j).condition() == Condition.CONSCIOUS && x.margin[j] < x.size[j]) {
                    x.heal(j, heal.amount());
                }
            }
        } else if (c.kind() == Contest.Kind.STEAL) {
            int victim = x.index(c.target());
            int thief = x.index(c.attacker());
            double moved = Math.min(x.water[victim], x.waterDensity);
            x.water[victim] -= moved;
            x.water[thief] += moved;
        }
    }

    /** 别人押下了几张（看得见张数）就按平均每张估；还没押的按他藏着的估。 */
    private static double committedOrHidden(Outlook o, SeatView.ContestInfo c, int j, double avg) {
        int committed = c.committed(o.ids[j]);
        if (c.stage() == Contest.Stage.WEAPONS && committed > 0) {
            return committed * avg;
        }
        return o.hiddenPower(j);
    }

    /** 整副牌里一张武器平均加多少（押下的暗牌按它估）。 */
    static double averageWeaponPower(SeatView view) {
        double power = 0;
        int count = 0;
        for (Provision p : view.catalog().all()) {
            if (p.weaponPower() > 0) {
                power += (double) p.weaponPower() * p.count();
                count += p.count();
            }
        }
        return count == 0 ? 0 : power / count;
    }

    /** 押武器之前喝一口：多 3 点力气值不值今天多渴一次。 */
    @Override
    public Optional<String> drinkForFight(SeatView view, List<String> drinkable, Random rng) {
        if (drinkable.isEmpty()) {
            return Optional.empty();
        }
        SeatView.ContestInfo c = view.contest().orElseThrow();
        Outlook o = outlook(view);
        double base = o.ev();
        Fight.Side side = c.sideOf(view.self()).orElse(null);
        if (side == null) {
            return Optional.empty();
        }
        ProvisionEffect.BuffSize buff = (ProvisionEffect.BuffSize) view.catalog().get(drinkable.getFirst()).effect();
        double without = fightValue(o, base, c, side, 0.5 * o.myWeaponPower(), Outlook.FIGHT_SIGMA_CLOSED);
        double with = fightValue(o, base, c, side, 0.5 * o.myWeaponPower() + buff.amount(), Outlook.FIGHT_SIGMA_CLOSED);
        double cost = thirstCost(o, base);
        return with - cost > without + 0.1 * sign() ? Optional.of(drinkable.getFirst()) : Optional.empty();
    }

    /** 押武器：在「押哪几张」的每一种组合里，挑打赢的好处减去亮牌代价最划算的那一种（多半是刚好够翻盘的那几张）。 */
    @Override
    public List<String> commitWeapons(SeatView view, List<String> weapons, Random rng) {
        if (weapons.isEmpty() || view.contest().orElseThrow().sideOf(view.self()).isEmpty()) {
            return List.of();
        }
        List<List<String>> subsets = weaponSubsets(weapons);
        return subsets.get(choose(scoreWeapons(view, subsets), rng));
    }

    /** 押哪几张的每一种组合（同样几张算一种），第一种是一张不押。 */
    static List<List<String>> weaponSubsets(List<String> weapons) {
        int k = Math.min(weapons.size(), 6);
        List<List<String>> subsets = new ArrayList<>();
        Set<List<String>> seen = new LinkedHashSet<>();
        for (int mask = 0; mask < (1 << k); mask++) {
            List<String> pick = new ArrayList<>();
            for (int i = 0; i < k; i++) {
                if ((mask & (1 << i)) != 0) {
                    pick.add(weapons.get(i));
                }
            }
            List<String> key = new ArrayList<>(pick);
            key.sort(null);
            if (seen.add(key)) {
                subsets.add(List.copyOf(pick));
            }
        }
        return List.copyOf(subsets);
    }

    /** 每一种组合押下去值几分。 */
    double[] scoreWeapons(SeatView view, List<List<String>> subsets) {
        SeatView.ContestInfo c = view.contest().orElseThrow();
        Outlook o = outlook(view);
        double base = o.ev();
        Fight.Side side = c.sideOf(view.self()).orElseThrow();
        double[] scores = new double[subsets.size()];
        for (int i = 0; i < scores.length; i++) {
            double power = 0;
            double cost = 0;
            for (String card : subsets.get(i)) {
                Provision p = view.catalog().get(card);
                power += p.weaponPower();
                cost += p.effect().discardOnUse() ? hold(o, base, card) : EXPOSURE * sign();
            }
            scores[i] = fightValue(o, base, c, side, power, Outlook.FIGHT_SIGMA_CLOSED) - cost;
        }
        return scores;
    }

    /** 抢到手了：挑对我最值钱（外加他少了它最让我高兴）的那一张。 */
    @Override
    public PickChoice pick(SeatView view, List<PickChoice> legal, Random rng) {
        return legal.get(choose(scorePicks(view, legal), rng));
    }

    /** 每一种挑法值几分。 */
    double[] scorePicks(SeatView view, List<PickChoice> legal) {
        SeatView.ContestInfo c = view.contest().orElseThrow();
        Outlook o = outlook(view);
        double base = o.ev();
        int t = o.index(c.target());
        double[] scores = new double[legal.size()];
        for (int i = 0; i < scores.length; i++) {
            if (legal.get(i) instanceof PickChoice.FromFront f) {
                scores[i] = hold(o, base, f.card()) + denial(o, base, t, f.card(), true);
            } else {
                scores[i] = handPickValue(o, base, t);
            }
        }
        return scores;
    }

    // ---------------------------------------------------------------- 落海与口渴

    /** 落海那一刻：给爱的人 / 自己扔救生圈，给恨的人那一批扔血饵 —— 按这一批落海结算完的样子比。 */
    @Override
    public Optional<Session.OverboardPlay> overboard(SeatView view, List<Session.OverboardPlay> plays, Random rng) {
        SeatView.OverboardInfo w = view.overboard().orElseThrow();
        Outlook o = outlook(view);
        double base = o.ev();
        double none = resolveWindow(o, w, w.bait(), -1, -1);
        Session.OverboardPlay best = null;
        double bestGain = OVERBOARD_THRESHOLD;
        for (Session.OverboardPlay play : plays) {
            ProvisionEffect e = view.catalog().get(play.card()).effect();
            double after;
            if (e instanceof ProvisionEffect.DamageInWater bait) {
                if (w.bait() > 0 && !bait.stacks()) {
                    continue;                                // 血饵不叠加：已经有人扔过了
                }
                after = resolveWindow(o, w, w.bait() + bait.amount(), -1, -1) - hold(o, base, play.card());
            } else {
                int to = o.index(play.target());
                boolean fromMe = true;
                after = resolveWindow(o, w, w.bait(), to, fromMe ? o.me : -1);
            }
            double gain = after - none;
            if (gain > bestGain) {
                bestGain = gain;
                best = play;
            }
        }
        return Optional.ofNullable(best);
    }

    /** 这一批落海结算完我大概拿几分。{@code ringTo} 收到救生圈的人（-1 = 没人），{@code ringFrom} 扔出救生圈的人。 */
    private static double resolveWindow(Outlook o, SeatView.OverboardInfo w, int bait, int ringTo, int ringFrom) {
        Outlook c = o.copy();
        if (ringTo >= 0) {
            if (ringFrom >= 0 && ringFrom != ringTo) {
                c.ring[ringFrom] = false;
            }
            c.ring[ringTo] = true;
        }
        for (CharacterId id : w.swimmers()) {
            c.overboard(c.index(id), bait);
        }
        return c.ev();
    }

    /** 轮到我口渴：喝几次。挨伤是确定的、留着的水以后未必用得上，所以分数相同时多喝。 */
    @Override
    public WaterPlan drinkWater(SeatView view, int ownUnits, Random rng) {
        SeatView.ThirstInfo t = view.thirst().orElseThrow();
        int units = choose(scoreWater(view, ownUnits), rng);
        return new WaterPlan(units, units < t.remaining());
    }

    /** 自己喝 0、1、2…… 次各值几分（下标就是次数）。 */
    double[] scoreWater(SeatView view, int ownUnits) {
        SeatView.ThirstInfo t = view.thirst().orElseThrow();
        Outlook o = outlook(view);
        int max = Math.min(ownUnits, t.remaining());
        double[] scores = new double[max + 1];
        for (int u = 0; u <= max; u++) {
            int units = u;
            Outlook c = o.copy();
            c.water[o.me] -= units * t.waterPerSource();
            c.hurt(o.me, t.remaining() - units);
            scores[u] = c.ev() + 1e-6 * u;
        }
        return scores;
    }

    /** 别人渴了：递几次。他少挨的伤对我值多少，减去我少了的水。 */
    @Override
    public int donateWater(SeatView view, CharacterId drinker, int shortUnits, int myUnits, boolean helpAsked,
                           int donatedSoFar, Random rng) {
        SeatView.ThirstInfo t = view.thirst().orElseThrow();
        Outlook o = outlook(view);
        int d = o.index(drinker);
        int max = Math.min(myUnits, shortUnits);
        double[] scores = new double[max + 1];
        for (int g = 0; g <= max; g++) {
            int give = g;
            Outlook c = o.copy();
            c.water[o.me] -= give * t.waterPerSource();
            c.hurt(d, shortUnits - give);
            scores[g] = c.ev() - 1e-6 * g;
        }
        return choose(scores, rng);
    }

    @Override
    public String label() {
        String mode = switch (objective) {
            case NORMAL -> "";
            case SWAPPED -> " · 爱恨对调（红测）";
            case INVERTED -> " · 估分反过来（红测）";
        };
        return "按处境打分（温度 %s%s）".formatted(settings.temperature(), mode);
    }

    // ---------------------------------------------------------------- 工具

    /** 按 softmax 挑一项；温度为 0 时挑分最高的第一项，不消费随机数。 */
    int choose(double[] scores, Random rng) {
        int best = 0;
        for (int i = 1; i < scores.length; i++) {
            if (scores[i] > scores[best]) {
                best = i;
            }
        }
        double t = settings.temperature();
        if (t <= 0 || scores.length == 1) {
            return best;
        }
        double[] w = new double[scores.length];
        double sum = 0;
        for (int i = 0; i < scores.length; i++) {
            w[i] = Math.exp((scores[i] - scores[best]) / t);
            sum += w[i];
        }
        double r = rng.nextDouble() * sum;
        for (int i = 0; i < w.length; i++) {
            r -= w[i];
            if (r <= 0) {
                return i;
            }
        }
        return best;
    }

    /** 在副本上做一件事，看我的估分变了多少。 */
    static double delta(Outlook o, double base, java.util.function.Consumer<Outlook> change) {
        Outlook c = o.copy();
        change.accept(c);
        return c.ev() - base;
    }

    private static List<String> available(SeatView view) {
        List<String> all = new ArrayList<>(view.hand());
        all.addAll(view.me().front());
        return all;
    }

    /** 没见过的牌（别人手里、牌堆里）各有几张。 */
    static Map<String, Integer> unseenPool(SeatView view) {
        Map<String, Integer> unseen = new LinkedHashMap<>();
        for (String card : view.catalog().deck()) {
            unseen.merge(card, 1, Integer::sum);
        }
        view.hand().forEach(c -> unseen.merge(c, -1, Integer::sum));
        for (SeatView.SeatInfo s : view.seats()) {
            s.front().forEach(c -> unseen.merge(c, -1, Integer::sum));
        }
        view.table().discard().forEach(c -> unseen.merge(c, -1, Integer::sum));
        view.box().ifPresent(b -> b.offer().forEach(c -> unseen.merge(c, -1, Integer::sum)));
        unseen.replaceAll((k, v) -> Math.max(0, v));
        return unseen;
    }

    /**
     * 航海牌对我值几分：每一张若执行，此刻立刻怎样（{@link Outlook#apply}），减去不执行。
     * 给「划船值不值」「当舵手值不值」估一个数。
     */
    final class CardValues {
        private final Outlook o;
        private final double base;
        private double[] values;

        CardValues(Outlook o, double base) {
            this.o = o;
            this.base = base;
        }

        private double[] values() {
            if (values == null) {
                List<NavigationCard> deck = o.view.navDeck();
                values = new double[deck.size()];
                for (int i = 0; i < values.length; i++) {
                    NavigationCard card = deck.get(i);
                    values[i] = delta(o, base, c -> c.apply(card));
                }
            }
            return values;
        }

        private double mean() {
            double[] v = values();
            double s = 0;
            for (double x : v) {
                s += x;
            }
            return v.length == 0 ? 0 : s / v.length;
        }

        /** 每天能从三张里挑一张（舵手）比随手翻一张多出几分，乘上大约还能当几天。 */
        double helmValue() {
            return Math.min(o.days(), 2) * (bestOf(3) - mean());
        }

        /** 从 {@code k} 张随机牌里挑最好的一张，平均值几分（按固定的几组抽样估，不消费对局的随机数）。 */
        double bestOf(int k) {
            double[] v = values();
            if (v.length == 0) {
                return 0;
            }
            double sum = 0;
            for (int s = 0; s < HELM_SAMPLES; s++) {
                double best = Double.NEGATIVE_INFINITY;
                for (int i = 0; i < k; i++) {
                    best = Math.max(best, v[(int) (((long) (s * 7919 + i * 104729)) % v.length)]);
                }
                sum += best;
            }
            return sum / HELM_SAMPLES;
        }

        /** 我是舵手时划船：划船堆里多一张我从 {@code draws} 张里挑出来的牌。 */
        double helmGain(int stack, int draws) {
            if (stack == 0) {
                return bestOf(draws) - mean();               // 没人划过：本来是翻顶牌
            }
            return bestOf(stack + draws) - bestOf(stack);
        }

        /** 我不是舵手时划船：我塞进去的那张，舵手大约以 1/(张数) 的机会挑中。 */
        double rowerGain(int stack, int draws) {
            return (bestOf(draws) - mean()) / (stack + 1);
        }
    }

    /** 测试用：这一位此刻对每个合法行动打的分（不挑）。 */
    double[] scoreActions(SeatView view, List<ActionChoice> legal) {
        Outlook o = outlook(view);
        double base = o.ev();
        CardValues cards = new CardValues(o, base);
        double[] scores = new double[legal.size()];
        for (int i = 0; i < scores.length; i++) {
            scores[i] = score(o, base, cards, legal.get(i));
        }
        return scores;
    }
}
