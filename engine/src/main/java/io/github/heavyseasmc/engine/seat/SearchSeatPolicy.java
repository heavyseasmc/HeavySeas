package io.github.heavyseasmc.engine.seat;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.scoring.TreasureScoring;
import io.github.heavyseasmc.engine.sim.Simulator;
import io.github.heavyseasmc.engine.state.Fight;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * 会动脑的替身，第二层：<b>往前推演</b>。第一层（{@link HeuristicSeatPolicy}）先打分剪枝，剩下的几个候选
 * 各自在「可能的局面」上做下去、往后打几天，挑平均下来对自己最好的那个。
 *
 * <h2>为什么不是一般的博弈树搜索</h2>
 * 这是多人、有骰子、有暗牌的游戏：别人手里有什么看不见，下一张航海牌是什么也看不见，α-β 那一套的前提一条都不成立。
 * 所以用「抽样定局」的蒙特卡洛：
 * <ol>
 *   <li><b>剪枝</b>：合法选项先用第一层打分，只留前 {@link SeatPolicySettings#width() width} 个，
 *       比最好的那项差 {@value #PRUNE_GAP} 分以上的也不留（那是「打爱的人」一类的硬罚分）。
 *       只剩一个就不推演；</li>
 *   <li><b>抽样定局</b>：每一局推演先照我的视角抽一局「可能的局面」（{@link Worlds#sample}：看得见的照搬，看不见的按我知道的重抽），
 *       在上面把候选做下去，然后全船都按第一层往下打 {@link SeatPolicySettings#horizonDays() horizonDays} 天
 *       （今天算第一天；0 = 打到终局）；</li>
 *   <li><b>收尾估分</b>：打完了的按真的计分规则（{@code Scorer}，第十一章四项，含爱恨两项）算我拿几分；
 *       没打完的用第一层的估分（{@code Outlook#ev()}，对着几万局的结果校准过）；</li>
 *   <li><b>逐轮减半</b>（successive halving）：先给每个候选同样几局，砍掉平均分差的那一半，剩下的再多给几局……
 *       直到只剩一个、或者预算用完。同一批里各候选用<b>同一局</b>抽出来的局面与同一条随机流，
 *       比的是「换了这一步会怎样」，而不是「运气好的那几局分给了谁」。</li>
 * </ol>
 *
 * <h2>目标里的恩怨</h2>
 * 估分加上第一层同一份恩怨倾向（帮过我的人活着，多一点高兴；害过我的人活着，少一点），
 * 而且按<b>拿主意那一刻</b>的恩怨算 —— 推演里别人「帮了我」是抽出来的，不是真的。不加的话，一推演，「记得谁帮过我」就被抹掉了。
 *
 * <h2>可复现、有预算、看不见的碰不到</h2>
 * <ul>
 *   <li>推演的随机数只从<b>自己那一条</b>拿（构造时按种子造），不碰对局的随机流；
 *       推演不加温度（逐轮减半最后只剩一个）。不推演的那几个决定照第一层答，温度不为 0 时照第一层用对局的随机流；</li>
 *   <li>预算：每个决定最多推演 {@link SeatPolicySettings#rollouts() rollouts} 局，墙上时间最多
 *       {@link SeatPolicySettings#millisPerDecision() millisPerDecision} 毫秒。
 *       ❗限时会让结果取决于机器快慢 —— 要可复现就设 0，让局数先用完；</li>
 *   <li>它只从视角抽局，碰不到真的那一局：两局只差看不见的东西时，同一个种子下的决定一模一样
 *       （{@code SearchSeatPolicyTest} 钉着）。</li>
 * </ul>
 *
 * <h2>每局每座一个实例</h2>
 * 它带着自己的随机流与计时统计，<b>不能</b>几个座位、几局共用：每局每座各造一个（{@link #seats} 就是这么造的）。
 * 可以在工作线程上调它（视角与选项都是快照），但同一个实例不要同时在两个线程上用。
 *
 * <h2>推演哪几个决定</h2>
 * 补给箱留牌、行动、划船留牌、表态、站队、押武器、挑牌、舵手挑牌、自己喝几次水。
 * 亮牌、开头喝酒、送牌、打架前喝酒、落海时打牌、替别人递水照第一层：它们多半没得选或影响小，推演不划算。
 *
 * <h2>推演出了错</h2>
 * 抽局或推演里抛了异常（那是引擎的毛病），这一个决定退回第一层剪枝后排第一的答案，记一笔（{@link #failures()}、{@link #lastFailure()}）。
 * 不抛：模组里一局卡在半路比替身少想一步糟得多。测试一律断言它是 0。
 */
public final class SearchSeatPolicy implements SeatPolicy {

    // ---------------------------------------------------------------- 设计常数（不给服主调）

    /** 第一层打分比最好的那项差这么多分以上的选项不进推演（「打爱的人」一类的硬罚分是 10 分）。 */
    static final double PRUNE_GAP = 3.0;

    /** 这一份推演替谁说话（红测用：把目标弄坏，它就该输）。 */
    enum Objective {
        NORMAL,
        /**
         * 推演的估分反过来，剪枝与不推演的决定照常：在第一层留下的几个候选里，挑推演出来对自己<b>最差</b>的那个。
         * 只弄坏推演这一层 —— 它若不输给第一层，「推演比第一层强」那条判据就量不出推演到底起没起作用。
         */
        INVERTED
    }

    private final SeatPolicySettings settings;
    private final TreasureScoring scoring;
    private final Objective objective;
    /** 剪枝、不推演的那几个决定、推演里全船（包括我自己）的每一步，都是这一份第一层。无状态，可以共用。 */
    private final HeuristicSeatPolicy layer1;
    private final Random rng;
    private final List<Long> nanos = new ArrayList<>();
    private long rollouts;
    private int failures;
    private String lastFailure;
    /** 测试用：最近一次推演里每个选项的平均估分（下标同选项；没推演上的为 NaN）。 */
    double[] lastMeans = new double[0];

    /**
     * @param scoring 终局计分用的分值表（来自 {@code data/roster} 的 {@code treasure_scoring}）；
     *                {@code null} = 没有（合成阵容），推演打到终局时也用第一层的估分
     * @param seed    这一位自己的随机流的种子（每局每座各一个，见 {@link #seats}）
     */
    public SearchSeatPolicy(SeatPolicySettings settings, TreasureScoring scoring, long seed) {
        this(settings, scoring, seed, Objective.NORMAL);
    }

    SearchSeatPolicy(SeatPolicySettings settings, TreasureScoring scoring, long seed, Objective objective) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.scoring = scoring;
        this.objective = Objects.requireNonNull(objective, "objective");
        this.layer1 = new HeuristicSeatPolicy(settings);
        this.rng = new Random(seed);
    }

    /**
     * 每局每座各造一个，种子由这一局的种子与座位混出来（同一局里两个座位不共用一条随机流）。
     *
     * @param scoring 同 {@link #SearchSeatPolicy(SeatPolicySettings, TreasureScoring, long)}
     */
    public static SeatPolicies seats(SeatPolicySettings settings, TreasureScoring scoring) {
        return seats(settings, scoring, Objective.NORMAL);
    }

    static SeatPolicies seats(SeatPolicySettings settings, TreasureScoring scoring, Objective objective) {
        Objects.requireNonNull(settings, "settings");
        return (seat, seed) -> new SearchSeatPolicy(settings, scoring, seedFor(seat, seed), objective);
    }

    /** 一局的种子与座位混成这一位自己的种子（{@code String#hashCode} 是规范钉死的，跨 JVM 不变）。 */
    static long seedFor(CharacterId seat, long gameSeed) {
        return gameSeed * 0x9E3779B97F4A7C15L + seat.value().hashCode();
    }

    public SeatPolicySettings settings() {
        return settings;
    }

    /** 真的推演过的决定，每一个花了多少纳秒（第一层直接答的、只剩一个候选的不记）。 */
    public List<Long> decisionNanos() {
        return Collections.unmodifiableList(nanos);
    }

    /** 一共推演了几局。 */
    public long rollouts() {
        return rollouts;
    }

    /** 推演里抛了异常、退回第一层的次数（应当永远是 0）。 */
    public int failures() {
        return failures;
    }

    /** 最近一次推演出错的说明；没出过错为空。 */
    public Optional<String> lastFailure() {
        return Optional.ofNullable(lastFailure);
    }

    // ---------------------------------------------------------------- 照第一层答的

    @Override
    public Optional<String> reveal(SeatView view, List<String> revealable, Random gameRng) {
        return layer1.reveal(view, revealable, gameRng);
    }

    @Override
    public Optional<String> drink(SeatView view, List<String> drinkable, Random gameRng) {
        return layer1.drink(view, drinkable, gameRng);
    }

    @Override
    public Optional<Gift> give(SeatView view, List<Gift> gifts, Random gameRng) {
        return layer1.give(view, gifts, gameRng);
    }

    @Override
    public Optional<String> drinkForFight(SeatView view, List<String> drinkable, Random gameRng) {
        return layer1.drinkForFight(view, drinkable, gameRng);
    }

    @Override
    public Optional<Session.OverboardPlay> overboard(SeatView view, List<Session.OverboardPlay> plays,
                                                     Random gameRng) {
        return layer1.overboard(view, plays, gameRng);
    }

    @Override
    public int donateWater(SeatView view, CharacterId drinker, int shortUnits, int myUnits, boolean helpAsked,
                           int donatedSoFar, Random gameRng) {
        return layer1.donateWater(view, drinker, shortUnits, myUnits, helpAsked, donatedSoFar, gameRng);
    }

    // ---------------------------------------------------------------- 推演的

    @Override
    public String keepProvision(SeatView view, List<String> offer, Random gameRng) {
        if (!settings.search()) {
            return layer1.keepProvision(view, offer, gameRng);
        }
        List<String> options = HeuristicSeatPolicy.provisionOptions(offer);
        return search(view, options, () -> layer1.scoreProvisions(view, options), SeatDriver::resumeProvision);
    }

    @Override
    public ActionChoice act(SeatView view, List<ActionChoice> legal, Random gameRng) {
        if (!settings.search()) {
            return layer1.act(view, legal, gameRng);
        }
        CharacterId me = view.self();
        return search(view, legal, () -> layer1.scoreActions(view, legal),
                (d, choice) -> d.resumeAction(me, choice));
    }

    @Override
    public int keepRowCard(SeatView view, List<NavigationCard> drawn, Random gameRng) {
        if (!settings.search()) {
            return layer1.keepRowCard(view, drawn, gameRng);
        }
        List<Integer> options = indices(drawn.size());
        return search(view, options, () -> layer1.scoreCards(view, drawn), SeatDriver::resumeRowCard);
    }

    @Override
    public boolean refuse(SeatView view, Random gameRng) {
        if (!settings.search()) {
            return layer1.refuse(view, gameRng);
        }
        if (!view.me().canAct()) {
            return false;                                    // 昏迷的人拒绝不了
        }
        return search(view, List.of(false, true), () -> layer1.scoreRefuse(view), SeatDriver::resumeConsent);
    }

    @Override
    public Optional<Fight.Side> joinStance(SeatView view, Random gameRng) {
        if (!settings.search()) {
            return layer1.joinStance(view, gameRng);
        }
        CharacterId me = view.self();
        return search(view, HeuristicSeatPolicy.STANCES, () -> layer1.scoreStances(view),
                (d, side) -> d.resumeStance(me, side));
    }

    @Override
    public List<String> commitWeapons(SeatView view, List<String> weapons, Random gameRng) {
        if (!settings.search()) {
            return layer1.commitWeapons(view, weapons, gameRng);
        }
        if (weapons.isEmpty() || view.contest().orElseThrow().sideOf(view.self()).isEmpty()) {
            return List.of();
        }
        CharacterId me = view.self();
        List<List<String>> subsets = HeuristicSeatPolicy.weaponSubsets(weapons);
        return search(view, subsets, () -> layer1.scoreWeapons(view, subsets),
                (d, pick) -> d.resumeWeapons(me, pick));
    }

    @Override
    public PickChoice pick(SeatView view, List<PickChoice> legal, Random gameRng) {
        if (!settings.search()) {
            return layer1.pick(view, legal, gameRng);
        }
        return search(view, legal, () -> layer1.scorePicks(view, legal), SeatDriver::resumePick);
    }

    @Override
    public NavigationCard steer(SeatView view, List<NavigationCard> rowStack, Random gameRng) {
        if (!settings.search()) {
            return layer1.steer(view, rowStack, gameRng);
        }
        return search(view, rowStack, () -> layer1.scoreCards(view, rowStack), SeatDriver::resumeSteer);
    }

    @Override
    public WaterPlan drinkWater(SeatView view, int ownUnits, Random gameRng) {
        if (!settings.search()) {
            return layer1.drinkWater(view, ownUnits, gameRng);
        }
        SeatView.ThirstInfo t = view.thirst().orElseThrow();
        int max = Math.min(ownUnits, t.remaining());
        List<WaterPlan> options = new ArrayList<>();
        for (int u = 0; u <= max; u++) {
            options.add(new WaterPlan(u, u < t.remaining()));
        }
        return search(view, options, () -> layer1.scoreWater(view, ownUnits), SeatDriver::resumeWater);
    }

    @Override
    public String label() {
        return "往前推演（每步 %d 局 · 留 %d 个 · 看 %d 天 · 限时 %d ms%s）".formatted(settings.rollouts(),
                settings.width(), settings.horizonDays(), settings.millisPerDecision(),
                objective == Objective.INVERTED ? " · 估分反过来（红测）" : "");
    }

    // ---------------------------------------------------------------- 搜索本身

    /**
     * 剪枝 + 抽样定局 + 逐轮减半。
     *
     * @param prior 第一层给每个选项打的分（下标与 {@code options} 相同），剪枝用
     * @param apply 在抽出来的那一局里替我把这个选项做下去；之后由 {@link SeatDriver#playUntil} 接着打
     */
    private <T> T search(SeatView view, List<T> options, Supplier<double[]> prior, BiConsumer<SeatDriver, T> apply) {
        if (options.size() == 1) {
            return options.getFirst();
        }
        double[] scores = prior.get();
        List<Integer> alive = candidates(scores, settings.width());
        if (alive.size() == 1) {
            return options.get(alive.getFirst());
        }
        long start = System.nanoTime();
        try {
            return options.get(halve(view, options, scores, alive, apply));
        } catch (RuntimeException e) {
            failures++;
            lastFailure = "%s 第 %d 天 %s 推演出错：%s".formatted(view.phase(), view.turn(), view.self().value(), e);
            return options.get(alive.getFirst());
        } finally {
            nanos.add(System.nanoTime() - start);
        }
    }

    /** 逐轮减半，交回最后留下的那一个的下标。 */
    private <T> int halve(SeatView view, List<T> options, double[] prior, List<Integer> candidates,
                          BiConsumer<SeatDriver, T> apply) {
        long deadline = settings.millisPerDecision() > 0
                ? System.nanoTime() + settings.millisPerDecision() * 1_000_000L : Long.MAX_VALUE;
        Map<CharacterId, Double> regard = regard(view);
        double[] sum = new double[options.size()];
        int[] count = new int[options.size()];
        List<Integer> alive = new ArrayList<>(candidates);
        int rounds = 32 - Integer.numberOfLeadingZeros(alive.size() - 1);       // ⌈log2 n⌉
        int budget = settings.rollouts();
        double[] batch = new double[options.size()];
        boolean outOfTime = false;
        for (int round = 0; round < rounds && alive.size() > 1 && !outOfTime; round++) {
            int per = Math.max(1, budget / (alive.size() * (rounds - round)));
            for (int i = 0; i < per && budget >= alive.size() && !outOfTime; i++) {
                // 一批：每个候选各推演一局，用同一局抽出来的局面、同一条随机流。
                // 时间在每局之后都查；到点时这一批没做完就整批不算 —— 只算了一半的那一批，比的就不是同一局了
                long seed = rng.nextLong();
                for (int c : alive) {
                    batch[c] = rollout(view, options.get(c), apply, seed, regard);
                    if (System.nanoTime() > deadline) {
                        outOfTime = true;
                        break;
                    }
                }
                budget -= alive.size();
                if (!outOfTime) {
                    for (int c : alive) {
                        sum[c] += batch[c];
                        count[c]++;
                    }
                }
            }
            alive.sort((a, b) -> compare(sum, count, prior, b, a));
            if (!outOfTime && budget >= 2) {
                alive = new ArrayList<>(alive.subList(0, (alive.size() + 1) / 2));
            } else {
                break;
            }
        }
        alive.sort((a, b) -> compare(sum, count, prior, b, a));
        lastMeans = new double[options.size()];
        for (int i = 0; i < lastMeans.length; i++) {
            lastMeans[i] = count[i] == 0 ? Double.NaN : sum[i] / count[i];
        }
        return alive.getFirst();
    }

    /** 平均分高的在前；一局都没推演上的排后面；平手看第一层的分，再平手按原来的次序。 */
    private static int compare(double[] sum, int[] count, double[] prior, int a, int b) {
        if ((count[a] == 0) != (count[b] == 0)) {
            return count[a] == 0 ? -1 : 1;
        }
        if (count[a] > 0) {
            int byMean = Double.compare(sum[a] / count[a], sum[b] / count[b]);
            if (byMean != 0) {
                return byMean;
            }
        }
        int byPrior = Double.compare(prior[a], prior[b]);
        return byPrior != 0 ? byPrior : Integer.compare(b, a);
    }

    /** 在一局抽出来的「可能的局面」上把这个选项做下去，往后打几天，看我拿几分。 */
    private <T> double rollout(SeatView view, T option, BiConsumer<SeatDriver, T> apply, long seed,
                               Map<CharacterId, Double> regard) {
        rollouts++;
        Random r = new Random(seed);
        Session world = Worlds.sample(view, r);
        Map<CharacterId, SeatPolicy> seats = new HashMap<>();
        for (Survivor s : view.roster().survivors()) {
            seats.put(s.id(), layer1);
        }
        SeatDriver driver = new SeatDriver(world, seats, r);
        apply.accept(driver, option);
        int last = settings.horizonDays() == 0 ? Simulator.TURN_LIMIT
                : Math.min(Simulator.TURN_LIMIT, view.turn() + settings.horizonDays() - 1);
        driver.playUntil(last);
        double value = value(world, view.self(), regard);
        return objective == Objective.INVERTED ? -value : value;
    }

    /**
     * 我在这一局里拿几分：打完了按真的计分规则算（加上恩怨倾向），没打完用第一层的估分（同一份恩怨倾向）。
     */
    double value(Session world, CharacterId me, Map<CharacterId, Double> regard) {
        SeatView mine = SeatView.of(world, me);
        if (world.state().isOver() && scoring != null) {
            double total = world.scores(scoring).get(me).total();
            for (SeatView.SeatInfo s : mine.seats()) {
                if (!s.id().equals(me) && s.alive()) {
                    total += regard.getOrDefault(s.id(), 0.0);
                }
            }
            return total;
        }
        Outlook o = new Outlook(mine, settings, Outlook.Objective.NORMAL);
        double[] r = new double[o.n];
        for (int i = 0; i < o.n; i++) {
            r[i] = regard.getOrDefault(o.ids[i], 0.0);
        }
        return o.ev(r);
    }

    /** 拿主意这一刻的恩怨（按人记：推演里换了座位，下标就对不上了）。 */
    Map<CharacterId, Double> regard(SeatView view) {
        double[] social = Outlook.social(view, settings);
        Map<CharacterId, Double> out = new LinkedHashMap<>();
        List<SeatView.SeatInfo> seats = view.seats();
        for (int i = 0; i < seats.size(); i++) {
            out.put(seats.get(i).id(), social[i]);
        }
        return out;
    }

    /** 进推演的候选：第一层分数前 {@code k} 个，且不比最好的那项差 {@value #PRUNE_GAP} 分以上（同分按原来的次序）。 */
    static List<Integer> candidates(double[] scores, int k) {
        List<Integer> order = indices(scores.length);
        order.sort((a, b) -> Double.compare(scores[b], scores[a]));
        double best = scores[order.getFirst()];
        List<Integer> out = new ArrayList<>();
        for (int i : order) {
            if (out.size() == k || scores[i] < best - PRUNE_GAP) {
                break;
            }
            out.add(i);
        }
        return out;
    }

    private static List<Integer> indices(int n) {
        List<Integer> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(i);
        }
        return out;
    }
}
