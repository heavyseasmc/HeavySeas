package io.github.heavyseasmc.engine.seat;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.play.Contest;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.state.Fight;
import io.github.heavyseasmc.engine.state.Phase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static io.github.heavyseasmc.engine.seat.Scenario.CAPTAIN;
import static io.github.heavyseasmc.engine.seat.Scenario.COLLECTOR;
import static io.github.heavyseasmc.engine.seat.Scenario.DOCTOR;
import static io.github.heavyseasmc.engine.seat.Scenario.HOSTESS;
import static io.github.heavyseasmc.engine.seat.Scenario.JEWELER;
import static io.github.heavyseasmc.engine.seat.Scenario.KID;
import static io.github.heavyseasmc.engine.seat.Scenario.MATE;
import static io.github.heavyseasmc.engine.seat.Scenario.SAILOR;
import static io.github.heavyseasmc.engine.seat.Scenario.card;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 按处境打分的替身：一个个摆出来的局面里，它做的是不是常识。
 *
 * <h2>每一条都带一个反面对照</h2>
 * 只断言「它没去打爱的人」的话，一个什么都不做的替身也是绿的。所以每个局面都同时跑一个<b>把那一项估值反过来</b>的替身
 * （爱恨对调，或者整个估分反过来），断言它<b>做了</b>那件不该做的事 —— 判据在这个局面上真的分得开对错，才算数
 * （「红测要挑两种实现会给出不同答案的局面」）。
 *
 * <p>温度一律为 0：挑分最高的那一项，不消费随机数，结果不靠运气。
 */
class HeuristicSeatPolicyTest {

    private static final SeatPolicySettings DET = SeatPolicySettings.DEFAULTS.withoutSearch().withTemperature(0);
    private static final HeuristicSeatPolicy NORMAL = new HeuristicSeatPolicy(DET);
    private static final HeuristicSeatPolicy SWAPPED = new HeuristicSeatPolicy(DET, Outlook.Objective.SWAPPED);
    private static final HeuristicSeatPolicy INVERTED = new HeuristicSeatPolicy(DET, Outlook.Objective.INVERTED);

    private static Random rng() {
        return new Random(1);
    }

    /**
     * 推到航海阶段，翻掉牌堆顶那一张（不结算它），换成结算 {@code card}。
     *
     * <p>❗不用只有一两张牌的「假牌堆」：替身按整副牌的构成估谁常被点到，一副只有一张「点陪酒女落海」的牌
     * 会让它以为陪酒女每天都落海、反正要淹死 —— 2026-10-07 第一次写这些测试时就这样撞上了。
     */
    private static void navigate(Scenario s, NavigationCard card) {
        while (s.session.state().phase() != Phase.NAVIGATION) {
            s.session.advancePhase();
        }
        s.session.prepareRowStack();
        s.session.takeCardForNavigation(null);
        s.session.beginNavigation(card);
    }

    /** 让他之前的人都行动过，轮到他。 */
    private static void turnOf(Scenario s, CharacterId who) {
        while (!s.session.nextActor().orElseThrow().equals(who)) {
            s.session.markActed(s.session.nextActor().orElseThrow());
        }
    }

    @Nested
    @DisplayName("爱与恨")
    class LoveAndHate {

        @Test
        @DisplayName("有别的事可做时，不去抢自己爱的人（爱恨对调的替身就去抢了）")
        void neverRobTheLoved() {
            Scenario s = new Scenario(Map.of("water", 6, "jewelry", 1, "cash", 1), 1)
                    .affinities(CAPTAIN, KID, COLLECTOR, 7)
                    .deal(KID, "water", "water", "jewelry").deal(COLLECTOR, "cash").toAction()
                    .reveal(KID, "water").reveal(KID, "water");
            turnOf(s, CAPTAIN);
            List<ActionChoice> legal = Legal.actions(s.session, CAPTAIN);
            ActionChoice normal = NORMAL.act(s.view(CAPTAIN), legal, rng());
            ActionChoice swapped = SWAPPED.act(s.view(CAPTAIN), legal, rng());
            assertFalse(normal instanceof ActionChoice.Declare d && d.target().equals(KID),
                    "去抢 / 换爱的人了：" + normal);
            assertTrue(swapped instanceof ActionChoice.Declare d && d.target().equals(KID),
                    "反面对照没有分开：爱恨对调的替身应当冲着（它以为恨的）小孩去，实际 " + swapped);
        }

