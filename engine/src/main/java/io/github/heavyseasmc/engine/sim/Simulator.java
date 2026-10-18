package io.github.heavyseasmc.engine.sim;

import io.github.heavyseasmc.engine.play.Invariants;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.play.Table;
import io.github.heavyseasmc.engine.model.Affinities;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Provisions;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.navigation.NavigationDeck;
import io.github.heavyseasmc.engine.scoring.ScoreSheet;
import io.github.heavyseasmc.engine.scoring.TreasureScoring;
import io.github.heavyseasmc.engine.seat.RandomSeatPolicy;
import io.github.heavyseasmc.engine.seat.SeatDriver;
import io.github.heavyseasmc.engine.seat.SeatPolicies;
import io.github.heavyseasmc.engine.seat.SeatPolicy;
import io.github.heavyseasmc.engine.state.GameState;
import io.github.heavyseasmc.engine.weather.WeatherCard;
import io.github.heavyseasmc.engine.weather.WeatherDeck;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;

/**
 * 对局模拟器 —— 用席位策略把状态机跑到终局，每一步核对不变量。
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
 * <h2>默认的随机席位不是「玩得好」，是「玩得杂」</h2>
 * 它刻意做蠢事：明明有水也可能不喝、能赢的架也可能不打。
 * 目的是<b>把状态空间铺开</b>，不是模拟人类决策（{@link RandomSeatPolicy}）。
 *
 * <h2>每个决定都问座位的策略</h2>
 * 决定原先全写死在本类的私有方法里；现在交给 {@link SeatPolicy}，由 {@link SeatDriver} 去问 ——
 * 于是一桌可以混坐：随机席位、按处境打分的替身、往前推演的替身。
 * ❗随机席位按原样消费随机数：只用随机席位的旧构造，每一局与搬家之前一个比特不差（{@code RandomSeatsReproduceTest}）。
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
    private final SeatPolicies seats;

    /** 天候牌；空表示三阶段兼容局（不翻天候）。 */
    private final List<WeatherCard> weather;

    /** 终局计分用的分值表；{@code null} 表示这一台模拟器不计分。 */
    private final TreasureScoring scoring;

    /** 默认用对照组策略（留不留、挑哪张全看运气）。 */
    public Simulator(Roster roster, List<NavigationCard> deck, Provisions provisions) {
        this(roster, deck, provisions, NavigationPolicy.INDIFFERENT);
    }

    public Simulator(Roster roster, List<NavigationCard> deck, Provisions provisions,
                     NavigationPolicy policy) {
        this(roster, deck, provisions, policy, null);
    }

    /**
     * 全船随机席位，划船留牌与舵手挑牌按 {@code policy}。
     *
     * @param scoring 终局计分用的分值表；{@code null} 表示不计分。
     *                ❗计分要分得清现金与美术品，而那靠角色表里的船长与收藏家（{@code Roster#doublers}）——
     *                手搭的合成小阵容里没有他们、也没有整张角色表可查，所以默认不计分，真实阵容的测量台才传它
     */
    public Simulator(Roster roster, List<NavigationCard> deck, Provisions provisions,
                     NavigationPolicy policy, TreasureScoring scoring) {
        this(roster, deck, provisions, policy, scoring,
                SeatPolicies.all(new RandomSeatPolicy(Objects.requireNonNull(policy, "policy"))), List.of());
    }

    /**
     * 每个座位用什么策略由 {@code seats} 定（可以混坐）。
     *
     * @param weather 天候牌；空表示不翻天候（与旧模拟器相同的三阶段局）
     */
    public Simulator(Roster roster, List<NavigationCard> deck, Provisions provisions, TreasureScoring scoring,
                     SeatPolicies seats, List<WeatherCard> weather) {
        this(roster, deck, provisions, NavigationPolicy.INDIFFERENT, scoring, seats, weather);
    }

    private Simulator(Roster roster, List<NavigationCard> deck, Provisions provisions, NavigationPolicy policy,
                      TreasureScoring scoring, SeatPolicies seats, List<WeatherCard> weather) {
        this.scoring = scoring;
        this.roster = Objects.requireNonNull(roster, "roster");
        this.deck = List.copyOf(Objects.requireNonNull(deck, "deck"));
        this.provisions = Objects.requireNonNull(provisions, "provisions");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.seats = Objects.requireNonNull(seats, "seats");
        this.weather = List.copyOf(Objects.requireNonNull(weather, "weather"));
        if (this.deck.isEmpty()) {
            throw new IllegalArgumentException("航海牌堆不能为空 —— 没有牌就永远结束不了");
        }
    }

    /** 随机席位划船留牌与舵手挑牌用的取向（按席位策略构造的模拟器上，这是默认的对照组）。 */
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
        // ❗问策略的次序与次数是可复现性的一部分（SeatDriver 的类注释），改动 Session 里的循环结构也一样。
        Random rng = new Random(seed);
        String context = "seed=%d".formatted(seed);
        Table table = weather.isEmpty()
                ? new Table(new NavigationDeck(deck, rng), provisions, rng)
                : new Table(new NavigationDeck(deck, rng), provisions, new WeatherDeck(weather, rng), rng);
        Session session = new Session(context, roster, table);
        // 开局发爱恨（ADR-0022）。❗在任何决策之前、用同一条随机流 —— 次序是可复现性的一部分。
        session.dealAffinities(Affinities.random(roster, rng));
        ExposureTally exposure = new ExposureTally();

        Map<CharacterId, SeatPolicy> table0 = new LinkedHashMap<>();
        for (CharacterId id : session.state().bySeat()) {
            table0.put(id, Objects.requireNonNull(seats.forSeat(id, seed), "座位 " + id + " 没有策略"));
        }
        // 曝光率从结算报告里数，而不是在结算过程中埋钩子 ——
        // 埋钩子的话，模拟器看到的与模组看到的可能不是同一件事。
        SeatDriver driver = new SeatDriver(session, table0, rng, new SeatDriver.Listener() {
            @Override
            public void navigated(io.github.heavyseasmc.engine.play.NavigationReport report) {
                // ❗一张牌里同一个人只算一次：暴风雨天被点名的人若也划过船，会在两批里各下一次水，
                //   报告里他就出现两次 —— 而「一张牌一次机会」，两次命中会被 Exposure 当成分母漏加拦下。
                //   不翻天候的局里每张牌只有一批，这一行与原来一模一样。
                report.overboardCandidates().forEach(exposure::overboardChance);
                report.overboardSelected().stream().distinct().forEach(exposure::overboard);
                report.thirstCandidates().forEach(exposure::thirstChance);
                report.thirstSelected().stream().distinct().forEach(exposure::thirst);
            }
        });

        while (!session.state().isOver()) {
            if (session.state().turn() > TURN_LIMIT) {
                throw new IllegalStateException(
                        "seed=%d 超过 %d 回合仍未结束，疑似死锁（海鸥 %d，存活 %d）"
                                .formatted(seed, TURN_LIMIT, session.state().gulls(), session.aliveCount()));
            }
            GameState before = session.state();
            driver.playPhase();
            Invariants.requireValidTransition(before, session.state(), seed, "阶段 " + before.phase(),
                    session.healedSincePhaseStart());
            if (session.state().isOver()) {
                break;
            }
            // ❗推进阶段这一步也要核对。原先的窗口是「阶段开头 → 阶段做完」，而 advancePhase 恰好夹在
            //   上一个窗口的结尾与下一个窗口的开头之间 —— 回合结束时的清理从来不在任何一个核对窗口里。
            //   2026-09-16 变异测试（让回合推进丢掉移出标记）照出来的：模拟器红了，却红在别处（ADR-0022 §9）。
            GameState beforeAdvance = session.state();
            session.advancePhase();
            Invariants.requireValidTransition(beforeAdvance, session.state(), seed,
                    "推进阶段 " + beforeAdvance.phase(), 0);
        }
        GameState end = session.state();
        // 每一局都算一次分：终局状态有一处对不上（珠宝超过全局张数、美术品面值超过总和），计分器会当场抛。
        Map<CharacterId, ScoreSheet> scores = scoring == null ? Map.of() : session.scores(scoring);
        return new Result(seed, end.turn(), end.outcome().orElseThrow(), session.aliveCount(), driver.fights(),
                exposure.toMap(), scores, driver.fallbacks());
    }

    /**
     * 一局的结果。
     *
     * @param seed      种子，失败时靠它复现
     * @param turns     走了几回合
     * @param outcome   终局原因
     * @param alive     终局时还活着几个人
     * @param fights    打了几架
     * @param exposure  每个角色被航海牌点到的次数与机会数，O1 的分布曲线由它汇总而来。
     *                  ❗<b>只含真的被结算过的回合</b>：海鸥当场结束一局时那张牌不结算落海，
     *                  所以那一回合两边都不计
     * @param scores    每个人的四项计分；不计分时是空表
     * @param fallbacks 策略交回不合法的选项、被退回默认的次数（{@link SeatDriver#fallbacks()}）。
     *                  随机席位与按处境打分的替身都该是 0 —— 不是 0 说明合法清单与策略对不上
     */
    public record Result(long seed, int turns, GameState.Outcome outcome, int alive, int fights,
                         Map<CharacterId, Exposure> exposure, Map<CharacterId, ScoreSheet> scores, int fallbacks) {

        public Result {
            exposure = Map.copyOf(Objects.requireNonNull(exposure, "exposure"));
            scores = Map.copyOf(Objects.requireNonNull(scores, "scores"));   // 不计分时是空表
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
