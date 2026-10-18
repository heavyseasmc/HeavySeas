package io.github.heavyseasmc.engine.seat;

import io.github.heavyseasmc.engine.data.LocalData;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.navigation.NavigationDeck;
import io.github.heavyseasmc.engine.play.Contest;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.play.Table;
import io.github.heavyseasmc.engine.state.Fight;
import io.github.heavyseasmc.engine.state.GameState;
import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.engine.weather.WeatherDeck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static io.github.heavyseasmc.engine.seat.Scenario.CAPTAIN;
import static io.github.heavyseasmc.engine.seat.Scenario.COLLECTOR;
import static io.github.heavyseasmc.engine.seat.Scenario.JEWELER;
import static io.github.heavyseasmc.engine.seat.Scenario.KID;
import static io.github.heavyseasmc.engine.seat.Scenario.MATE;
import static io.github.heavyseasmc.engine.seat.Scenario.SAILOR;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 视角（{@link SeatView}）的两条保证。
 *
 * <ol>
 *   <li><b>看不见的变了，视角不变、决定也不变</b>：两局只差别人的手牌、牌堆次序、别人的爱恨、别人押下的是哪一张时，
 *       同一个座位拿到的视角一个字不差，替身的每个决定也一模一样（温度不为 0 时用同一条随机流）。</li>
 *   <li><b>它是一张快照</b>：造完之后那一局接着打，视角不变；顺着它的对象图走一遍，碰不到 {@link Session}、桌面、
 *       牌堆、随机流，也碰不到可变的集合；工作线程上算出来的决定，与当场算的一样 —— 那一局同时还在被改着。</li>
 * </ol>
 *
 * <p>❗双胞胎局面是<b>手搭的</b>，不是拿「重新抽一遍看不见的牌」造的：后者若漏了哪一项没重抽，两局在那一项上碰巧相同，
 * 判据就看不见那一项的泄漏。每个双胞胎都先断言它们<b>确实</b>在看不见的那几项上不同（正向对照）。
 */
class SeatViewTest {

    private static final SeatPolicySettings DET = SeatPolicySettings.DEFAULTS.withoutSearch().withTemperature(0);
    private static final SeatPolicySettings WARM = SeatPolicySettings.DEFAULTS.withoutSearch().withTemperature(1.0);
    private static final List<SeatPolicy> POLICIES = List.of(new HeuristicSeatPolicy(DET),
            new HeuristicSeatPolicy(WARM), RandomSeatPolicy.indifferent());

    /** 两局物资牌堆的构成一样（每种几张），谁拿了哪几张不一样。 */
    private static final Map<String, Integer> PILE = Map.of("water", 8, "knife", 1, "cash", 2, "rum", 1,
            "flail", 1, "oar", 2, "jewelry", 1, "medical_kit", 1);

    private static void turnOf(Scenario s, CharacterId who) {
        while (!s.session.nextActor().orElseThrow().equals(who)) {
            s.session.markActed(s.session.nextActor().orElseThrow());
        }
    }

    /** 正向对照：两局在看不见的那几项上真的不同，否则「视角相同」什么也证明不了。 */
    private static void assertHiddenDiffers(Scenario a, Scenario b, CharacterId other) {
        assertNotEquals(a.session.state().stateOf(other).hand(), b.session.state().stateOf(other).hand(),
                "两局里 " + other + " 的手牌一样 —— 双胞胎没搭对");
        assertNotEquals(a.session.table().pile().order(), b.session.table().pile().order(), "航海牌堆次序一样");
        assertNotEquals(a.session.table().provisionPileOrder(), b.session.table().provisionPileOrder(),
                "物资牌堆次序一样");
        assertNotEquals(a.session.affinities().orElseThrow().love(), b.session.affinities().orElseThrow().love(),
                "别人的爱一样");
    }

    @Nested
    @DisplayName("看不见的变了，视角与决定都不变")
    class HiddenInfo {

        /** 船长的两局：他自己的牌与爱恨一样，别人的手牌、两副牌堆的次序、别人的爱恨都不一样。 */
        private Scenario twin(long seed, String[] mate, String[] kid) {
            return new Scenario(PILE, seed)
                    .affinities(CAPTAIN, KID, MATE, seed * 31)
                    .deal(CAPTAIN, "water", "knife").deal(MATE, mate).deal(KID, kid).toAction();
        }