        @Test
        @DisplayName("站队站爱的人那边（爱恨对调的替身站到对面）")
        void sideWithTheLoved() {
            Scenario s = new Scenario(Map.of("water", 4), 2)
                    .affinities(MATE, HOSTESS, SAILOR, 3)
                    .deal(HOSTESS, "water").toAction();
            turnOf(s, SAILOR);
            s.session.declare(SAILOR, Contest.Kind.STEAL, HOSTESS);
            s.session.consent(true);
            assertEquals(Contest.Stage.STANCES, s.session.contest().orElseThrow().stage());
            assertEquals(Optional.of(Fight.Side.DEFEND), NORMAL.joinStance(s.view(MATE), rng()));
            assertEquals(Optional.of(Fight.Side.ATTACK), SWAPPED.joinStance(s.view(MATE), rng()),
                    "反面对照没有分开：爱恨对调的替身应当站到水手（它以为爱的）那边");
        }

        @Test
        @DisplayName("站队站在恨的人对面（爱恨对调的替身不站这边）")
        void sideAgainstTheHated() {
            Scenario s = new Scenario(Map.of("water", 4), 3)
                    .affinities(MATE, JEWELER, CAPTAIN, 4)
                    .deal(DOCTOR, "water").toAction();
            turnOf(s, CAPTAIN);
            s.session.declare(CAPTAIN, Contest.Kind.STEAL, DOCTOR);
            s.session.consent(true);
            assertEquals(Optional.of(Fight.Side.DEFEND), NORMAL.joinStance(s.view(MATE), rng()));
            assertNotEquals(Optional.of(Fight.Side.DEFEND), SWAPPED.joinStance(s.view(MATE), rng()),
                    "反面对照没有分开：爱恨对调的替身不该去打船长（它以为爱的）");
        }

        @Test
        @DisplayName("舵手挑把恨的人送下水的那张，不挑送自己、送爱的人下水的（爱恨对调就挑反）")
        void helmsmanDunksTheHated() {
            NavigationCard me = card("dunk_kid", 0, List.of(KID), List.of());
            NavigationCard hated = card("dunk_mate", 0, List.of(MATE), List.of());
            NavigationCard loved = card("dunk_hostess", 0, List.of(HOSTESS), List.of());
            Scenario s = new Scenario(Map.of("water", 2), 4, List.of(me, hated, loved))
                    .affinities(KID, HOSTESS, MATE, 5).toAction();
            s.session.advancePhase();
            assertEquals(Phase.NAVIGATION, s.session.state().phase());
            List<NavigationCard> stack = List.of(me, hated, loved);
            assertEquals(hated, NORMAL.steer(s.view(KID), stack, rng()));
            assertEquals(loved, SWAPPED.steer(s.view(KID), stack, rng()),
                    "反面对照没有分开：爱恨对调的替身应当把陪酒女（它以为恨的）送下水");
            assertEquals(1, NORMAL.keepRowCard(s.view(KID), List.of(me, hated), rng()),
                    "划船留牌：该把送恨的人下水的那张塞进划船堆");
        }

        @Test
        @DisplayName("医疗箱：只剩一点体力的爱人比自己的轻伤值，治恨的人最不值（爱恨对调就倒过来）")
        void healTheLoved() {
            Scenario s = new Scenario(Map.of("medical_kit", 2, "water", 2), 5)
                    .affinities(JEWELER, KID, MATE, 6)
                    .deal(JEWELER, "medical_kit").hurt(KID, 2).hurt(MATE, 1).hurt(JEWELER, 1);
            turnOf(s, JEWELER);
            List<ActionChoice> legal = Legal.actions(s.session, JEWELER);
            ActionChoice loved = new ActionChoice.Play("medical_kit", Optional.of(KID));
            ActionChoice self = new ActionChoice.Play("medical_kit", Optional.of(JEWELER));
            ActionChoice hated = new ActionChoice.Play("medical_kit", Optional.of(MATE));
            assertTrue(legal.containsAll(List.of(loved, self, hated)), legal.toString());
            double[] normal = NORMAL.scoreActions(s.view(JEWELER), legal);
            double[] swapped = SWAPPED.scoreActions(s.view(JEWELER), legal);
            assertTrue(normal[legal.indexOf(loved)] > normal[legal.indexOf(self)],
                    "小孩（爱的人）只剩一点体力、落一次水就淹死，治他反倒不如治自己一点轻伤");
            assertTrue(normal[legal.indexOf(self)] > normal[legal.indexOf(hated)], "治恨的人竟然比治自己值");
            assertTrue(normal[legal.indexOf(hated)] < 0, "治恨的人该是负分");
            assertTrue(swapped[legal.indexOf(hated)] > swapped[legal.indexOf(loved)],
                    "反面对照没有分开：爱恨对调的替身应当更想治大副（它以为爱的）");
            ActionChoice chosen = NORMAL.act(s.view(JEWELER), legal, rng());
            assertNotEquals(hated, chosen, "真去治恨的人了");
        }
    }

