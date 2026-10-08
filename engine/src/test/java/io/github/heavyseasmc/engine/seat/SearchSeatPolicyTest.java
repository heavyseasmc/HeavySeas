package io.github.heavyseasmc.engine.seat;

import io.github.heavyseasmc.engine.data.LocalData;
import io.github.heavyseasmc.engine.data.SyntheticTable;
import io.github.heavyseasmc.engine.data.TestProvisions;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Provisions;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.play.Contest;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.scoring.TreasureScoring;
import io.github.heavyseasmc.engine.sim.Simulator;
import io.github.heavyseasmc.engine.state.Fight;
import io.github.heavyseasmc.engine.state.GameState;
import io.github.heavyseasmc.engine.weather.WeatherCard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static io.github.heavyseasmc.engine.seat.Scenario.CAPTAIN;
import static io.github.heavyseasmc.engine.seat.Scenario.JEWELER;
import static io.github.heavyseasmc.engine.seat.Scenario.KID;
import static io.github.heavyseasmc.engine.seat.Scenario.MATE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会动脑的替身第二层（{@link SearchSeatPolicy}）的几条保证 —— 「它是不是更聪明」另在 {@code SearchSmarterTest}。
 *
 * <ol>
 *   <li><b>推演从一个决定接着往下走，与驱动者自己往下走一模一样</b>：每一种要推演的决定，驱动者都有一个入口
 *       （{@code SeatDriver#resume*}）；同一个答案走入口、再把这一阶段走完，局面与随机流必须与真的那一局逐位相同。
 *       入口错了（漏记行动、从头再问一遍站队……），推演推的就是另一种游戏 —— 而那不会报错。</li>
 *   <li><b>看不见的变了，推演的决定不变</b>：两局只差别人的手牌、牌堆次序、别人的爱恨时，同一个推演种子下决定一模一样，
 *       而且推演真的跑过（正向对照）。</li>
 *   <li><b>可复现</b>：不限时的推演只从自己的随机流拿随机数 —— 同一个种子整局一模一样。</li>
 *   <li><b>有预算</b>：默认预算下每个决定多久（p50 / p95），不超出时限太多。</li>
 * </ol>
 */
class SearchSeatPolicyTest {

    /** 测试用的小预算：不限时（可复现），局数少。 */
    static final SeatPolicySettings SMALL = SeatPolicySettings.DEFAULTS.withBudget(24, 0, 3, 1);
    /** 不消费随机数的第一层（温度 0）：「接着往下走」两边才比得了随机流。 */
    static final SeatPolicySettings DET = SeatPolicySettings.DEFAULTS.withoutSearch().withTemperature(0);
    static final TreasureScoring SCORING = LocalData.roster().treasureScoring();

    // ================================================================ 推演从一个决定接着往下走

    /** 某个决定那一刻：那一局的副本、随机流的副本、以及「照这个答案接着走」。 */
    record Snapshot(String kind, Session session, SeatDriver.RandomState randoms, Consumer<SeatDriver> resume) {
    }

    /** 照第一层（温度 0）答；每到一个要推演的决定，先把那一刻记下来。 */
    static final class Recorder implements SeatPolicy {
        final Supplier<Session> live;
        final HeuristicSeatPolicy inner = new HeuristicSeatPolicy(DET);
        final List<Snapshot> snapshots = new ArrayList<>();
        SeatDriver driver;

        Recorder(Supplier<Session> live) {
            this.live = live;
        }

        private void snap(String kind, Random rng, Consumer<SeatDriver> resume) {
            snapshots.add(new Snapshot(kind, live.get().copy(), driver.snapshotRandoms(SearchSeatPolicyTest::copyOf), resume));
        }

        @Override
        public String keepProvision(SeatView view, List<String> offer, Random rng) {
            String answer = inner.keepProvision(view, offer, rng);
            snap("补给箱留牌", rng, d -> d.resumeProvision(answer));
            return answer;
        }

        @Override
        public Optional<String> reveal(SeatView view, List<String> revealable, Random rng) {
            return inner.reveal(view, revealable, rng);
        }

        @Override
        public Optional<String> drink(SeatView view, List<String> drinkable, Random rng) {
            return inner.drink(view, drinkable, rng);
        }

        @Override
        public Optional<Gift> give(SeatView view, List<Gift> gifts, Random rng) {
            return inner.give(view, gifts, rng);
        }

        @Override
        public ActionChoice act(SeatView view, List<ActionChoice> legal, Random rng) {
            ActionChoice answer = inner.act(view, legal, rng);
            CharacterId me = view.self();
            snap("行动", rng, d -> d.resumeAction(me, answer));
            return answer;
        }

        @Override
        public int keepRowCard(SeatView view, List<NavigationCard> drawn, Random rng) {
            int answer = inner.keepRowCard(view, drawn, rng);
            snap("划船留牌", rng, d -> d.resumeRowCard(answer));
            return answer;
        }

        @Override
        public boolean refuse(SeatView view, Random rng) {
            boolean answer = inner.refuse(view, rng);
            boolean effective = answer && view.me().canAct();    // 驱动者把昏迷的人的「拒绝」退回同意
            snap("表态", rng, d -> d.resumeConsent(effective));
            return answer;
        }

        @Override
        public Optional<Fight.Side> joinStance(SeatView view, Random rng) {
            Optional<Fight.Side> answer = inner.joinStance(view, rng);
            CharacterId me = view.self();
            snap("站队", rng, d -> d.resumeStance(me, answer));
            return answer;
        }

        @Override
        public Optional<String> drinkForFight(SeatView view, List<String> drinkable, Random rng) {
            return inner.drinkForFight(view, drinkable, rng);
        }

        @Override
        public List<String> commitWeapons(SeatView view, List<String> weapons, Random rng) {
            List<String> answer = inner.commitWeapons(view, weapons, rng);
            CharacterId me = view.self();
            snap("押武器", rng, d -> d.resumeWeapons(me, answer));
            return answer;
        }

        @Override
        public PickChoice pick(SeatView view, List<PickChoice> legal, Random rng) {
            PickChoice answer = inner.pick(view, legal, rng);
            snap("挑牌", rng, d -> d.resumePick(answer));
            return answer;
        }

        @Override
        public NavigationCard steer(SeatView view, List<NavigationCard> rowStack, Random rng) {
            NavigationCard answer = inner.steer(view, rowStack, rng);
            snap("舵手挑牌", rng, d -> d.resumeSteer(answer));
            return answer;
        }

        @Override
        public Optional<Session.OverboardPlay> overboard(SeatView view, List<Session.OverboardPlay> plays,
                                                         Random rng) {
            return inner.overboard(view, plays, rng);
        }

        @Override
        public WaterPlan drinkWater(SeatView view, int ownUnits, Random rng) {
            WaterPlan answer = inner.drinkWater(view, ownUnits, rng);
            snap("喝水", rng, d -> d.resumeWater(answer));
            return answer;
        }

        @Override
        public int donateWater(SeatView view, CharacterId drinker, int shortUnits, int myUnits, boolean helpAsked,
                               int donatedSoFar, Random rng) {
            return inner.donateWater(view, drinker, shortUnits, myUnits, helpAsked, donatedSoFar, rng);
        }

        @Override
        public String label() {
            return "记录员（照第一层答）";
        }
    }

    @Test
    @DisplayName("❗推演从每一种决定接着往下走，与驱动者自己往下走一模一样：这一阶段走完，局面与随机流逐位相同")
    @Timeout(value = 300, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void resumeEntriesContinueLikeTheDriver() {
        record Config(String label, Roster roster, List<NavigationCard> deck, Provisions provisions,
                      List<WeatherCard> weather, int games) {
        }
        List<Config> configs = List.of(
                new Config("真实 8 人 · 天候", LocalData.roster().preset(8), LocalData.navigationDeck(),
                        LocalData.provisions(), LegalTest.weather(), 50),
                new Config("真实 8 人", LocalData.roster().preset(8), LocalData.navigationDeck(),
                        LocalData.provisions(), List.of(), 30),
                new Config("合成五人", SyntheticTable.roster(), SyntheticTable.deck(), TestProvisions.synthetic(),
                        List.of(), 40));
        Map<String, Integer> counts = new TreeMap<>();
        for (Config cfg : configs) {
            for (long seed = 0; seed < cfg.games(); seed++) {
                Session[] live = new Session[1];
                live[0] = LegalTest.newGame(cfg.label(), cfg.roster(), cfg.deck(), cfg.provisions(), cfg.weather(),
                        seed);
                Recorder rec = new Recorder(() -> live[0]);
                Map<CharacterId, SeatPolicy> seats = new LinkedHashMap<>();
                Map<CharacterId, SeatPolicy> plain = new LinkedHashMap<>();
                for (Survivor s : cfg.roster().survivors()) {
                    seats.put(s.id(), rec);
                    plain.put(s.id(), rec.inner);
                }
                Random rng = new Random(seed);
                SeatDriver driver = new SeatDriver(live[0], seats, rng);
                rec.driver = driver;
                while (!live[0].state().isOver()) {
                    assertTrue(live[0].state().turn() <= Simulator.TURN_LIMIT, live[0].context() + " 打不完");
                    driver.playPhase();
                    String expected = describe(live[0], rng) + describeRandoms(driver);
                    for (Snapshot snap : rec.snapshots) {
                        SeatDriver resumed = new SeatDriver(snap.session(), plain, snap.randoms());
                        snap.resume().accept(resumed);
                        resumed.finishPhase();
                        assertEquals(expected, describe(snap.session(), snap.randoms().game()) + describeRandoms(resumed),
                                "%s 第 %d 天：从「%s」接着走，与驱动者自己走的不一样".formatted(live[0].context(),
                                        live[0].state().turn(), snap.kind()));
                        counts.merge(snap.kind(), 1, Integer::sum);
                    }
                    rec.snapshots.clear();
                    if (live[0].state().isOver()) {
                        break;
                    }
                    live[0].advancePhase();
                }
            }
        }
        System.out.println("推演从一个决定接着往下走（与驱动者自己走逐位比对）：");
        counts.forEach((k, v) -> System.out.printf("  %-8s %6d%n", k, v));
        // 正向对照：「0 处不同」与「根本没比」输出一样 —— 每一种入口都必须真的比过
        for (String kind : List.of("补给箱留牌", "行动", "划船留牌", "表态", "站队", "押武器", "挑牌", "舵手挑牌", "喝水")) {
            assertTrue(counts.getOrDefault(kind, 0) > 0, "「" + kind + "」一次都没比过");
        }
    }

    private static String describeRandoms(SeatDriver driver) {
        var state = driver.snapshotRandoms(SearchSeatPolicyTest::copyOf);
        Map<CharacterId, Long> next = new TreeMap<>();
        state.seats().forEach((id, random) -> next.put(id, random.nextLong()));
        return "\nseatRandoms=" + next;
    }

    /** 一局此刻的全部（集合排好序）与随机流的下一个数，压成一段字。 */
    static String describe(Session s, Random rng) {
        StringBuilder out = new StringBuilder();
        GameState st = s.state();
        out.append("turn=").append(st.turn()).append(' ').append(st.phase()).append(" gulls=").append(st.gulls())
                .append(" over=").append(st.isOver()).append(" removed=").append(new java.util.TreeSet<>(
                        st.removedIds().stream().map(CharacterId::value).toList()))
                .append(" offline=").append(new java.util.TreeSet<>(st.offlineIds().stream()
                        .map(CharacterId::value).toList())).append('\n');
        for (CharacterId id : st.bySeat()) {
            out.append("  ").append(st.stateOf(id)).append('\n');
        }
        out.append("nav=").append(s.table().pile().order().stream().map(NavigationCard::id).toList())
                .append("\nrow=").append(s.table().rowStack().stream().map(NavigationCard::id).toList())
                .append(" rowerHand=").append(s.table().rowerHand().stream().map(NavigationCard::id).toList())
                .append("\nprovisions=").append(s.table().provisionPileOrder())
                .append("\ndiscard=").append(s.table().provisionDiscard())
                .append(" removed=").append(s.table().removedProvisions())
                .append("\ncontest=").append(s.contest()).append(" rower=").append(s.rower())
                .append("\nprogress=").append(s.progress())
                .append("\nweather=").append(s.currentWeather().map(WeatherCard::id))
                .append("\ndeeds=").append(s.deeds())
                .append("\nrng=").append(copyOf(rng).nextLong());
        return out.toString();
    }

    /** 随机流的副本（往后吐的数一模一样，互不相干）。 */
    static Random copyOf(Random rng) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
                out.writeObject(rng);
            }
            try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
                return (Random) in.readObject();
            }
        } catch (IOException | ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    // ================================================================ 看不见的变了，推演的决定不变

    @Nested
    @DisplayName("看不见的变了，推演的决定不变（同一个推演种子）")
    class HiddenInfo {

        /** 两局物资牌堆的构成一样（每种几张），谁拿了哪几张不一样。 */
        private static final Map<String, Integer> PILE = Map.of("water", 8, "knife", 1, "cash", 2, "rum", 1,
                "flail", 1, "oar", 2, "jewelry", 1, "medical_kit", 1);

        /** 船长的两局：他自己的牌与爱恨一样，别人的手牌、两副牌堆的次序、别人的爱恨都不一样。 */
        private Scenario twin(long seed, String[] mate, String[] kid) {
            return new Scenario(PILE, seed)
                    .affinities(CAPTAIN, KID, MATE, seed * 31)
                    .deal(CAPTAIN, "water", "knife").deal(MATE, mate).deal(KID, kid).toAction();
        }

        private void turnOf(Scenario s, CharacterId who) {
            while (!s.session.nextActor().orElseThrow().equals(who)) {
                s.session.markActed(s.session.nextActor().orElseThrow());
            }
        }

        /** 正向对照：两局在看不见的那几项上真的不同，否则「决定相同」什么也证明不了。 */
        private void assertHiddenDiffers(Scenario a, Scenario b) {
            assertNotEquals(a.session.state().stateOf(MATE).hand(), b.session.state().stateOf(MATE).hand());
            assertNotEquals(a.session.table().pile().order(), b.session.table().pile().order());
            assertNotEquals(a.session.table().provisionPileOrder(), b.session.table().provisionPileOrder());
            assertNotEquals(a.session.affinities().orElseThrow().love(), b.session.affinities().orElseThrow().love());
        }

        /**
         * 两个同种子的推演替身，各答一边：答案一样，每个候选推演出来的平均分也逐位一样（比只比答案灵敏得多 ——
         * 漏进来的东西未必翻得动排名，却一定改得动分数），而且真的推演过、没出错。
         */
        private <T> void same(SeatView a, SeatView b, java.util.function.BiFunction<SearchSeatPolicy, SeatView, T> ask) {
            assertEquals(a.fingerprint(), b.fingerprint(), "两边的视角不一样 —— 双胞胎没搭对");
            SearchSeatPolicy pa = new SearchSeatPolicy(SMALL, SCORING, 77);
            SearchSeatPolicy pb = new SearchSeatPolicy(SMALL, SCORING, 77);
            T x = ask.apply(pa, a);
            T y = ask.apply(pb, b);
            assertTrue(pa.rollouts() > 0, "正向对照：推演一局都没跑（只剩一个候选时不推演，那就什么也没比）");
            assertEquals(0, pa.failures() + pb.failures(), "推演出错：" + pa.lastFailure() + " / " + pb.lastFailure());
            assertEquals(java.util.Arrays.toString(pa.lastMeans), java.util.Arrays.toString(pb.lastMeans),
                    "看不见的东西改了推演出来的分数");
            assertEquals(x, y);
            assertEquals(pa.rollouts(), pb.rollouts());
        }

        @Test
        @DisplayName("轮到我行动")
        void atMyTurn() {
            Scenario a = twin(1, new String[]{"water", "cash"}, new String[]{"rum"});
            Scenario b = twin(2, new String[]{"flail", "oar"}, new String[]{"water"});
            assertHiddenDiffers(a, b);
            turnOf(a, CAPTAIN);
            turnOf(b, CAPTAIN);
            List<ActionChoice> legal = Legal.actions(a.session, CAPTAIN);
            assertEquals(legal, Legal.actions(b.session, CAPTAIN));
            same(a.view(CAPTAIN), b.view(CAPTAIN), (p, v) -> p.act(v, legal, new Random(5)));
        }

        @Test
        @DisplayName("大副要抢我：拒不拒绝")
        void whenSomeoneStealsFromMe() {
            Scenario[] twins = {twin(3, new String[]{"flail", "water"}, new String[]{"rum"}),
                    twin(4, new String[]{"oar", "cash"}, new String[]{"water"})};
            for (Scenario s : twins) {
                turnOf(s, MATE);
                s.session.declare(MATE, Contest.Kind.STEAL, CAPTAIN);
            }
            assertHiddenDiffers(twins[0], twins[1]);
            same(twins[0].view(CAPTAIN), twins[1].view(CAPTAIN), (p, v) -> p.refuse(v, new Random(9)));
        }

        @Test
        @DisplayName("别人打起来了：站不站队")
        void whenOthersFight() {
            Scenario[] twins = {twin(5, new String[]{"flail", "water"}, new String[]{"rum"}),
                    twin(6, new String[]{"oar", "cash"}, new String[]{"water"})};
            for (Scenario s : twins) {
                s.deal(JEWELER, "medical_kit");
                turnOf(s, MATE);
                s.session.declare(MATE, Contest.Kind.STEAL, JEWELER);
                s.session.consent(true);
            }
            assertHiddenDiffers(twins[0], twins[1]);
            same(twins[0].view(CAPTAIN), twins[1].view(CAPTAIN), (p, v) -> p.joinStance(v, new Random(9)));
        }

        @Test
        @DisplayName("一场里别人押下了暗牌（这一局链枷、那一局船桨）：我押不押")
        void inAFightWithHiddenWeapons() {
            Scenario[] twins = {twin(7, new String[]{"flail", "water"}, new String[]{"rum"}),
                    twin(8, new String[]{"oar", "cash"}, new String[]{"water"})};
            String[] committed = {"flail", "oar"};
            for (int i = 0; i < 2; i++) {
                Scenario s = twins[i];
                s.deal(JEWELER, "medical_kit");
                turnOf(s, MATE);
                s.session.declare(MATE, Contest.Kind.STEAL, JEWELER);
                s.session.consent(true);
                s.session.join(CAPTAIN, Fight.Side.DEFEND);
                s.session.closeStances();
                s.session.commitWeapon(MATE, committed[i]);
            }
            assertHiddenDiffers(twins[0], twins[1]);
            List<String> weapons = Legal.weapons(twins[0].session, CAPTAIN);
            assertEquals(weapons, Legal.weapons(twins[1].session, CAPTAIN));
            same(twins[0].view(CAPTAIN), twins[1].view(CAPTAIN), (p, v) -> p.commitWeapons(v, weapons,
                    new Random(9)));
        }
    }

    // ================================================================ 可复现

    @Test
    @DisplayName("同一个种子，整局一模一样（不限时的推演只从自己的随机流拿随机数）；换推演的种子，局会变（正向对照）")
    @Timeout(value = 600, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void sameSeedSameGame() {
        Roster roster = LocalData.roster().preset(8);
        HeuristicSeatPolicy layer1 = new HeuristicSeatPolicy(SMALL.withoutSearch());
        java.util.function.LongUnaryOperator[] salt = {x -> x};
        SeatPolicies mixed = (seat, seed) -> seat.equals(MATE) || seat.equals(KID)
                ? new SearchSeatPolicy(SMALL, SCORING, salt[0].applyAsLong(SearchSeatPolicy.seedFor(seat, seed)))
                : layer1;
        Simulator sim = new Simulator(roster, LocalData.navigationDeck(), LocalData.provisions(), SCORING, mixed,
                List.of());
        List<Simulator.Result> first = java.util.stream.LongStream.range(0, 6).parallel().mapToObj(sim::run).toList();
        List<Simulator.Result> again = java.util.stream.LongStream.range(0, 6).parallel().mapToObj(sim::run).toList();
        assertEquals(first, again, "同一个种子打出了两种局");
        salt[0] = x -> x + 1;
        List<Simulator.Result> other = java.util.stream.LongStream.range(0, 6).parallel().mapToObj(sim::run).toList();
        long differ = 0;
        for (int i = 0; i < first.size(); i++) {
            if (!first.get(i).equals(other.get(i))) {
                differ++;
            }
        }
        System.out.printf("推演可复现：6 局同种子两遍逐局相同；换推演种子后 %d / 6 局不同%n", differ);
        assertTrue(differ > 0, "正向对照：换了推演的种子，6 局一局都没变 —— 推演可能根本没在用自己的随机流");
    }

    @Test
    @DisplayName("❗限时截断只连累被截断的那一个决定：同种子两位，第一个决定一位到点、一位跑满，第二个决定的推演分逐位相同（审查 R12）")
    void timeoutDoesNotLeakIntoLaterDecisions() {
        Scenario s = new Scenario(Map.of("water", 8, "knife", 1, "cash", 2, "rum", 1, "flail", 1, "oar", 2,
                "jewelry", 1, "medical_kit", 1), 1)
                .affinities(CAPTAIN, KID, MATE, 31)
                .deal(CAPTAIN, "water", "knife").deal(MATE, "water", "cash").deal(KID, "rum").toAction();
        while (!s.session.nextActor().orElseThrow().equals(CAPTAIN)) {
            s.session.markActed(s.session.nextActor().orElseThrow());
        }
        List<ActionChoice> legal = Legal.actions(s.session, CAPTAIN);
        SeatView view = s.view(CAPTAIN);
        SeatPolicySettings limited = SMALL.withBudget(24, 50, 3, 1);          // 限时 50 ms；到不到点由假钟说了算

        // 跑满的那一位：钟不走，永远不到点
        SearchSeatPolicy full = new SearchSeatPolicy(limited, SCORING, 77, SearchSeatPolicy.Objective.NORMAL, () -> 0L);
        // 被截断的那一位：第一个决定定下时限之后，第一局推演一做完钟就跳过了时限；之后钟停在那里 ——
        // 第二个决定从那一刻重新起算时限，于是跑满
        long[] calls = {0};
        SearchSeatPolicy cut = new SearchSeatPolicy(limited, SCORING, 77, SearchSeatPolicy.Objective.NORMAL,
                () -> ++calls[0] <= 2 ? 0L : 1_000_000_000_000L);

        full.act(view, legal, new Random(5));
        cut.act(view, legal, new Random(5));
        assertTrue(full.rollouts() > 0, "正向对照：第一个决定真的推演了");
        assertTrue(cut.rollouts() < full.rollouts(), "正向对照：被截断的那一位第一个决定少推演了（%d 对 %d）"
                .formatted(cut.rollouts(), full.rollouts()));

        long fullBefore = full.rollouts();
        long cutBefore = cut.rollouts();
        ActionChoice a = full.act(view, legal, new Random(5));
        ActionChoice b = cut.act(view, legal, new Random(5));
        assertEquals(full.rollouts() - fullBefore, cut.rollouts() - cutBefore, "第二个决定两位都跑满");
        assertEquals(java.util.Arrays.toString(full.lastMeans), java.util.Arrays.toString(cut.lastMeans),
                "第一个决定被截断，连累了第二个决定的推演");
        assertEquals(a, b);
        assertEquals(0, full.failures() + cut.failures());
    }

    // ================================================================ 预算

    @Test
    @DisplayName("默认预算下每个决定花多久：p50 / p95（单线程，一位推演、七位第一层，两局）")
    @Timeout(value = 600, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void timingAtTheDefaultBudget() {
        SeatPolicySettings defaults = SeatPolicySettings.DEFAULTS;
        Roster roster = LocalData.roster().preset(8);
        List<SearchSeatPolicy> made = Collections.synchronizedList(new ArrayList<>());
        SeatPolicies one = SeatPolicies.one(MATE, (seat, seed) -> {
            SearchSeatPolicy p = new SearchSeatPolicy(defaults, SCORING, SearchSeatPolicy.seedFor(seat, seed));
            made.add(p);
            return p;
        }, SeatPolicies.all(new HeuristicSeatPolicy(defaults)));
        Simulator sim = new Simulator(roster, LocalData.navigationDeck(), LocalData.provisions(), SCORING, one,
                List.of());
        for (long seed = 0; seed < 2; seed++) {
            sim.run(seed);
        }
        List<Long> nanos = new ArrayList<>();
        long rollouts = 0;
        int failures = 0;
        for (SearchSeatPolicy p : made) {
            nanos.addAll(p.decisionNanos());
            rollouts += p.rollouts();
            failures += p.failures();
        }
        Collections.sort(nanos);
        assertTrue(nanos.size() >= 20, "两局只推演了 " + nanos.size() + " 个决定：量不出分布");
        double p50 = nanos.get(nanos.size() / 2) / 1e6;
        double p95 = nanos.get((int) Math.ceil(nanos.size() * 0.95) - 1) / 1e6;
        double max = nanos.getLast() / 1e6;
        System.out.printf("默认预算（每步 %d 局 · 留 %d 个 · 看 %d 天 · 限时 %d ms）：推演过的决定 %d 个，平均每个 %.0f 局；"
                        + "p50 %.0f ms · p95 %.0f ms · 最长 %.0f ms · 出错 %d%n", defaults.rollouts(), defaults.width(),
                defaults.horizonDays(), defaults.millisPerDecision(), nanos.size(), rollouts / (double) nanos.size(),
                p50, p95, max, failures);
        assertEquals(0, failures);
        // 时限在每一局推演之后查：超出的只是最后那一局（一局几毫秒，机器再忙也就几十毫秒）。
        // 不限时的话这一档 p95 实测 419 ms（2026-10-07），所以 1.4 倍（350 ms）分得开「时限管用」与「时限没在管」
        assertTrue(p95 <= defaults.millisPerDecision() * 1.4,
                "p95 %.0f ms，超出时限 %d ms 太多：时限没在管".formatted(p95, defaults.millisPerDecision()));
    }
}
