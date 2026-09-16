package io.github.heavyseasmc.engine.play;

import io.github.heavyseasmc.engine.data.LocalData;
import io.github.heavyseasmc.engine.data.TestProvisions;
import io.github.heavyseasmc.engine.model.Ability;
import io.github.heavyseasmc.engine.model.Affinities;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Provisions;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.model.TreasureKind;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.navigation.NavigationDeck;
import io.github.heavyseasmc.engine.navigation.Selector;
import io.github.heavyseasmc.engine.scoring.FinalState;
import io.github.heavyseasmc.engine.scoring.ScoreSheet;
import io.github.heavyseasmc.engine.scoring.TreasureScoring;
import io.github.heavyseasmc.engine.state.Condition;
import io.github.heavyseasmc.engine.state.Fight;
import io.github.heavyseasmc.engine.state.GameState;
import io.github.heavyseasmc.engine.state.Phase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 终局与计分（ADR-0022）：爱恨置换 · 移出游戏与水中死亡 · 终局状态接进计分器。
 *
 * <p>和物资那一组同一个写法：每条都挑「两种实现会给出不同答案」的局面，并配对照。
 * 发牌走真实通路（补给箱），伤害靠真的打架与落海来凑 —— 不直接改伤害数，
 * 否则测的就不是结算那一段了。
 */
class EndgameTest {

    private static final CharacterId JEWELER = CharacterId.of("jeweler");     // 4 / 8，珠宝加倍
    private static final CharacterId COLLECTOR = CharacterId.of("collector"); // 5 / 7，美术品加倍
    private static final CharacterId CAPTAIN = CharacterId.of("captain");     // 7 / 5，现金加倍
    private static final CharacterId MATE = CharacterId.of("mate");           // 8 / 4
    private static final CharacterId SAILOR = CharacterId.of("sailor");       // 6 / 6，落水免伤（要清醒）
    private static final CharacterId KID = CharacterId.of("kid");             // 3 / 9

    /** 标准计分表：现金 1 · 美术品 2/3/3 · 珠宝 1/4/8。与 data/roster 一致（由 DataConsistency 核对）。 */
    private static final TreasureScoring STANDARD =
            new TreasureScoring(1, List.of(2, 3, 3), List.of(1, 4, 8));

    private static Survivor jeweler(int seat) {
        return new Survivor(JEWELER, seat, 4, 8, "base",
                new Ability.ScoreMultiplier(TreasureKind.JEWELRY, 2, Ability.ScoreMultiplier.Scope.SET_TOTAL));
    }

    private static Survivor collector(int seat) {
        return new Survivor(COLLECTOR, seat, 5, 7, "base",
                new Ability.ScoreMultiplier(TreasureKind.FINE_ART, 2, Ability.ScoreMultiplier.Scope.FACE_VALUE));
    }

    private static Survivor captain(int seat) {
        return new Survivor(CAPTAIN, seat, 7, 5, "base",
                new Ability.ScoreMultiplier(TreasureKind.CASH, 2, Ability.ScoreMultiplier.Scope.FACE_VALUE));
    }

    private static Survivor mate(int seat) {
        return new Survivor(MATE, seat, 8, 4, "base", new Ability.None());
    }

    private static Survivor sailor(int seat) {
        return new Survivor(SAILOR, seat, 6, 6, "base",
                new Ability.OverboardImmune(true, List.of("bait_bucket")));
    }

    private static Survivor kid(int seat) {
        return new Survivor(KID, seat, 3, 9, "base", new Ability.None());
    }

    private static NavigationCard card(String id, Selector overboard, Selector thirst) {
        return new NavigationCard(id, 0, overboard, thirst, false, false);
    }

    private static final Session.WaterChoice DRINKS_NOTHING = (prompt, state) -> List.of();