    @Nested
    @DisplayName("保命")
    class Survival {

        @Test
        @DisplayName("口渴有水就喝，快昏的人更要喝（估分反过来的替身不喝）")
        void drinkWhenThirsty() {
            NavigationCard thirst = card("thirst_kid", 0, List.of(), List.of(KID));
            Scenario s = new Scenario(Map.of("water", 4), 6)
                    .affinities(KID, JEWELER, MATE, 7).deal(KID, "water", "water").hurt(KID, 2);
            navigate(s, thirst);
            Session.ThirstPrompt prompt = s.session.thirstPending().orElseThrow();
            assertEquals(KID, prompt.who());
            assertEquals(1, prompt.remaining());
            int own = Legal.ownWaterUnits(s.session, KID, 1);
            assertEquals(new WaterPlan(1, false), NORMAL.drinkWater(s.view(KID), own, rng()));
            assertEquals(0, INVERTED.drinkWater(s.view(KID), own, rng()).units(),
                    "反面对照没有分开：估分反过来的替身应当不喝、自己找伤");
        }

        @Test
        @DisplayName("爱的人在水里要淹死了：把救生圈扔给他（爱恨对调就不扔）")
        void ringForTheDrowningLoved() {
            NavigationCard fall = card("fall_hostess", 0, List.of(HOSTESS), List.of());
            Scenario s = new Scenario(Map.of("life_preserver", 1, "water", 2), 7)
                    .affinities(MATE, HOSTESS, SAILOR, 8).deal(MATE, "life_preserver").hurt(HOSTESS, 2);
            navigate(s, fall);
            List<Session.OverboardPlay> plays = Legal.overboard(s.session, MATE);
            assertEquals(List.of(new Session.OverboardPlay("life_preserver", HOSTESS)), plays);
            assertEquals(Optional.of(plays.getFirst()), NORMAL.overboard(s.view(MATE), plays, rng()));
            assertEquals(Optional.empty(), SWAPPED.overboard(s.view(MATE), plays, rng()),
                    "反面对照没有分开：爱恨对调的替身不该救陪酒女（它以为恨的）");
        }

        @Test
        @DisplayName("快靠岸了，恨的人在水里只差一点就淹死：扔血饵（爱恨对调就不扔）")
        void baitTheHated() {
            NavigationCard fall = card("fall_hostess", 0, List.of(HOSTESS), List.of());
            Scenario s = new Scenario(Map.of("bait_bucket", 1, "water", 2), 8)
                    .affinities(DOCTOR, JEWELER, HOSTESS, 9).deal(DOCTOR, "bait_bucket").hurt(HOSTESS, 1);
            // ❗快靠岸时才值：离靠岸还远的话，体力只剩一点的陪酒女多半自己也撑不到，再花一张血饵不划算 ——
            //   那是对的判断，不是这一条要测的（第一次写这条测试时就撞上了它）
            s.session.debugSetGulls(3);
            navigate(s, fall);
            List<Session.OverboardPlay> plays = Legal.overboard(s.session, DOCTOR);
            assertEquals(1, plays.size());
            assertEquals(Optional.of(plays.getFirst()), NORMAL.overboard(s.view(DOCTOR), plays, rng()));
            assertEquals(Optional.empty(), SWAPPED.overboard(s.view(DOCTOR), plays, rng()),
                    "反面对照没有分开：爱恨对调的替身不该害陪酒女（它以为爱的）");
        }

