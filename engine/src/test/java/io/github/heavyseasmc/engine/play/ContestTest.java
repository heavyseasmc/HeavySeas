package io.github.heavyseasmc.engine.play;

import io.github.heavyseasmc.engine.data.TestProvisions;
import io.github.heavyseasmc.engine.model.Ability;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Provisions;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.navigation.NavigationDeck;
import io.github.heavyseasmc.engine.navigation.Selector;
import io.github.heavyseasmc.engine.state.Fight;
import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.engine.thirst.ThirstSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 换座位与抢夺：拒绝才开打（ADR-0023）。
 *
 * <p>与物资、终局两组同一个写法：每条都挑「两种实现会给出不同答案」的局面，并配对照 ——
 * 只断言「进行中交易会抛」的话，一个「行动阶段交易一律抛」的错误实现照样绿，所以收场之后要再交易一次。
 * 发牌走真实通路（补给箱）；伤害靠真的打架来凑，不直接改伤害数。
 */
class ContestTest {

    private static final CharacterId MATE = CharacterId.of("mate");         // 8 / 4
    private static final CharacterId CAPTAIN = CharacterId.of("captain");   // 7 / 5
    private static final CharacterId SAILOR = CharacterId.of("sailor");     // 6 / 6
    private static final CharacterId KID = CharacterId.of("kid");           // 3 / 9

    private static Survivor mate(int seat) {
        return new Survivor(MATE, seat, 8, 4, "base", new Ability.None());
    }

    private static Survivor captain(int seat) {
        return new Survivor(CAPTAIN, seat, 7, 5, "base", new Ability.None());
    }

    private static Survivor sailor(int seat) {
        return new Survivor(SAILOR, seat, 6, 6, "base", new Ability.OverboardImmune(true, List.of("bait_bucket")));
    }

    /** 没有技能的小孩：给「3 血、清醒与否」这类局面用，不掺偷窃。 */
    private static Survivor kid(int seat) {
        return new Survivor(KID, seat, 3, 9, "base", new Ability.None());
    }

    /** 带偷窃的小孩（与 data/roster 相同：只偷手牌、不触发战斗、对方不能临时亮牌）。 */
    private static Survivor thief(int seat) {
        return new Survivor(KID, seat, 3, 9, "base", new Ability.StealUncontested("hand", false, true));
    }

    /** 船头大副 · 船长 · 水手 · 船尾小孩；轮到的第一个人是大副。 */
    private static List<Survivor> crew() {
        return List.of(mate(1), captain(2), sailor(3), kid(4));
    }