        @Test
        @DisplayName("轮到我行动：视角一字不差，行动一模一样")
        void atMyTurn() {
            Scenario a = twin(1, new String[]{"water", "cash"}, new String[]{"rum"});
            Scenario b = twin(2, new String[]{"flail", "oar"}, new String[]{"water"});
            assertHiddenDiffers(a, b, MATE);
            turnOf(a, CAPTAIN);
            turnOf(b, CAPTAIN);
            assertEquals(a.view(CAPTAIN).fingerprint(), b.view(CAPTAIN).fingerprint());
            assertNotEquals(a.view(MATE).fingerprint(), b.view(MATE).fingerprint(),
                    "正向对照：大副自己的手牌不同，他的视角却一样 —— 指纹没把手牌算进去");
            List<ActionChoice> legalA = Legal.actions(a.session, CAPTAIN);
            assertEquals(legalA, Legal.actions(b.session, CAPTAIN), "合法清单也只该取决于看得见的东西");
            for (SeatPolicy p : POLICIES) {
                assertEquals(p.act(a.view(CAPTAIN), legalA, new Random(5)),
                        p.act(b.view(CAPTAIN), legalA, new Random(5)), p.label());
                assertEquals(p.keepRowCard(a.view(CAPTAIN), a.session.table().pile().order().subList(0, 2),
                                new Random(5)),
                        p.keepRowCard(b.view(CAPTAIN), a.session.table().pile().order().subList(0, 2),
                                new Random(5)), p.label());
            }
        }

        @Test
        @DisplayName("一场里别人押下了暗牌（这一局小刀、那一局船桨）：我的视角与押不押都一样")
        void inAFightWithHiddenWeapons() {
            Scenario[] twins = {twin(3, new String[]{"flail", "water"}, new String[]{"rum"}),
                    twin(4, new String[]{"oar", "cash"}, new String[]{"water"})};
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
            assertHiddenDiffers(twins[0], twins[1], MATE);
            assertEquals(twins[0].view(CAPTAIN).fingerprint(), twins[1].view(CAPTAIN).fingerprint());
            assertEquals(1, twins[0].view(CAPTAIN).contest().orElseThrow().committed(MATE), "押了几张是公开的");
            List<String> weapons = Legal.weapons(twins[0].session, CAPTAIN);
            assertEquals(weapons, Legal.weapons(twins[1].session, CAPTAIN));
            for (SeatPolicy p : POLICIES) {
                assertEquals(p.commitWeapons(twins[0].view(CAPTAIN), weapons, new Random(9)),
                        p.commitWeapons(twins[1].view(CAPTAIN), weapons, new Random(9)), p.label());
            }
        }

        @Test
        @DisplayName("别人在结算口渴（他手里有几张水看不见）：我递不递水都一样")
        void whileSomeoneElseIsThirsty() {
            NavigationCard thirst = Scenario.card("thirst_mate", 0, List.of(), List.of(MATE));
            Scenario[] twins = {twin(5, new String[]{"water", "water"}, new String[]{"rum"}),
                    twin(6, new String[]{"cash", "jewelry"}, new String[]{"water"})};
            for (Scenario s : twins) {
                while (s.session.state().phase() != Phase.NAVIGATION) {
                    s.session.advancePhase();
                }
                // ❗不先翻牌堆顶那一张：两局牌堆次序不同，翻出来的那张「已执行」是公开的，两个视角就该不同了
                s.session.beginNavigation(thirst);
                assertEquals(MATE, s.session.thirstPending().orElseThrow().who());
            }
            assertHiddenDiffers(twins[0], twins[1], MATE);
            assertEquals(twins[0].view(CAPTAIN).fingerprint(), twins[1].view(CAPTAIN).fingerprint());
            for (SeatPolicy p : POLICIES) {
                assertEquals(p.donateWater(twins[0].view(CAPTAIN), MATE, 1, 1, true, 0, new Random(3)),
                        p.donateWater(twins[1].view(CAPTAIN), MATE, 1, 1, true, 0, new Random(3)), p.label());
            }
        }

