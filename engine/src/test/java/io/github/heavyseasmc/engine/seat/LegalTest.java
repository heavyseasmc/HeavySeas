package io.github.heavyseasmc.engine.seat;

import io.github.heavyseasmc.engine.data.LocalData;
import io.github.heavyseasmc.engine.data.SyntheticTable;
import io.github.heavyseasmc.engine.data.TestProvisions;
import io.github.heavyseasmc.engine.data.WeatherLoader;
import io.github.heavyseasmc.engine.model.Affinities;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Provisions;
import io.github.heavyseasmc.engine.model.ProvisionEffect;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.navigation.NavigationDeck;
import io.github.heavyseasmc.engine.play.Contest;
import io.github.heavyseasmc.engine.play.Invariants;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.play.Table;
import io.github.heavyseasmc.engine.navigation.Selector;
import io.github.heavyseasmc.engine.state.Fight;
import io.github.heavyseasmc.engine.state.GameState;
import io.github.heavyseasmc.engine.weather.WeatherCard;
import io.github.heavyseasmc.engine.weather.WeatherDeck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 合法清单（{@link Legal}）与 {@link Session} 的守卫是不是一回事。
 *
 * <h2>两个方向都要查</h2>
 * <ul>
 *   <li><b>列出来的都做得成</b>：每个决定点上，把清单里的每一项在那一局的副本上真的做一遍，引擎一次都不许抛；</li>
 *   <li><b>没列的做不成</b>：行动这一项（最复杂的那一张清单）再把「所有说得出口的行动」逐个试一遍 ——
 *       不在清单里的，引擎必须拒绝。只查前一个方向的话，一张「什么都不列」的清单也是绿的。</li>
 * </ul>
 * 局面来自几百局随机对局（合成阵容、真实 8 人、带天候的真实 8 人），每个决定点都查 —— 单测覆盖我想到的局面，
 * 随机对局覆盖我没想到的（「死人被医疗箱治回来」就是这么抓到的）。
 */
class LegalTest {

    // ---------------------------------------------------------------- 逐项做一遍的检查员

    /** 每次被问，都把拿到的每一个选项在那一局的副本上做一遍；做完照随机席位答。 */
    static final class Checking implements SeatPolicy {

        final Supplier<Session> live;
        final RandomSeatPolicy inner = RandomSeatPolicy.indifferent();
        final Map<String, Integer> checked = new TreeMap<>();

        Checking(Supplier<Session> live) {
            this.live = live;
        }

        private void count(String what, int n) {
            checked.merge(what, n, Integer::sum);
        }

        private static void must(Runnable r, String what) {
            try {
                r.run();
            } catch (RuntimeException e) {
                throw new AssertionError("清单里列了、引擎却拒绝：" + what + " —— " + e.getMessage(), e);
            }
        }

        private static void mustNot(Runnable r, String what) {
            try {
                r.run();
            } catch (IllegalArgumentException | IllegalStateException expected) {
                return;
            }
            throw new AssertionError("清单里没列、引擎却照做了：" + what);
        }

        @Override
        public String keepProvision(SeatView view, List<String> offer, Random rng) {
            for (String card : new LinkedHashSet<>(offer)) {
                Session c = live.get().copy();
                must(() -> c.provisionKeep(card), "留 " + card);
            }
            count("补给箱留牌", offer.size());
            return inner.keepProvision(view, offer, rng);
        }

        @Override
        public Optional<String> reveal(SeatView view, List<String> revealable, Random rng) {
            for (String card : new LinkedHashSet<>(revealable)) {
                Session c = live.get().copy();
                must(() -> c.reveal(view.self(), card), "亮 " + card);
            }
            count("亮牌", revealable.size());
            return inner.reveal(view, revealable, rng);
        }

        @Override
        public Optional<String> drink(SeatView view, List<String> drinkable, Random rng) {
            checkDrinks(view, drinkable);
            return inner.drink(view, drinkable, rng);
        }

        private void checkDrinks(SeatView view, List<String> drinkable) {
            for (String card : new LinkedHashSet<>(drinkable)) {
                Session c = live.get().copy();
                must(() -> c.drinkRum(view.self(), card), "喝 " + card);
            }
            count("喝酒", drinkable.size());
        }