        @Test
        @DisplayName("补给箱：渴得要命时留水，安稳时留替我翻倍的财宝（估分反过来就两样都不留）")
        void provisionsBySituation() {
            // 安稳：珠宝商有两张水、满血 —— 留珠宝（在他身上翻倍）
            Scenario calm = new Scenario(Map.of("water", 3, "jewelry", 1, "cash", 1), 9)
                    .affinities(JEWELER, DOCTOR, MATE, 10).deal(JEWELER, "water", "water");
            List<String> offer = calm.session.beginProvision();
            assertEquals(JEWELER, calm.session.provisionHolder().orElseThrow());
            assertEquals(3, offer.size());
            assertEquals("jewelry", NORMAL.keepProvision(calm.view(JEWELER), offer, rng()));
            String inverted = INVERTED.keepProvision(calm.view(JEWELER), offer, rng());
            assertNotEquals("jewelry", inverted, "反面对照没有分开：估分反过来的替身不该留最值钱的");

            // 危急：珠宝商只剩一点体力、一张水都没有，箱子里除了水只有不救命的东西 —— 留水。
            // （箱子里若有珠宝，快死的人留珠宝反而可能更值：尸体在艇上，财宝照样算分 —— 那不是这一条要测的。）
            Scenario dry = new Scenario(Map.of("water", 1, "flail", 1, "bait_bucket", 1), 9)
                    .affinities(JEWELER, DOCTOR, MATE, 10).hurt(JEWELER, 3);
            while (dry.session.state().phase() != Phase.PROVISION) {
                dry.session.advancePhase();
            }
            List<String> dryOffer = dry.session.beginProvision();
            assertEquals(JEWELER, dry.session.provisionHolder().orElseThrow());
            assertEquals("water", NORMAL.keepProvision(dry.view(JEWELER), dryOffer, rng()));
            assertNotEquals("water", INVERTED.keepProvision(dry.view(JEWELER), dryOffer, rng()));
        }
    }

    /**
     * 打架的两条各自是两面的：打得赢 / 打不赢，翻得了盘 / 翻不了盘。一个「总拒绝」或「总押」的替身必然在其中一面红 ——
     * 这两面互为对照。（估分整个反过来的替身在这里不是干净的反面：它还想把自己的牌扔掉，见本类注释之外的说明。）
     */
    @Nested
    @DisplayName("打架")
    class Fights {

        @Test
        @DisplayName("打得赢的才拒绝，打不赢的就让")
        void refuseOnlyWinnableFights() {
            Scenario strong = new Scenario(Map.of("water", 4), 10)
                    .affinities(MATE, DOCTOR, KID, 11).deal(MATE, "water").toAction();
            turnOf(strong, JEWELER);
            strong.session.declare(JEWELER, Contest.Kind.STEAL, MATE);   // 珠宝商（4）抢大副（8）
            assertTrue(NORMAL.refuse(strong.view(MATE), rng()), "大副该拒绝：平手都算他赢");

            Scenario weak = new Scenario(Map.of("water", 4), 11)
                    .affinities(KID, DOCTOR, JEWELER, 12).deal(KID, "water").toAction();
            turnOf(weak, MATE);
            weak.session.declare(MATE, Contest.Kind.STEAL, KID);        // 大副（8）抢小孩（3）
            assertFalse(NORMAL.refuse(weak.view(KID), rng()), "小孩打不过大副，拒绝只会白挨一下");
        }