        @Test
        @DisplayName("补给箱在别人手上（箱子里是什么看不见）：视角一字不差")
        void whileSomeoneElseHoldsTheBox() {
            Scenario a = new Scenario(PILE, 7).affinities(CAPTAIN, KID, MATE, 70).deal(CAPTAIN, "water");
            Scenario b = new Scenario(PILE, 8).affinities(CAPTAIN, KID, MATE, 80).deal(CAPTAIN, "water");
            a.session.beginProvision();
            b.session.beginProvision();
            assertEquals(JEWELER, a.session.provisionHolder().orElseThrow());
            assertNotEquals(a.session.provisionOffer(), b.session.provisionOffer(), "两局箱子里的牌一样 —— 没搭对");
            assertEquals(a.view(CAPTAIN).fingerprint(), b.view(CAPTAIN).fingerprint());
            assertTrue(a.view(CAPTAIN).box().orElseThrow().offer().isEmpty(), "箱子在珠宝商手上，船长看得见里面");
            assertEquals(a.session.provisionOffer(), a.view(JEWELER).box().orElseThrow().offer(), "拿着箱子的人要看得见");
        }
    }

    // ---------------------------------------------------------------- 快照

    /** 一局真实 8 人局（带天候），打到第 3 天的行动阶段开头。 */
    private static Session midGame(long seed) {
        Roster roster = LocalData.roster().preset(8);
        Random rng = new Random(seed);
        Session s = new Session("快照 seed=" + seed, roster, new Table(new NavigationDeck(LocalData.navigationDeck(), rng),
                LocalData.provisions(), new WeatherDeck(LegalTest.weather(), rng), rng));
        s.dealAffinities(io.github.heavyseasmc.engine.model.Affinities.random(roster, rng));
        Map<CharacterId, SeatPolicy> seats = new LinkedHashMap<>();
        roster.survivors().forEach(sv -> seats.put(sv.id(), new HeuristicSeatPolicy(DET)));
        SeatDriver d = new SeatDriver(s, seats, rng);
        while (!(s.state().turn() == 3 && s.state().phase() == Phase.ACTION) && !s.state().isOver()) {
            d.playPhase();
            if (!s.state().isOver()) {
                s.advancePhase();
            }
        }
        return s;
    }

    @Test
    @DisplayName("造完之后那一局接着打下去，视角一个字不变")
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void viewIsFrozen() {
        Session s = midGame(11);
        assertEquals(Phase.ACTION, s.state().phase(), "没打到第 3 天的行动阶段");
        List<SeatView> views = new ArrayList<>();
        List<String> before = new ArrayList<>();
        for (CharacterId id : s.state().bySeat()) {
            SeatView v = SeatView.of(s, id);
            views.add(v);
            before.add(v.fingerprint());
            v.state();                                   // 惰性拼出来的那一份也要在改之前拼一次
        }
        GameState gameBefore = views.getFirst().state();
        Map<CharacterId, SeatPolicy> seats = new LinkedHashMap<>();
        s.state().bySeat().forEach(id -> seats.put(id, new HeuristicSeatPolicy(DET)));
        SeatDriver d = new SeatDriver(s, seats, new Random(1));
        while (!s.state().isOver()) {
            d.playPhase();
            if (!s.state().isOver()) {
                s.advancePhase();
            }
        }
        assertTrue(s.state().turn() > 3, "这一局没有接着往下打：快照不变什么也证明不了");
        for (int i = 0; i < views.size(); i++) {
            assertEquals(before.get(i), views.get(i).fingerprint(), "那一局打完，" + views.get(i).self() + " 的视角变了");
        }
        assertTrue(gameBefore == views.getFirst().state(), "拼好的状态被换掉了");
    }

    @Test
    @DisplayName("顺着视角的对象图走一遍：碰不到那一局、桌面、牌堆、随机流，也碰不到可变的集合")
    void objectGraphHoldsNoLiveState() throws IllegalAccessException {
        Session s = midGame(12);
        List<Object> roots = new ArrayList<>();
        for (CharacterId id : s.state().bySeat()) {
            SeatView v = SeatView.of(s, id);
            v.state();
            roots.add(v);
        }
        // 再造几张有补给箱、这一场、口渴在进行中的视角：那几项平时是空的，走不到
        Scenario fight = new Scenario(PILE, 13).affinities(CAPTAIN, KID, MATE, 5).deal(CAPTAIN, "knife")
                .deal(MATE, "flail").toAction();
        turnOf(fight, MATE);
        fight.session.declare(MATE, Contest.Kind.STEAL, CAPTAIN);
        fight.session.consent(true);
        fight.session.closeStances();
        fight.session.commitWeapon(CAPTAIN, "knife");
        roots.add(fight.view(CAPTAIN));
        Scenario box = new Scenario(PILE, 14).affinities(CAPTAIN, KID, MATE, 6);
        box.session.beginProvision();
        roots.add(box.view(JEWELER));
        Map<Object, Boolean> seen = new IdentityHashMap<>();
        int[] visited = {0};
        for (Object root : roots) {
            walk(root, "view", seen, visited);
        }
        assertTrue(visited[0] > 200, "只走了 " + visited[0] + " 个对象 —— 走法有问题，什么都没查到");
        System.out.printf("视角对象图：走了 %d 个对象，没有一个是活的那一局、桌面、牌堆、随机流或可变集合%n", visited[0]);
    }