        @Override
        public Optional<Gift> give(SeatView view, List<Gift> gifts, Random rng) {
            for (Gift g : gifts) {
                Session c = live.get().copy();
                must(() -> c.giveCard(view.self(), g.to(), g.card(), g.fromFront()), "送 " + g);
            }
            count("送牌", gifts.size());
            return Optional.empty();
        }

        @Override
        public ActionChoice act(SeatView view, List<ActionChoice> legal, Random rng) {
            CharacterId me = view.self();
            for (ActionChoice choice : legal) {
                Session c = live.get().copy();
                Map<CharacterId, SeatPolicy> rnd = new LinkedHashMap<>();
                c.state().bySeat().forEach(id -> rnd.put(id, RandomSeatPolicy.indifferent()));
                must(() -> {
                    new SeatDriver(c, rnd, new Random(7)).execute(me, choice);
                    Invariants.requireValid(c.state(), "合法清单", "做完 " + choice);
                }, String.valueOf(choice));
            }
            count("行动", legal.size());
            // 反方向：所有说得出口的行动里，不在清单上的引擎必须拒绝
            Session now = live.get();
            for (Contest.Kind kind : List.of(Contest.Kind.SWAP, Contest.Kind.STEAL)) {
                for (CharacterId t : now.state().bySeat()) {
                    ActionChoice d = new ActionChoice.Declare(kind, t);
                    if (!legal.contains(d)) {
                        Session c = now.copy();
                        mustNot(() -> c.declare(me, kind, t), String.valueOf(d));
                        count("行动（反方向）", 1);
                    }
                }
            }
            if (!legal.contains(ActionChoice.ROW)) {
                Session c = now.copy();
                mustNot(() -> c.beginRow(me), "划船");
                count("行动（反方向）", 1);
            }
            List<String> held = new ArrayList<>(view.hand());
            held.addAll(view.me().front());
            for (String card : new LinkedHashSet<>(held)) {
                ProvisionEffect e = now.provisions().get(card).effect();
                if (e instanceof ProvisionEffect.Heal) {
                    for (CharacterId t : now.state().bySeat()) {
                        if (!legal.contains(new ActionChoice.Play(card, Optional.of(t)))) {
                            Session c = now.copy();
                            mustNot(() -> c.useMedicalKit(me, t, card), "医疗箱治 " + t);
                            count("行动（反方向）", 1);
                        }
                    }
                } else if (e instanceof ProvisionEffect.HealAll
                        && !legal.contains(new ActionChoice.Play(card, Optional.empty()))) {
                    Session c = now.copy();
                    mustNot(() -> c.useRation(me, card), "分食");
                    count("行动（反方向）", 1);
                }
                // 已经撑开的伞再撑一次：引擎照做（只是白花一个行动），清单故意不列 —— 不算反例
            }
            return inner.act(view, legal, rng);
        }

        @Override
        public int keepRowCard(SeatView view, List<NavigationCard> drawn, Random rng) {
            for (int i = 0; i < drawn.size(); i++) {
                int index = i;
                Session c = live.get().copy();
                must(() -> c.chooseRow(index), "留第 " + i + " 张");
            }
            count("划船留牌", drawn.size());
            return inner.keepRowCard(view, drawn, rng);
        }

        @Override
        public boolean refuse(SeatView view, Random rng) {
            for (boolean fight : new boolean[]{true, false}) {
                Session c = live.get().copy();
                must(() -> c.consent(fight), fight ? "拒绝" : "同意");
            }
            count("表态", 2);
            return inner.refuse(view, rng);
        }

        @Override
        public Optional<Fight.Side> joinStance(SeatView view, Random rng) {
            for (Fight.Side side : Fight.Side.values()) {
                Session c = live.get().copy();
                must(() -> c.join(view.self(), side), "站 " + side);
            }
            count("站队", 2);
            return inner.joinStance(view, rng);
        }

        @Override
        public Optional<String> drinkForFight(SeatView view, List<String> drinkable, Random rng) {
            checkDrinks(view, drinkable);
            return Optional.empty();
        }

