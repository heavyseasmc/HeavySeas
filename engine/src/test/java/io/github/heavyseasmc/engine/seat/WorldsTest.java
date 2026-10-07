package io.github.heavyseasmc.engine.seat;

import io.github.heavyseasmc.engine.data.LocalData;
import io.github.heavyseasmc.engine.data.SyntheticTable;
import io.github.heavyseasmc.engine.data.TestProvisions;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Provisions;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.state.Fight;
import io.github.heavyseasmc.engine.weather.WeatherCard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 照视角抽出来的「可能的局面」（{@link Worlds}）是不是一局真正的、与视角相符的局面。
 *
 * <h2>判据：从同一个座位再看一眼，必须一模一样</h2>
 * 抽出来的那一局，从同一个座位造视角，与原来那张视角一字不差 —— 看得见的一样都没变，
 * 而看不见的确实重抽过（正向对照：别人的手牌、牌堆次序不总与真的那一局相同）。
 * 局面来自几百局随机对局的<b>每一个决定点</b>：补给箱传到一半、划船摸了牌、这一场打到押暗牌、落海还剩一批、口渴排到谁……
 * 拼不出来、对不上账，{@link Session#rebuild} 当场就抛。
 */
class WorldsTest {

    /** 每个决定点都抽一局、比一次；答案照随机席位。 */
    static final class Sampling implements SeatPolicy {
        final Supplier<Session> live;
        final RandomSeatPolicy inner = RandomSeatPolicy.indifferent();
        final Random rng = new Random(99);
        final Map<String, Integer> checked = new TreeMap<>();
        int resampledHands;
        int resampledPiles;

        Sampling(Supplier<Session> live) {
            this.live = live;
        }

        private void check(SeatView view, String what) {
            Session world = Worlds.sample(view, rng);
            SeatView again = SeatView.of(world, view.self());
            assertEquals(view.fingerprint(), again.fingerprint(), "抽出来的局从同一个座位看，与原来的视角不一样（" + what + "）");
            Session real = live.get();
            for (CharacterId id : real.state().bySeat()) {
                if (!id.equals(view.self())
                        && !real.state().stateOf(id).hand().equals(world.state().stateOf(id).hand())) {
                    resampledHands++;
                    break;
                }
            }
            if (!real.table().pile().order().equals(world.table().pile().order())) {
                resampledPiles++;
            }
            checked.merge(what, 1, Integer::sum);
        }

        @Override
        public String keepProvision(SeatView view, List<String> offer, Random r) {
            check(view, "补给箱留牌");
            return inner.keepProvision(view, offer, r);
        }

        @Override
        public Optional<String> reveal(SeatView view, List<String> revealable, Random r) {
            return inner.reveal(view, revealable, r);
        }

        @Override
        public Optional<String> drink(SeatView view, List<String> drinkable, Random r) {
            return inner.drink(view, drinkable, r);
        }

        @Override
        public ActionChoice act(SeatView view, List<ActionChoice> legal, Random r) {
            check(view, "行动");
            return inner.act(view, legal, r);
        }

        @Override
        public int keepRowCard(SeatView view, List<NavigationCard> drawn, Random r) {
            check(view, "划船留牌");
            return inner.keepRowCard(view, drawn, r);
        }

        @Override
        public boolean refuse(SeatView view, Random r) {
            check(view, "表态");
            return inner.refuse(view, r);
        }

        @Override
        public Optional<Fight.Side> joinStance(SeatView view, Random r) {
            check(view, "站队");
            return inner.joinStance(view, r);
        }

        @Override
        public List<String> commitWeapons(SeatView view, List<String> weapons, Random r) {
            check(view, "押武器");
            return inner.commitWeapons(view, weapons, r);
        }

        @Override
        public PickChoice pick(SeatView view, List<PickChoice> legal, Random r) {
            check(view, "挑牌");
            return inner.pick(view, legal, r);
        }

        @Override
        public NavigationCard steer(SeatView view, List<NavigationCard> rowStack, Random r) {
            check(view, "舵手挑牌");
            return inner.steer(view, rowStack, r);
        }

        @Override
        public Optional<Session.OverboardPlay> overboard(SeatView view, List<Session.OverboardPlay> plays, Random r) {
            check(view, "落海时打牌");
            return Optional.empty();
        }

        @Override
        public WaterPlan drinkWater(SeatView view, int ownUnits, Random r) {
            check(view, "喝水");
            return inner.drinkWater(view, ownUnits, r);
        }

        @Override
        public int donateWater(SeatView view, CharacterId drinker, int shortUnits, int myUnits, boolean helpAsked,
                               int donatedSoFar, Random r) {
            check(view, "递水");
            return inner.donateWater(view, drinker, shortUnits, myUnits, helpAsked, donatedSoFar, r);
        }

        @Override
        public String label() {
            return "抽局检查员";
        }
    }

    @Test
    @DisplayName("每个决定点上抽一局：从同一个座位再看，视角一字不差；看不见的确实重抽过")
    @Timeout(value = 300, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void sampledWorldsMatchTheView() {
        record Config(String label, Roster roster, List<NavigationCard> deck, Provisions provisions,
                      List<WeatherCard> weather, int games) {
        }
        List<Config> configs = List.of(
                new Config("真实 8 人 · 天候", LocalData.roster().preset(8), LocalData.navigationDeck(),
                        LocalData.provisions(), LegalTest.weather(), 80),
                new Config("真实 8 人", LocalData.roster().preset(8), LocalData.navigationDeck(),
                        LocalData.provisions(), List.of(), 40),
                new Config("合成五人", SyntheticTable.roster(), SyntheticTable.deck(), TestProvisions.synthetic(),
                        List.of(), 60));
        Map<String, Integer> total = new TreeMap<>();
        int hands = 0;
        int piles = 0;
        for (Config cfg : configs) {
            for (long seed = 0; seed < cfg.games(); seed++) {
                Session[] live = new Session[1];
                live[0] = LegalTest.newGame(cfg.label(), cfg.roster(), cfg.deck(), cfg.provisions(), cfg.weather(),
                        seed);
                Sampling sampler = new Sampling(() -> live[0]);
                Map<CharacterId, SeatPolicy> seats = new LinkedHashMap<>();
                for (Survivor s : cfg.roster().survivors()) {
                    seats.put(s.id(), sampler);
                }
                LegalTest.playOut(live[0], seats, new Random(seed));
                sampler.checked.forEach((k, v) -> total.merge(k, v, Integer::sum));
                hands += sampler.resampledHands;
                piles += sampler.resampledPiles;
            }
        }
        System.out.println("照视角抽局（每个决定点抽一局、从同一个座位再看一眼）：");
        total.forEach((k, v) -> System.out.printf("  %-8s %6d%n", k, v));
        System.out.printf("  其中别人的手牌与真的不同 %d 次、航海牌堆次序不同 %d 次%n", hands, piles);
        for (String kind : List.of("补给箱留牌", "行动", "划船留牌", "表态", "站队", "押武器", "挑牌", "舵手挑牌",
                "落海时打牌", "喝水", "递水")) {
            assertTrue(total.getOrDefault(kind, 0) > 0, "「" + kind + "」一次都没抽到：那种半路上的局面没被测过");
        }
        int all = total.values().stream().mapToInt(Integer::intValue).sum();
        assertTrue(hands > all / 2, "别人的手牌多半与真的一样：看不见的根本没重抽");
        assertTrue(piles > all * 9 / 10, "航海牌堆次序多半与真的一样：看不见的根本没重抽");
    }

    @Test
    @DisplayName("同一个视角、同一个种子抽出来的局一模一样；换个种子就不一样")
    void sameSeedSameWorld() {
        Session s = LegalTest.newGame("同种子", LocalData.roster().preset(8), LocalData.navigationDeck(),
                LocalData.provisions(), LegalTest.weather(), 3);
        while (s.state().phase() != io.github.heavyseasmc.engine.state.Phase.ACTION) {
            new SeatDriver(s, randomSeats(s), new Random(1)).playPhase();
            s.advancePhase();
        }
        SeatView view = SeatView.of(s, s.nextActor().orElseThrow());
        Session a = Worlds.sample(view, new Random(7));
        Session b = Worlds.sample(view, new Random(7));
        Session c = Worlds.sample(view, new Random(8));
        assertEquals(describe(a), describe(b));
        assertNotEquals(describe(a), describe(c));
    }

    @Test
    @DisplayName("两人押了暗牌、没见过的武器只剩一张：排在前面、面前有武器的人不能把它拿走（每个种子都抽得出来）")
    void scarceHiddenWeapons() {
        // 2026-10-07 推演里实测撞上的局面，手搭出来：珠宝商（排在前面）面前有船桨、押了一张；
        // 收藏家面前什么也没有、押了一张 —— 那只能是他手里那把鱼叉，而鱼叉是船长没见过的最后一张武器
        Scenario s = new Scenario(Map.of("water", 6, "oar", 1, "fish_spear", 1, "knife", 1, "cash", 2), 11)
                .affinities(Scenario.CAPTAIN, Scenario.KID, Scenario.MATE, 5)
                .deal(Scenario.JEWELER, "oar", "water").deal(Scenario.COLLECTOR, "fish_spear")
                .deal(Scenario.CAPTAIN, "knife").toAction();
        s.reveal(Scenario.JEWELER, "oar");
        while (!s.session.nextActor().orElseThrow().equals(Scenario.JEWELER)) {
            s.session.markActed(s.session.nextActor().orElseThrow());
        }
        s.session.declare(Scenario.JEWELER, io.github.heavyseasmc.engine.play.Contest.Kind.STEAL,
                Scenario.COLLECTOR);
        s.session.consent(true);
        s.session.closeStances();
        s.session.commitWeapon(Scenario.JEWELER, "oar");
        s.session.commitWeapon(Scenario.COLLECTOR, "fish_spear");
        SeatView view = s.view(Scenario.CAPTAIN);
        // 正向对照：局面真是那个样子 —— 两人各押一张，珠宝商排在前面、面前有武器，收藏家面前没有
        assertEquals(1, view.contest().orElseThrow().committed(Scenario.JEWELER));
        assertEquals(1, view.contest().orElseThrow().committed(Scenario.COLLECTOR));
        assertTrue(view.info(Scenario.JEWELER).seat() < view.info(Scenario.COLLECTOR).seat());
        assertEquals(List.of("oar"), view.info(Scenario.JEWELER).front());
        assertTrue(view.info(Scenario.COLLECTOR).front().isEmpty());
        for (long seed = 0; seed < 300; seed++) {
            Session world = Worlds.sample(view, new Random(seed));
            assertEquals(view.fingerprint(), SeatView.of(world, Scenario.CAPTAIN).fingerprint());
            assertTrue(world.state().stateOf(Scenario.COLLECTOR).hand().contains("fish_spear"),
                    "种子 " + seed + "：收藏家押下的那张不是鱼叉");
        }
    }

    private static Map<CharacterId, SeatPolicy> randomSeats(Session s) {
        Map<CharacterId, SeatPolicy> seats = new LinkedHashMap<>();
        s.state().bySeat().forEach(id -> seats.put(id, RandomSeatPolicy.indifferent()));
        return seats;
    }

    /** 一局里看不见的那几样，压成一行（比两局是不是同一局）。 */
    private static String describe(Session s) {
        StringBuilder out = new StringBuilder();
        for (CharacterId id : s.state().bySeat()) {
            out.append(id).append(s.state().stateOf(id).hand()).append(';');
        }
        out.append(s.table().pile().order().stream().map(NavigationCard::id).toList());
        out.append(s.table().provisionPileOrder());
        out.append(s.affinities().orElseThrow());
        return out.toString();
    }
}