    /** 发牌：目录里恰好每人一张，按座位顺序点名谁留哪张。发完停在行动阶段。 */
    private static Session deal(List<Survivor> crew, String... keeps) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String id : keeps) {
            counts.merge(id, 1, Integer::sum);
        }
        Provisions catalog = TestProvisions.counting(counts);
        List<NavigationCard> nav = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            nav.add(new NavigationCard("syn_" + i, 0, new Selector.Nobody(), new Selector.Nobody(), false, false));
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

    /** 让 target 挨 n 下：用这一场之外的结算入口，只为凑伤害。 */
    private static void beat(Session s, CharacterId attacker, CharacterId target, int n) {
        for (int i = 0; i < n; i++) {
            s.applyFight(Fight.between(attacker, target));
        }
    }

    private static int seat(Session s, CharacterId id) {
        return s.state().stateOf(id).seat();
    }

    private static int damage(Session s, CharacterId id) {
        return s.state().stateOf(id).damage();
    }

    private static boolean fought(Session s, CharacterId id) {
        return s.state().stateOf(id).thirst().has(ThirstSource.FOUGHT);
    }

    private static Contest.Stage stage(Session s) {
        return s.contest().orElseThrow().stage();
    }

    // ------------------------------------------------------------------ 触发条件

    @Nested
    @DisplayName("只有「清醒且不同意」才打得起来")
    class Trigger {

        @Test
        @DisplayName("清醒的目标同意：换座位当场生效，没有人打架")
        void consciousTargetAgrees() {
            Session s = deal(crew(), "water", "water", "water", "water");
            s.declare(MATE, Contest.Kind.SWAP, CAPTAIN);
            assertEquals(Contest.Stage.CONSENT, stage(s), "清醒的人要问");
            assertEquals(1, seat(s, MATE), "还没表态就不换");
            s.consent(false);
            assertTrue(s.contest().isEmpty());
            assertEquals(2, seat(s, MATE));
            assertEquals(1, seat(s, CAPTAIN));
            assertFalse(fought(s, MATE), "同意就没有战斗标记");
        }

        @Test
        @DisplayName("昏迷的目标视为同意，不问；清醒的目标要问（对照）")
        void unconsciousTargetCannotRefuse() {
            Session s = deal(crew(), "water", "water", "water", "water");
            beat(s, MATE, KID, 3);                                   // 小孩 3 血：伤害 = 体型，昏迷
            assertFalse(s.state().conditionOf(KID).canAct());
            s.declare(MATE, Contest.Kind.SWAP, KID);
            assertTrue(s.contest().isEmpty(), "昏迷者不能拒绝");
            assertEquals(4, seat(s, MATE));
            s.markActed(MATE);

            s.declare(CAPTAIN, Contest.Kind.SWAP, SAILOR);           // 对照：船长轮到了，水手清醒
            assertEquals(Contest.Stage.CONSENT, stage(s));
        }

        @Test
        @DisplayName("只有轮到的人能宣告；不能对自己")
        void onlyTheActorDeclares() {
            Session s = deal(crew(), "water", "water", "water", "water");
            assertThrows(IllegalStateException.class, () -> s.declare(CAPTAIN, Contest.Kind.SWAP, SAILOR));
            assertThrows(IllegalArgumentException.class, () -> s.declare(MATE, Contest.Kind.STEAL, MATE));
            assertTrue(s.contest().isEmpty(), "宣告失败不留下半截");
            s.declare(MATE, Contest.Kind.STEAL, SAILOR);
            assertEquals(Contest.Stage.CONSENT, stage(s));
        }
    }

    // ------------------------------------------------------------------ 结算

    @Nested
    @DisplayName("站队 · 挂武器 · 结算")
    class Resolution {

        @Test
        @DisplayName("拒绝之后防守方拉到帮手：败方每人 1 伤，全部参战者一个战斗标记，旁观者没有")
        void helperTurnsTheFight() {
            Session s = deal(crew(), "knife", "water", "water", "water");
            s.declare(MATE, Contest.Kind.STEAL, CAPTAIN);
            s.consent(true);
            assertEquals(Contest.Stage.STANCES, stage(s));
            s.join(SAILOR, Fight.Side.DEFEND);
            s.closeStances();
            s.commitWeapon(MATE, "knife");
            Fight.Outcome outcome = s.resolveContest();              // 8 + 3 = 11 对 7 + 6 = 13
            assertEquals(Fight.Side.DEFEND, outcome.winner());
            assertTrue(s.contest().isEmpty(), "防守方胜就收场，没有挑牌");
            assertEquals(1, damage(s, MATE));
            assertEquals(0, damage(s, CAPTAIN));
            assertEquals(0, damage(s, SAILOR));
            assertTrue(fought(s, MATE) && fought(s, CAPTAIN) && fought(s, SAILOR));
            assertFalse(fought(s, KID), "旁观者没有战斗标记");
            assertTrue(s.state().stateOf(MATE).hasInFront("knife"), "押下的武器结算时亮出");
        }

        @Test
        @DisplayName("平手防守方胜：座位不换（对照：多 1 点就换）")
        void tieGoesToDefender() {
            Session tie = deal(crew(), "water", "oar", "water", "water");
            tie.declare(MATE, Contest.Kind.SWAP, CAPTAIN);
            tie.consent(true);
            tie.closeStances();
            tie.commitWeapon(CAPTAIN, "oar");
            Fight.Outcome outcome = tie.resolveContest();            // 8 对 7 + 1
            assertTrue(outcome.wasTie());
            assertEquals(Fight.Side.DEFEND, outcome.winner());
            assertEquals(1, seat(tie, MATE));

            Session win = deal(crew(), "water", "oar", "water", "water");
            win.declare(MATE, Contest.Kind.SWAP, CAPTAIN);
            win.consent(true);
            win.closeStances();
            win.resolveContest();                                    // 8 对 7：船长没押那支船桨
            assertEquals(2, seat(win, MATE), "攻方胜，换座位当场生效");
        }

        @Test
        @DisplayName("站队：不清醒的人不能加入；加入之后不能反悔，也不能换边")
        void stances() {
            Session s = deal(crew(), "water", "water", "water", "water");
            beat(s, MATE, KID, 3);
            s.declare(MATE, Contest.Kind.SWAP, CAPTAIN);
            s.consent(true);
            assertThrows(IllegalArgumentException.class, () -> s.join(KID, Fight.Side.ATTACK), "昏迷者不能参战");
            s.join(SAILOR, Fight.Side.ATTACK);
            assertThrows(IllegalArgumentException.class, () -> s.join(SAILOR, Fight.Side.DEFEND));
            assertThrows(IllegalArgumentException.class, () -> s.join(CAPTAIN, Fight.Side.ATTACK), "防守方本来就在场上");
            assertThrows(IllegalStateException.class, () -> s.commitWeapon(MATE, "water"), "还在站队段，不能押武器");
        }

        @Test
        @DisplayName("挂武器：只有参战者、只押真有的；两支船桨押两次，第三次抛；押了之后面前不变，结算时才亮")
        void weaponsAreHiddenAndCounted() {
            Session s = deal(crew(), "oar", "water", "oar", "knife");
            s.giveCard(SAILOR, MATE, "oar");
            s.reveal(MATE, "oar");                                   // 大副：手上一支、面前一支
            s.declare(MATE, Contest.Kind.SWAP, CAPTAIN);
            s.consent(true);
            s.closeStances();
            assertThrows(IllegalArgumentException.class, () -> s.commitWeapon(KID, "knife"), "旁观者不能押");
            assertThrows(IllegalArgumentException.class, () -> s.commitWeapon(CAPTAIN, "knife"), "他没有这把刀");
            assertThrows(IllegalArgumentException.class, () -> s.commitWeapon(CAPTAIN, "water"), "水不是武器");
            s.commitWeapon(MATE, "oar");
            s.commitWeapon(MATE, "oar");
            assertThrows(IllegalArgumentException.class, () -> s.commitWeapon(MATE, "oar"), "只有两支");
            assertEquals(List.of("oar"), s.state().stateOf(MATE).hand(), "暗牌：押了之后手上照旧");
            assertEquals(List.of("oar"), s.state().stateOf(MATE).front());
            Fight.Outcome outcome = s.resolveContest();
            assertEquals(10, outcome.attackPower(), "8 + 1 + 1");
            assertEquals(List.of("oar", "oar"), s.state().stateOf(MATE).front(), "结算那一刻才亮出来");
            assertTrue(s.state().stateOf(MATE).hand().isEmpty());
        }

        @Test
        @DisplayName("❗面前已有的先用：面前一支船桨的人押一支，手里那支不该被翻出来")
        void frontCopiesAreUsedFirst() {
            Session s = deal(crew(), "oar", "water", "oar", "water");
            s.giveCard(SAILOR, MATE, "oar");
            s.reveal(MATE, "oar");
            s.declare(MATE, Contest.Kind.SWAP, CAPTAIN);
            s.consent(true);
            s.closeStances();
            s.commitWeapon(MATE, "oar");
            s.resolveContest();
            assertEquals(List.of("oar"), s.state().stateOf(MATE).hand(), "手里那支还是暗的");
            assertEquals(List.of("oar"), s.state().stateOf(MATE).front());
        }
    }

    // ------------------------------------------------------------------ 抢夺

    @Nested
    @DisplayName("抢夺：成功之后才挑")
    class Steal {

        @Test
        @DisplayName("攻方胜进挑牌：面前的一张进抢夺方面前")
        void pickFromFrontAfterWinning() {
            Session s = deal(crew(), "fish_spear", "knife", "water", "water");
            s.reveal(CAPTAIN, "knife");
            s.declare(MATE, Contest.Kind.STEAL, CAPTAIN);
            s.consent(true);
            s.closeStances();
            s.commitWeapon(MATE, "fish_spear");
            s.resolveContest();                                      // 8 + 4 对 7
            assertEquals(Contest.Stage.PICK, stage(s));
            s.pickFromFront("knife");
            assertTrue(s.contest().isEmpty());
            assertTrue(s.state().stateOf(MATE).hasInFront("knife"), "亮出的牌换了主人照样亮着");
            assertFalse(s.state().stateOf(CAPTAIN).hasInFront("knife"));
        }

        @Test
        @DisplayName("同意就直接挑：手牌按下标拿，进抢夺方手牌")
        void pickFromHandAfterAgreeing() {
            Session s = deal(crew(), "water", "knife", "water", "water");
            s.declare(MATE, Contest.Kind.STEAL, CAPTAIN);
            s.consent(false);
            assertEquals(Contest.Stage.PICK, stage(s));
            assertThrows(IllegalArgumentException.class, () -> s.pickFromHand(1), "他手上只有一张");
            s.pickFromHand(0);
            assertEquals(List.of("water", "knife"), s.state().stateOf(MATE).hand());
            assertTrue(s.state().stateOf(CAPTAIN).hand().isEmpty());
            assertFalse(fought(s, MATE));
        }

        @Test
        @DisplayName("被抢方身上一张都没有：挑牌直接跳过（对照：有一张就要挑）")
        void nothingToTake() {
            Session empty = deal(crew(), "water", "water", "water", "water");
            empty.giveCard(CAPTAIN, SAILOR, "water");
            empty.declare(MATE, Contest.Kind.STEAL, CAPTAIN);
            empty.consent(false);
            assertTrue(empty.contest().isEmpty());

            Session one = deal(crew(), "water", "water", "water", "water");
            one.declare(MATE, Contest.Kind.STEAL, CAPTAIN);
            one.consent(false);
            assertEquals(Contest.Stage.PICK, stage(one));
        }

        @Test
        @DisplayName("小孩偷手牌：不问、不打、只挑手牌；普通人抢同一个人要等表态（对照）")
        void kidStealsUncontested() {
            Session s = deal(List.of(thief(1), mate(2), captain(3), sailor(4)), "water", "knife", "water", "water");
            s.giveCard(CAPTAIN, MATE, "water");
            s.reveal(MATE, "water");                                 // 大副：手上一把刀，面前一张水
            s.declare(KID, Contest.Kind.STEAL, MATE);
            assertEquals(Contest.Stage.PICK, stage(s), "小孩的偷窃跳过表态");
            assertTrue(s.contest().orElseThrow().handOnly());
            assertThrows(IllegalStateException.class, () -> s.pickFromFront("water"), "亮出的牌他碰不到");
            s.pickFromHand(0);
            assertEquals(List.of("water", "knife"), s.state().stateOf(KID).hand());
            assertFalse(fought(s, MATE), "不触发战斗");
            s.markActed(KID);

            s.declare(MATE, Contest.Kind.STEAL, SAILOR);             // 对照：大副轮到了，抢清醒的水手要问
            assertEquals(Contest.Stage.CONSENT, stage(s));
        }

        @Test
        @DisplayName("小孩偷一个手上没牌的人：直接收场，不会退回去拿面前的（对照：普通人会进挑牌）")
        void kidCannotFallBackToFront() {
            Session s = deal(List.of(thief(1), mate(2), captain(3), sailor(4)), "water", "water", "water", "water");
            s.reveal(MATE, "water");                                 // 大副手上空了，面前一张
            s.declare(KID, Contest.Kind.STEAL, MATE);
            assertTrue(s.contest().isEmpty());
            assertTrue(s.state().stateOf(MATE).hasInFront("water"));
            s.markActed(KID);

            s.declare(MATE, Contest.Kind.STEAL, CAPTAIN);
            s.consent(false);
            s.pickFromHand(0);
            s.markActed(MATE);
            s.declare(CAPTAIN, Contest.Kind.STEAL, MATE);            // 普通人抢大副：面前那张照样能挑
            s.consent(false);
            assertEquals(Contest.Stage.PICK, stage(s));
        }
    }

    // ------------------------------------------------------------------ 绝境

    @Nested
    @DisplayName("绝境：每个清醒角色都能反对")
    class Ration {

        private Session rationGame() {
            Session s = deal(crew(), "ration", "water", "water", "water");
            beat(s, MATE, KID, 4);                                  // 船上留一具尸体，满足绝境前提
            return s;
        }

        @Test
        @DisplayName("逐个问完都不反对才回血；牌在询问开始前已经弃掉")
        void everyoneMayPass() {
            Session s = rationGame();
            beat(s, MATE, CAPTAIN, 1);
            assertTrue(s.beginRation(MATE, "ration").isEmpty(), "有人能反对，所以不能当场回血");
            assertFalse(s.state().stateOf(MATE).hasInHand("ration"));
            assertEquals(List.of("ration"), s.table().provisionDiscard(), "无论最后结果都先弃牌");
            assertEquals(CAPTAIN, s.contest().orElseThrow().target());

            s.consent(false);
            assertEquals(SAILOR, s.contest().orElseThrow().target(), "按座位继续问下一位清醒角色");
            assertEquals(1, damage(s, CAPTAIN), "还有人没回答时不能提前回血");
            s.consent(false);

            assertTrue(s.contest().isEmpty());
            assertEquals(0, damage(s, CAPTAIN));
        }

        @Test
        @DisplayName("有人反对后攻方获胜才回血")
        void objectionStartsTheExistingFight() {
            Session won = rationGame();
            beat(won, MATE, SAILOR, 1);
            won.beginRation(MATE, "ration");
            won.consent(true);
            assertEquals(Contest.Stage.STANCES, stage(won));
            won.closeStances();
            assertTrue(won.resolveContest().attackerGetsWhatTheyWanted(), "8 对 7，打牌方赢");
            assertEquals(0, damage(won, SAILOR), "打牌方赢，绝境生效");

            Session lost = rationGame();
            beat(lost, MATE, CAPTAIN, 1);
            lost.beginRation(MATE, "ration");
            lost.consent(true);
            lost.join(SAILOR, Fight.Side.DEFEND);                    // 8 对 7 + 6
            lost.closeStances();
            assertFalse(lost.resolveContest().attackerGetsWhatTheyWanted());
            assertEquals(1, damage(lost, CAPTAIN), "反对方赢，绝境不生效");
            assertEquals(List.of("ration"), lost.table().provisionDiscard(), "失败照样弃牌");
        }
    }

    // ------------------------------------------------------------------ 进行中

    @Nested
    @DisplayName("这一场收场之前")
    class InProgress {

        @Test
        @DisplayName("❗任何一段里都不能交易；收场之后照常能（对照）")
        void noTradesUntilDone() {
            Session s = deal(crew(), "knife", "water", "water", "water");
            s.declare(MATE, Contest.Kind.STEAL, CAPTAIN);
            assertThrows(IllegalStateException.class, () -> s.giveCard(SAILOR, KID, "water"), "表态段");
            s.consent(true);
            assertThrows(IllegalStateException.class, () -> s.giveCard(SAILOR, KID, "water"), "站队段");
            s.closeStances();
            assertThrows(IllegalStateException.class, () -> s.giveCard(SAILOR, KID, "water"), "挂武器段");
            s.commitWeapon(MATE, "knife");
            s.resolveContest();                                      // 8 + 3 对 7
            assertEquals(Contest.Stage.PICK, stage(s));
            assertThrows(IllegalStateException.class, () -> s.giveCard(SAILOR, KID, "water"), "挑牌段");
            s.pickFromHand(0);
            s.giveCard(SAILOR, KID, "water");
            assertEquals(List.of("water", "water"), s.state().stateOf(KID).hand(), "收场之后交易照常");
        }

        @Test
        @DisplayName("挑牌那一刻被抢方不能亮牌；战斗里他能，别人任何时候都能（对照）")
        void noFlashRevealAtThePick() {
            Session s = deal(crew(), "fish_spear", "knife", "water", "water");
            s.giveCard(SAILOR, CAPTAIN, "water");                    // 船长：手上一把刀、一张水
            s.declare(MATE, Contest.Kind.STEAL, CAPTAIN);
            s.consent(true);
            s.reveal(CAPTAIN, "water");                              // 站队段里被抢方照常能亮
            s.closeStances();
            s.commitWeapon(MATE, "fish_spear");
            s.resolveContest();
            assertEquals(Contest.Stage.PICK, stage(s));
            assertThrows(IllegalStateException.class, () -> s.reveal(CAPTAIN, "knife"), "挑牌那一刻");
            s.reveal(KID, "water");                                  // 别人照常
            s.pickFromHand(0);
            assertEquals(List.of("knife"), s.state().stateOf(MATE).hand());
        }

        @Test
        @DisplayName("没收场就不能记下行动、推进阶段、划船；收场之后能（对照）")
        void actionEndsOnlyAfterTheContest() {
            Session s = deal(crew(), "water", "water", "water", "water");
            s.declare(MATE, Contest.Kind.SWAP, CAPTAIN);
            assertThrows(IllegalStateException.class, () -> s.markActed(MATE));
            assertThrows(IllegalStateException.class, s::advancePhase);
            assertThrows(IllegalStateException.class, () -> s.beginRow(MATE));
            assertThrows(IllegalStateException.class, () -> s.declare(MATE, Contest.Kind.STEAL, SAILOR), "一次一场");
            s.consent(false);
            s.markActed(MATE);
            assertEquals(CAPTAIN, s.nextActor().orElseThrow());
        }

        @Test
        @DisplayName("这一场之外的直接入口（夹具）在进行中照样抛")
        void directEntryPointsAreGuarded() {
            Session s = deal(crew(), "knife", "water", "water", "water");
            s.declare(MATE, Contest.Kind.SWAP, CAPTAIN);
            assertThrows(IllegalStateException.class, () -> s.swapSeats(SAILOR, KID));
            assertThrows(IllegalStateException.class, () -> s.applyFight(Fight.between(SAILOR, KID)));
            assertThrows(IllegalStateException.class, () -> s.playWeapon(Fight.between(MATE, SAILOR), MATE, "knife"));
        }
    }
}