        @Test
        @DisplayName("押武器：押了能翻盘就押，明知打不赢就一张不押")
        void commitOnlyWhenItSwings() {
            Scenario close = new Scenario(Map.of("knife", 1, "flare_gun", 1, "water", 2), 12)
                    .affinities(JEWELER, DOCTOR, KID, 13).deal(JEWELER, "knife", "flare_gun").toAction();
            turnOf(close, CAPTAIN);
            close.session.declare(CAPTAIN, Contest.Kind.STEAL, JEWELER); // 船长 7 对珠宝商 4
            close.session.consent(true);
            close.session.closeStances();
            List<String> weapons = Legal.weapons(close.session, JEWELER);
            assertFalse(NORMAL.commitWeapons(close.view(JEWELER), weapons, rng()).isEmpty(),
                    "押一张就能赢的架，一张都没押");

            Scenario hopeless = new Scenario(Map.of("knife", 1, "water", 2), 13)
                    .affinities(JEWELER, DOCTOR, KID, 14).deal(JEWELER, "knife").toAction();
            turnOf(hopeless, CAPTAIN);
            hopeless.session.declare(CAPTAIN, Contest.Kind.STEAL, JEWELER);
            hopeless.session.consent(true);
            hopeless.session.join(MATE, Fight.Side.ATTACK);
            hopeless.session.join(SAILOR, Fight.Side.ATTACK);
            hopeless.session.closeStances();                            // 21 对 4：押上小刀也是 7
            assertTrue(NORMAL.commitWeapons(hopeless.view(JEWELER), Legal.weapons(hopeless.session, JEWELER), rng())
                    .isEmpty(), "明知打不赢还押，白白把小刀亮出来");
        }
    }

    @Nested
    @DisplayName("记得谁帮过我")
    class Reciprocity {

        /**
         * 船长偷过水手，收藏家送过水手一张牌；之后两人都只剩两点体力、又渴了一次，水手手里有八张水（递一张不心疼）。
         *
         * <p>❗两点，不是一点：只剩一点的人再落一次水就淹死，递一张水救不了他多少（替身算得出来，这一点是对的）；
         * 两点的人这一下没水就掉进「落水即死」那一档 —— 递不递水，差的是一条命。
         * ❗水要多：水手自己也常渴，只有四张时递一张，他自己的死活就比收藏家那一点更要紧（替身也算得出来）。
         * 恩情改变的是「便宜的时候帮不帮」，不是「赔上自己也帮」。
         */
        private Scenario helpedAndHarmed(SeatPolicySettings settings) {
            NavigationCard thirst = card("thirst_two", 0, List.of(), List.of(COLLECTOR, CAPTAIN));
            Scenario s = new Scenario(Map.of("water", 12, "cash", 2), 14)
                    .affinities(SAILOR, KID, JEWELER, 15)
                    .deal(SAILOR, "water", "water", "water", "water", "water", "water", "water", "water", "cash")
                    .deal(COLLECTOR, "cash")
                    .hurt(COLLECTOR, 3).hurt(CAPTAIN, 5);
            turnOf(s, COLLECTOR);
            s.session.giveCard(COLLECTOR, SAILOR, "cash");               // 收藏家帮过水手
            s.session.markActed(COLLECTOR);
            turnOf(s, CAPTAIN);
            s.session.declare(CAPTAIN, Contest.Kind.STEAL, SAILOR);
            s.session.consent(false);                                     // 水手让了，船长摸走一张（现金）
            s.session.pickFromHand(8);
            s.session.markActed(CAPTAIN);
            navigate(s, thirst);
            return s;
        }

        @Test
        @DisplayName("一样危急的两个人：给帮过我的递水，不给抢过我的（不记恩的替身谁也不给）")
        void waterForTheHelperNotTheThief() {
            Scenario s = helpedAndHarmed(DET);
            Session.ThirstPrompt first = s.session.thirstPending().orElseThrow();
            assertEquals(COLLECTOR, first.who());
            int mine = s.session.watersOf(SAILOR);
            assertTrue(NORMAL.donateWater(s.view(SAILOR), COLLECTOR, 1, mine, false, 0, rng()) > 0,
                    "收藏家送过我牌、这一下没水就掉进落水即死的那一档，水手却一张水都不递");
            HeuristicSeatPolicy forgetful = new HeuristicSeatPolicy(DET.withMemory(0, 0, 4));
            assertEquals(0, forgetful.donateWater(s.view(SAILOR), COLLECTOR, 1, mine, false, 0, rng()),
                    "反面对照没有分开：不记恩的替身该不递（与收藏家无亲无故）");

            s.session.decideThirst(List.of());                            // 收藏家这一次没人递（只为走到船长）
            Session.ThirstPrompt second = s.session.thirstPending().orElseThrow();
            assertEquals(CAPTAIN, second.who());
            assertEquals(0, NORMAL.donateWater(s.view(SAILOR), CAPTAIN, 1, mine, false, 0, rng()),
                    "船长抢过我，水手却给他递水");
        }
    }
}