        @Override
        public List<String> commitWeapons(SeatView view, List<String> weapons, Random rng) {
            Session all = live.get().copy();
            for (String card : weapons) {
                must(() -> all.commitWeapon(view.self(), card), "押 " + card);
            }
            count("押武器", weapons.size());
            return inner.commitWeapons(view, weapons, rng);
        }

        @Override
        public PickChoice pick(SeatView view, List<PickChoice> legal, Random rng) {
            for (PickChoice p : legal) {
                Session c = live.get().copy();
                must(() -> {
                    if (p instanceof PickChoice.FromFront f) {
                        c.pickFromFront(f.card());
                    } else {
                        c.pickFromHand(0);
                    }
                }, String.valueOf(p));
            }
            // 反方向：他面前没有的牌、小孩的偷窃指名面前的牌，都要被拒绝
            Session c = live.get().copy();
            mustNot(() -> c.pickFromFront("water_not_in_front"), "指名一张他面前没有的");
            count("挑牌", legal.size());
            return inner.pick(view, legal, rng);
        }

        @Override
        public NavigationCard steer(SeatView view, List<NavigationCard> rowStack, Random rng) {
            for (NavigationCard card : rowStack) {
                Session c = live.get().copy();
                must(() -> c.takeCardForNavigation(card), "执行 " + card.id());
            }
            count("舵手挑牌", rowStack.size());
            return inner.steer(view, rowStack, rng);
        }

        @Override
        public Optional<Session.OverboardPlay> overboard(SeatView view, List<Session.OverboardPlay> plays, Random rng) {
            int token = view.overboard().orElseThrow().token();
            for (Session.OverboardPlay p : plays) {
                Session c = live.get().copy();
                must(() -> c.playOverboardCard(view.self(), p.target(), p.card(), token), String.valueOf(p));
            }
            count("落海时打牌", plays.size());
            return Optional.empty();
        }

        @Override
        public WaterPlan drinkWater(SeatView view, int ownUnits, Random rng) {
            SeatView.ThirstInfo t = view.thirst().orElseThrow();
            int max = Math.min(ownUnits, t.remaining());
            for (int u = 0; u <= max; u++) {
                int units = u;
                Session c = live.get().copy();
                must(() -> c.decideThirst(Collections.nCopies(units * t.waterPerSource(), view.self())),
                        "自己喝 " + u + " 次");
            }
            int over = max + 1;
            Session c = live.get().copy();
            mustNot(() -> c.decideThirst(Collections.nCopies(over * t.waterPerSource(), view.self())),
                    "自己喝 " + over + " 次（超出范围）");
            count("喝水", max + 2);
            return inner.drinkWater(view, ownUnits, rng);
        }

        @Override
        public int donateWater(SeatView view, CharacterId drinker, int shortUnits, int myUnits, boolean helpAsked,
                               int donatedSoFar, Random rng) {
            count("递水", 1);
            return inner.donateWater(view, drinker, shortUnits, myUnits, helpAsked, donatedSoFar, rng);
        }

        @Override
        public String label() {
            return "合法清单检查员";
        }
    }

    // ---------------------------------------------------------------- 跑局

    /** 用驱动者把一局打完，阶段之间核对不变量（与模拟器同一个写法）。 */
    static SeatDriver playOut(Session session, Map<CharacterId, SeatPolicy> seats, Random rng) {
        SeatDriver driver = new SeatDriver(session, seats, rng);
        while (!session.state().isOver()) {
            if (session.state().turn() > 500) {
                fail(session.context() + " 超过 500 天仍未结束");
            }
            GameState before = session.state();
            driver.playPhase();
            Invariants.requireValidTransition(before, session.state(), session.context(), "阶段 " + before.phase(),
                    session.healedSincePhaseStart());
            if (session.state().isOver()) {
                break;
            }
            session.advancePhase();
        }
        return driver;
    }

    static Session newGame(String context, Roster roster, List<NavigationCard> deck, Provisions provisions,
                           List<WeatherCard> weather, long seed) {
        Random rng = new Random(seed);
        Table table = weather.isEmpty()
                ? new Table(new NavigationDeck(deck, rng), provisions, rng)
                : new Table(new NavigationDeck(deck, rng), provisions, new WeatherDeck(weather, rng), rng);
        Session s = new Session(context + " seed=" + seed, roster, table);
        s.dealAffinities(Affinities.random(roster, rng));
        return s;
    }