    /** 发牌：目录里恰好每人一张，按 keeps 的顺序（船头 → 船尾）点名谁留哪张。发完停在行动阶段。 */
    private static Session deal(List<Survivor> crew, String... keeps) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String id : keeps) {
            counts.merge(id, 1, Integer::sum);
        }
        Provisions catalog = TestProvisions.counting(counts);
        List<NavigationCard> nav = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            nav.add(card("syn_" + i, new Selector.Nobody(), new Selector.Nobody()));
        }
        Session s = new Session("test", new Roster(crew),
                new Table(new NavigationDeck(nav, new Random(1)), catalog, new Random(1)));
        s.beginProvision();
        for (String keep : keeps) {
            s.provisionKeep(keep);
        }
        s.advancePhase();
        assertEquals(Phase.ACTION, s.state().phase());
        return s;
    }

    /** 让 target 挨 n 下：attacker 必须明显比他壮（输的一方扣 1 点）。 */
    private static void beat(Session s, CharacterId attacker, CharacterId target, int n) {
        for (int i = 0; i < n; i++) {
            s.applyFight(Fight.between(attacker, target));
        }
    }

    private static void toNavigation(Session s) {
        s.state().bySeat().forEach(id -> {
            if (s.state().conditionOf(id).canAct() && !s.state().stateOf(id).actedThisTurn()) {
                s.markActed(id);
            }
        });
        s.advancePhase();
        assertEquals(Phase.NAVIGATION, s.state().phase());
    }

    private static NavigationReport overboard(Session s, CharacterId who) {
        return s.navigate(card("o", new Selector.Only(Set.of(who)), new Selector.Nobody()), DRINKS_NOTHING);
    }

    // ------------------------------------------------------------------ 爱恨

    @Nested
    @DisplayName("爱恨是两个置换")
    class AffinityPermutations {

        @Test
        @DisplayName("❗每个角色恰好被爱一次、被恨一次；几百局里自恋者出现过，两个置换也不是同一个抄两遍")
        void permutationsAcrossManyDeals() {
            Roster eight = LocalData.roster().preset(8);
            int narcissists = 0;
            int identical = 0;
            for (long seed = 0; seed < 500; seed++) {
                Affinities a = Affinities.random(eight, new Random(seed));
                assertEquals(eight.size(), new HashSet<>(a.love().values()).size(), "seed=" + seed + " 有人被爱了两次");
                assertEquals(eight.size(), new HashSet<>(a.hate().values()).size(), "seed=" + seed + " 有人被恨了两次");
                for (Survivor s : eight.survivors()) {
                    if (a.loveOf(s.id()).equals(s.id())) {
                        narcissists++;
                    }
                }
                if (a.love().equals(a.hate())) {
                    identical++;
                }
            }
            // 正向对照：自恋者的概率约 1 − (7/8)^8 ≈ 66% 一局，500 局里一次都没有只可能是写错了。
            assertTrue(narcissists > 0, "500 局里一个自恋者都没有 —— 置换里不许指自己？那是写错了");
            // 两个置换完全相同的概率是 1/8! —— 500 局里出现两次以上，说明爱恨用了同一次洗牌。
            assertTrue(identical <= 1, "爱恨两张表完全相同出现了 " + identical + " 次：两个置换不独立");
        }

        @Test
        @DisplayName("两个人爱同一个人 —— 构造当场拒绝")
        void rejectsNonPermutation() {
            Map<CharacterId, CharacterId> bad = Map.of(MATE, KID, KID, KID);
            Map<CharacterId, CharacterId> fine = Map.of(MATE, KID, KID, MATE);
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> new Affinities(bad, fine));
            assertTrue(e.getMessage().contains("置换"), e.getMessage());
        }

        @Test
        @DisplayName("一局只发一次；发给的人要与阵容对得上")
        void dealOnceAndCoverRoster() {
            Session s = deal(List.of(mate(1), kid(2)), "water", "water");
            Affinities other = new Affinities(Map.of(MATE, SAILOR, SAILOR, MATE), Map.of(MATE, MATE, SAILOR, SAILOR));
            assertThrows(IllegalArgumentException.class, () -> s.dealAffinities(other), "阵容里没有水手");
            Affinities ok = new Affinities(Map.of(MATE, KID, KID, MATE), Map.of(MATE, MATE, KID, KID));
            s.dealAffinities(ok);
            assertTrue(assertThrows(IllegalStateException.class, () -> s.dealAffinities(ok))
                    .getMessage().contains("只发一次"));
        }
    }

    // ------------------------------------------------------------------ 移出游戏

    @Nested
    @DisplayName("移出游戏与水中死亡")
    class RemovedFromGame {

        @Test
        @DisplayName("❗尸体被冲下去：连人带牌移出游戏，牌进「已退出」，对账照样对")
        void corpseWashedOverboard() {
            Session s = deal(List.of(mate(1), kid(2)), "water", "cash");
            beat(s, MATE, KID, 4);                               // 伤害 4 > 体型 3：死在艇上
            assertEquals(Condition.DEAD, s.state().conditionOf(KID));
            assertFalse(s.state().isRemoved(KID), "前提：死在艇上还不算移出");
            toNavigation(s);

            NavigationReport report = overboard(s, KID);
            assertEquals(List.of(KID), report.removed());
            assertTrue(s.state().isRemoved(KID));
            assertTrue(s.state().stateOf(KID).hand().isEmpty(), "牌随人离场");
            assertEquals(List.of("cash"), s.table().removedProvisions());
            s.requireNoProvisionLost("移出之后");
            assertFalse(s.state().onBoatBySeat().contains(KID), "不在船上了");
            assertTrue(s.state().bySeat().contains(KID), "但仍是这一局的人：终局要翻他的牌、算他的分");
        }

        @Test
        @DisplayName("❗清醒角色在「再挨一点就满」时落水、没有救生圈：淹死并移出 —— M1 起一直是昏迷着回船")
        void drownsAtExactlySize() {
            Session s = deal(List.of(mate(1), kid(2)), "water", "water");
            beat(s, MATE, KID, 2);                               // 伤害 2 / 体型 3：清醒
            toNavigation(s);
            NavigationReport report = overboard(s, KID);
            assertEquals(3, s.state().stateOf(KID).damage(), "只挨了落水那 1 点，恰好等于体型");
            assertEquals(Condition.DEAD, s.state().conditionOf(KID), "水里恰好等于体型、没救生圈就是死");
            assertEquals(List.of(KID), report.removed());
        }

        @Test
        @DisplayName("对照：有救生圈的昏迷者落水，以昏迷状态回到船上")
        void preserverKeepsUnconsciousAboard() {
            Session s = deal(List.of(mate(1), kid(2)), "water", "life_preserver");
            s.reveal(KID, "life_preserver");
            beat(s, MATE, KID, 3);                               // 伤害 3 = 体型：昏迷
            assertEquals(Condition.UNCONSCIOUS, s.state().conditionOf(KID));
            toNavigation(s);
            NavigationReport report = overboard(s, KID);
            assertEquals(Condition.UNCONSCIOUS, s.state().conditionOf(KID));
            assertEquals(List.of(), report.removed());
            assertTrue(s.state().stateOf(KID).hasInFront("life_preserver"), "救生圈不会被冲走");
        }

        @Test
        @DisplayName("对照：清醒的水手差一点就满时落水，免伤，照样清醒；昏迷的水手不免伤，死并移出")
        void sailorImmunityNeedsConsciousness() {
            Session awake = deal(List.of(mate(1), sailor(2)), "water", "water");
            beat(awake, MATE, SAILOR, 5);                        // 5 / 6：清醒
            toNavigation(awake);
            assertEquals(List.of(), overboard(awake, SAILOR).removed());
            assertEquals(Condition.CONSCIOUS, awake.state().conditionOf(SAILOR));

            Session out = deal(List.of(mate(1), sailor(2)), "water", "water");
            beat(out, MATE, SAILOR, 6);                          // 6 / 6：昏迷
            toNavigation(out);
            assertEquals(List.of(SAILOR), overboard(out, SAILOR).removed());
        }

        @Test
        @DisplayName("被移出的人不再是落海的候选，也不能被换座位")
        void removedIsOffTheBoat() {
            Session s = deal(List.of(mate(1), kid(2)), "water", "water");
            beat(s, MATE, KID, 4);
            toNavigation(s);
            overboard(s, KID);
            // 尸体被冲下去那一刻照样先挨落水那 1 点（伤害 4 → 5），然后才离场。要比的是「离场之后」。
            int whenRemoved = s.state().stateOf(KID).damage();
            s.advancePhase();                                    // 航海 → 物资
            s.advancePhase();                                    // 物资 → 行动
            assertThrows(IllegalArgumentException.class, () -> s.swapSeats(MATE, KID));
            toNavigation(s);
            NavigationReport again = s.navigate(
                    card("all", new Selector.Everyone(), new Selector.Nobody()), DRINKS_NOTHING);
            assertEquals(List.of(MATE), again.overboardSelected(), "「所有人」只指船上的人");
            assertEquals(whenRemoved, s.state().stateOf(KID).damage(), "已经离场的人不会再挨一次");
        }

        @Test
        @DisplayName("绝境要船上有尸体 —— 尸体被冲走之后就没有了")
        void rationNeedsCorpseOnBoat() {
            Session s = deal(List.of(mate(1), sailor(2), kid(3)), "ration", "water", "water");
            beat(s, MATE, KID, 4);
            toNavigation(s);
            overboard(s, KID);
            s.advancePhase();
            s.advancePhase();
            assertTrue(assertThrows(IllegalStateException.class, () -> s.useRation(MATE, "ration"))
                    .getMessage().contains("尸体"));
        }
    }

    // ------------------------------------------------------------------ 计分

    @Nested
    @DisplayName("终局状态接进计分器")
    class Scoring {

        /**
         * <pre>
         * 角色       爱          恨          身份            终局
         * jeweler    captain     kid         普通            活 · 珠宝 1
         * collector  collector   jeweler     自恋者          活 · 美术品 3
         * captain    kid         captain     厌世者          活 · 现金 1
         * kid        jeweler     collector   普通            死 · 现金 1
         * </pre>
         */
        private Session fixture() {
            Session s = deal(List.of(jeweler(1), collector(2), captain(3), kid(4)),
                    "jewelry", "fine_art_3a", "cash", "cash");
            s.dealAffinities(new Affinities(
                    Map.of(JEWELER, CAPTAIN, COLLECTOR, COLLECTOR, CAPTAIN, KID, KID, JEWELER),
                    Map.of(JEWELER, KID, COLLECTOR, JEWELER, CAPTAIN, CAPTAIN, KID, COLLECTOR)));
            beat(s, CAPTAIN, KID, 4);                            // 小孩死在艇上
            return s;
        }

        @Test
        @DisplayName("❗死在艇上：财宝作为遗产照算；厌世者不为所爱之人的死加分 —— 逐人逐项与手算比")
        void deadOnBoat() {
            Map<CharacterId, ScoreSheet> scores = fixture().scores(STANDARD);
            assertEquals(new ScoreSheet(8, 2, 5, 3), scores.get(JEWELER), "珠宝 1 张 ×2；所爱船长活 5；所恨小孩死 3");
            assertEquals(new ScoreSheet(7, 6, 7, 0), scores.get(COLLECTOR), "自恋者：生存分两项各 7");
            assertEquals(new ScoreSheet(0, 2, 0, 0), scores.get(CAPTAIN),
                    "厌世者：自身不计；船上唯一的尸体是他爱的小孩 —— 不加分");
            assertEquals(new ScoreSheet(0, 1, 8, 0), scores.get(KID), "死者：遗产 1、所爱珠宝商活 8");
        }

        @Test
        @DisplayName("❗同一个人的尸体被冲走：他的财宝退出，恨他的人照拿分（事件），他自己的爱恨分照拿（§7.1）")
        void removedCorpse() {
            Session s = fixture();
            toNavigation(s);
            overboard(s, KID);
            Map<CharacterId, FinalState> states = s.finalStates();
            assertFalse(states.get(KID).onBoat());
            assertFalse(states.get(KID).alive());

            Map<CharacterId, ScoreSheet> scores = s.scores(STANDARD);
            assertEquals(new ScoreSheet(0, 0, 8, 0), scores.get(KID), "现金随人离场；所爱珠宝商活着照拿 8");
            assertEquals(3, scores.get(JEWELER).hated(), "憎恨看事件：他死了就给分，不问尸体在不在");
        }

        @Test
        @DisplayName("爱恨牌没发就算终局 —— 当场拒绝，不猜")
        void requiresAffinities() {
            Session s = deal(List.of(mate(1), kid(2)), "water", "water");
            assertTrue(assertThrows(IllegalStateException.class, s::finalStates).getMessage().contains("爱恨"));
        }

        @Test
        @DisplayName("夹具靠岸：海鸥置满，这一局当场以靠岸结束")
        void landForFixture() {
            Session s = deal(List.of(mate(1), kid(2)), "water", "water");
            s.landForFixture();
            assertEquals(GameState.Outcome.LANDED, s.state().outcome().orElseThrow());
        }
    }

    // ------------------------------------------------------------------ 不变量

    @Nested
    @DisplayName("不变量")
    class Invariant {

        @Test
        @DisplayName("❗被移出的人不会回来 —— 跨状态才看得出来")
        void removedStaysRemoved() {
            GameState start = GameState.start(new Roster(List.of(mate(1), kid(2))));
            GameState gone = start.withRemoved(KID);
            assertEquals(Condition.DEAD, gone.conditionOf(KID), "被移出就是死");
            String msg = String.join(";", Invariants.checkTransition(gone, start));
            assertTrue(msg.contains("移出"), msg);
            assertTrue(Invariants.checkTransition(start, gone).isEmpty(), "移出本身是合法的一步");
        }

        @Test
        @DisplayName("被移出的人手上与面前都必须是空的")
        void removedHoldsNothing() {
            GameState start = GameState.start(new Roster(List.of(mate(1), kid(2))));
            GameState holding = start.withState(KID, start.stateOf(KID).withCard("cash")).withRemoved(KID);
            String msg = String.join(";", Invariants.check(holding));
            assertTrue(msg.contains("移出"), msg);
        }
    }
}