    private static final Set<String> IMMUTABLE_JDK = Set.of("java.lang.String", "java.lang.Integer",
            "java.lang.Long", "java.lang.Boolean", "java.lang.Double", "java.lang.Character");

    private static void walk(Object o, String path, Map<Object, Boolean> seen, int[] visited)
            throws IllegalAccessException {
        if (o == null || o.getClass().isEnum() || IMMUTABLE_JDK.contains(o.getClass().getName())
                || seen.put(o, Boolean.TRUE) != null) {
            return;
        }
        visited[0]++;
        String name = o.getClass().getName();
        if (o instanceof Session || o instanceof Table || o instanceof NavigationDeck || o instanceof WeatherDeck
                || o instanceof Random) {
            fail(path + " 碰到了活的 " + name);
        }
        if (o instanceof Optional<?> opt) {
            walk(opt.orElse(null), path + ".get", seen, visited);
            return;
        }
        if (o instanceof Collection<?> || o instanceof Map<?, ?>) {
            boolean immutable = name.startsWith("java.util.ImmutableCollections$")
                    || name.startsWith("java.util.Collections$Unmodifiable")
                    || name.equals("java.util.Collections$CopiesList")
                    || name.equals("java.util.Collections$EmptyList") || name.equals("java.util.Collections$EmptySet")
                    || name.equals("java.util.Collections$EmptyMap");
            if (!immutable) {
                fail(path + " 是一个可变集合：" + name);
            }
            if (o instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    walk(e.getKey(), path + ".key", seen, visited);
                    walk(e.getValue(), path + "[" + e.getKey() + "]", seen, visited);
                }
            } else {
                int i = 0;
                for (Object e : (Collection<?>) o) {
                    walk(e, path + "[" + i++ + "]", seen, visited);
                }
            }
            return;
        }
        if (!name.startsWith("io.github.heavyseasmc.")) {
            fail(path + " 是一个没认出来的类型：" + name + "（加进白名单之前先确认它不可变）");
        }
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) {
                    continue;
                }
                f.setAccessible(true);
                walk(f.get(o), path + "." + f.getName(), seen, visited);
            }
        }
    }

    @Test
    @DisplayName("工作线程上的决定与当场算的一模一样，哪怕那一局同时还在被改")
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void decisionsOnWorkerThreads() throws Exception {
        Session s = midGame(21);
        CharacterId actor = s.nextActor().orElseThrow();
        SeatView view = SeatView.of(s, actor);
        List<ActionChoice> legal = Legal.actions(s, actor);
        HeuristicSeatPolicy policy = new HeuristicSeatPolicy(WARM);
        ActionChoice expected = policy.act(view, legal, new Random(42));
        String fingerprint = view.fingerprint();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<List<ActionChoice>>> jobs = new ArrayList<>();
            for (int t = 0; t < 6; t++) {
                jobs.add(pool.submit(() -> {
                    List<ActionChoice> out = new ArrayList<>();
                    for (int k = 0; k < 40; k++) {
                        out.add(policy.act(view, legal, new Random(42)));
                    }
                    return out;
                }));
            }
            // 与此同时，拥有那一局的线程把它接着打下去
            Map<CharacterId, SeatPolicy> seats = new LinkedHashMap<>();
            s.state().bySeat().forEach(id -> seats.put(id, new HeuristicSeatPolicy(DET)));
            SeatDriver d = new SeatDriver(s, seats, new Random(2));
            while (!s.state().isOver()) {
                d.playPhase();
                if (!s.state().isOver()) {
                    s.advancePhase();
                }
            }
            for (Future<List<ActionChoice>> job : jobs) {
                for (ActionChoice got : job.get()) {
                    assertEquals(expected, got);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(fingerprint, view.fingerprint());
    }
}