    static List<WeatherCard> weather() {
        return WeatherLoader.load(Path.of(System.getProperty("heavyseas.data.dir"), "weather/default.json"));
    }

    @Test
    @DisplayName("几百局随机局面里，清单上的每一项都做得成、行动清单外的都做不成（合成 · 真实 8 人 · 带天候）")
    @Timeout(value = 300, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void everyListedOptionIsAccepted() {
        Map<String, Integer> total = new TreeMap<>();
        record Config(String label, Roster roster, List<NavigationCard> deck, Provisions provisions,
                      List<WeatherCard> weather, int games) {
        }
        List<Config> configs = List.of(
                new Config("合成 5 人", SyntheticTable.roster(), SyntheticTable.deck(), TestProvisions.synthetic(),
                        List.of(), 120),
                new Config("真实 8 人", LocalData.roster().preset(8), LocalData.navigationDeck(),
                        LocalData.provisions(), List.of(), 60),
                new Config("真实 8 人 · 天候", LocalData.roster().preset(8), LocalData.navigationDeck(),
                        LocalData.provisions(), weather(), 60));
        for (Config cfg : configs) {
            for (long seed = 0; seed < cfg.games(); seed++) {
                Session[] live = new Session[1];
                live[0] = newGame(cfg.label(), cfg.roster(), cfg.deck(), cfg.provisions(), cfg.weather(), seed);
                Checking checker = new Checking(() -> live[0]);
                Map<CharacterId, SeatPolicy> seats = new LinkedHashMap<>();
                for (Survivor s : cfg.roster().survivors()) {
                    seats.put(s.id(), checker);
                }
                SeatDriver d = playOut(live[0], seats, new Random(seed));
                assertEquals(0, d.fallbacks(), "检查员照随机席位答，不该有一次被退回：" + d.fallbackLog());
                checker.checked.forEach((k, v) -> total.merge(k, v, Integer::sum));
            }
        }
        System.out.println("合法清单逐项核对（几百局随机局面里，每一种决定各做了几项）：");
        total.forEach((k, v) -> System.out.printf("  %-10s %7d%n", k, v));
        // 正向对照：「0 项」与「根本没查」输出一样 —— 每一种决定都必须真的被查到过
        for (String kind : List.of("补给箱留牌", "亮牌", "喝酒", "送牌", "行动", "行动（反方向）", "划船留牌", "表态",
                "站队", "押武器", "挑牌", "舵手挑牌", "落海时打牌", "喝水", "递水")) {
            assertTrue(total.getOrDefault(kind, 0) > 0, "「" + kind + "」一次都没查到：清单可能根本没给出来");
        }
    }

    // ---------------------------------------------------------------- 答错了退回默认

    /** 每个决定都交回一个不合法的答案。 */
    static final class Broken implements SeatPolicy {
        @Override
        public String keepProvision(SeatView view, List<String> offer, Random rng) {
            return "不在箱子里的牌";
        }

        @Override
        public Optional<String> reveal(SeatView view, List<String> revealable, Random rng) {
            return Optional.of("不在手里的牌");
        }

        @Override
        public Optional<String> drink(SeatView view, List<String> drinkable, Random rng) {
            return Optional.of("不存在的酒");
        }

        @Override
        public Optional<Gift> give(SeatView view, List<Gift> gifts, Random rng) {
            return Optional.of(new Gift("不存在的牌", false, view.self()));
        }

        /** 单数天抢自己（不合法）；双数天真去抢一个人 —— 不然永远走不到「挑牌」那一步，那一步的退回就测不到。 */
        @Override
        public ActionChoice act(SeatView view, List<ActionChoice> legal, Random rng) {
            if (view.turn() % 2 == 1) {
                return new ActionChoice.Declare(Contest.Kind.STEAL, view.self());
            }
            return legal.stream().filter(c -> c instanceof ActionChoice.Declare d && d.kind() == Contest.Kind.STEAL)
                    .findFirst().orElse(ActionChoice.PASS);
        }

        @Override
        public int keepRowCard(SeatView view, List<NavigationCard> drawn, Random rng) {
            return 99;
        }

        @Override
        public boolean refuse(SeatView view, Random rng) {
            return true;                                                         // 合法：清醒的人才会被问
        }

        @Override
        public Optional<Fight.Side> joinStance(SeatView view, Random rng) {
            return Optional.empty();
        }

        @Override
        public List<String> commitWeapons(SeatView view, List<String> weapons, Random rng) {
            return List.of("knife", "knife", "knife", "knife");
        }

        @Override
        public PickChoice pick(SeatView view, List<PickChoice> legal, Random rng) {
            return new PickChoice.FromFront("不在他面前的牌");
        }

        @Override
        public NavigationCard steer(SeatView view, List<NavigationCard> rowStack, Random rng) {
            return new NavigationCard("ghost", 1, new Selector.Nobody(), new Selector.Nobody(), false, false);
        }

        @Override
        public WaterPlan drinkWater(SeatView view, int ownUnits, Random rng) {
            return new WaterPlan(99, true);
        }

        @Override
        public int donateWater(SeatView view, CharacterId drinker, int shortUnits, int myUnits, boolean helpAsked,
                               int donatedSoFar, Random rng) {
            return 99;
        }

        @Override
        public String label() {
            return "坏掉的策略";
        }
    }

    @Test
    @DisplayName("策略交回不合法的答案：退回默认、记一笔，一局照样打完、不变量照样成立")
    @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void illegalAnswersFallBack() {
        Roster roster = LocalData.roster().preset(8);
        int fallbacks = 0;
        Map<String, Integer> counts = new TreeMap<>();
        List<String> log = new ArrayList<>();
        for (long seed = 0; seed < 40; seed++) {
            Session s = newGame("坏策略", roster, LocalData.navigationDeck(), LocalData.provisions(), weather(), seed);
            Map<CharacterId, SeatPolicy> seats = new LinkedHashMap<>();
            for (Survivor sv : roster.survivors()) {
                seats.put(sv.id(), seed % 2 == 0 || sv.seat() % 2 == 0 ? new Broken() : RandomSeatPolicy.indifferent());
            }
            SeatDriver d = playOut(s, seats, new Random(seed));
            fallbacks += d.fallbacks();
            d.fallbackCounts().forEach((k, v) -> counts.merge(k, v, Integer::sum));
            log.addAll(d.fallbackLog());
        }
        assertTrue(fallbacks > 0, "坏策略交回了几百个不合法的答案，一次都没被记下");
        assertEquals(fallbacks, counts.values().stream().mapToInt(Integer::intValue).sum(), "按种类数的与总数对不上");
        for (String decision : List.of("补给箱留牌", "亮牌", "喝酒", "送牌", "行动", "挑牌", "舵手挑牌", "押武器", "喝水",
                "递水")) {
            assertTrue(counts.getOrDefault(decision, 0) > 0, "「" + decision + "」一次都没被退回：" + counts);
            assertTrue(log.stream().anyMatch(line -> line.contains("的" + decision + "交回了")),
                    "「" + decision + "」被退回了却没有一条详情");
        }
        System.out.printf("坏策略 40 局：退回默认 %d 次，局局打完。按种类：%s%n  例：%s%n", fallbacks, counts,
                log.getFirst());
    }

    @Test
    @DisplayName("合法清单是不可变的：策略改不了它，也就改不了驱动者拿来核对的那一份")
    void listsAreImmutable() {
        Session s = newGame("不可变", LocalData.roster().preset(8), LocalData.navigationDeck(), LocalData.provisions(),
                List.of(), 1);
        s.advancePhase();
        CharacterId actor = s.nextActor().orElseThrow();
        assertThrows(UnsupportedOperationException.class, () -> Legal.actions(s, actor).clear());
        assertThrows(UnsupportedOperationException.class, () -> Legal.gifts(s, actor).clear());
        assertThrows(UnsupportedOperationException.class, () -> Legal.drinks(s, actor).clear());
        assertThrows(UnsupportedOperationException.class, () -> Legal.reveals(s, actor).clear());
    }
}
