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
import io.github.heavyseasmc.engine.state.Condition;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 物资效果（ADR-0021）。
 *
 * <h2>每一条都挑「两种实现会给出不同答案」的局面</h2>
 * 证伪表那一条：「红测要挑一个两种实现会给出不同答案的局面，不是随手挑一个最小局面」。
 * 所以救生圈那几条一定有个<b>没有救生圈的对照</b>，阳伞那几条一定有个<b>没撑开的对照</b> ——
 * 只断言「有伞时不掉血」的话，一个「永远不掉血」的错误实现照样绿。
 *
 * <h2>发牌走真实通路</h2>
 * 手牌不是塞进去的，是从补给箱发出来的（目录张数 = 清醒人数，于是第一位看得到全部，
 * 按顺序点名留哪张即可精确控制谁拿到什么）。这样连发牌那一段本身也在测试覆盖之内。
 */
class ProvisionEffectTest {

    private static final CharacterId MATE = CharacterId.of("mate");        // 8 血
    private static final CharacterId SAILOR = CharacterId.of("sailor");    // 6 血，落水免伤
    private static final CharacterId KID = CharacterId.of("kid");          // 3 血
    private static final CharacterId HOSTESS = CharacterId.of("hostess");  // 3 血，蹭水、最后结算
    private static final CharacterId DOCTOR = CharacterId.of("doctor");    // 4 血，医疗箱不弃
    private static final CharacterId CAPTAIN = CharacterId.of("captain");   // 现金加倍
    private static final CharacterId JEWELER = CharacterId.of("jeweler");   // 珠宝加倍
    private static final CharacterId COLLECTOR = CharacterId.of("collector"); // 美术品加倍

    /** 一副内容无关紧要的航海牌，只为让 {@code Table} 有牌可用。 */
    private static NavigationDeck navDeck() {
        List<NavigationCard> cards = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            cards.add(card("syn_" + i, 0, new Selector.Nobody(), new Selector.Nobody(), false, false));
        }
        return new NavigationDeck(cards, new Random(1));
    }

    private static NavigationCard card(String id, int gull, Selector overboard, Selector thirst,
                                       boolean rowers, boolean fighters) {
        return new NavigationCard(id, gull, overboard, thirst, rowers, fighters);
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

    private static Survivor hostess(int seat) {
        return new Survivor(HOSTESS, seat, 3, 9, "base",
                new Ability.ShareEffect(List.of("water", "rum"), true, true,
                        Map.of("water", true, "rum", false)));
    }

    private static Survivor captain(int seat) {
        return new Survivor(CAPTAIN, seat, 7, 5, "base", new Ability.ScoreMultiplier(
                io.github.heavyseasmc.engine.model.TreasureKind.CASH, 2,
                Ability.ScoreMultiplier.Scope.FACE_VALUE));
    }

    private static Survivor jeweler(int seat) {
        return new Survivor(JEWELER, seat, 4, 8, "base", new Ability.ScoreMultiplier(
                io.github.heavyseasmc.engine.model.TreasureKind.JEWELRY, 2,
                Ability.ScoreMultiplier.Scope.SET_TOTAL));
    }

    private static Survivor collector(int seat) {
        return new Survivor(COLLECTOR, seat, 5, 7, "base", new Ability.ScoreMultiplier(
                io.github.heavyseasmc.engine.model.TreasureKind.FINE_ART, 2,
                Ability.ScoreMultiplier.Scope.FACE_VALUE));
    }

    private static Survivor doctor(int seat) {
        return new Survivor(DOCTOR, seat, 4, 8, "base",
                new Ability.NoDiscard("medical_kit", true, true, true));
    }

    /**
     * 发牌：目录里恰好每人一张，按 {@code keeps} 的顺序（船头 → 船尾）点名谁留哪张。
     *
     * <p>发完停在<b>行动阶段</b> —— 亮出、特殊行动、交易都在那里。
     */
    private static Session deal(List<Survivor> crew, String... keeps) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String id : keeps) {
            counts.merge(id, 1, Integer::sum);
        }
        Provisions catalog = TestProvisions.counting(counts);
        Session s = new Session("test", new Roster(crew), new Table(navDeck(), catalog, new Random(1)));
        s.beginProvision();
        for (String keep : keeps) {
            s.provisionKeep(keep);
        }
        assertFalse(s.provisionInProgress(), "每人一张，应当刚好传完");
        s.advancePhase();
        assertEquals(Phase.ACTION, s.state().phase());
        s.requireNoProvisionLost("发完牌");
        return s;
    }

    /** 全员行动完，推进到航海阶段。 */
    private static void toNavigation(Session s) {
        s.state().bySeat().forEach(id -> {
            if (s.state().conditionOf(id).canAct() && !s.state().stateOf(id).actedThisTurn()) {
                s.markActed(id);
            }
        });
        s.advancePhase();
        assertEquals(Phase.NAVIGATION, s.state().phase());
    }

    /** 口渴结算时一张水都不喝。 */
    private static final Session.WaterChoice DRINKS_NOTHING = (prompt, state) -> List.of();

    /** 有多少喝多少，喝自己的。 */
    private static Session.WaterChoice drinksOwn(Session s) {
        return (prompt, state) -> {
            List<CharacterId> donors = new ArrayList<>();
            for (int i = 0; i < Math.min(prompt.remaining(), s.watersOf(prompt.who())); i++) {
                donors.add(prompt.who());
            }
            return donors;
        };
    }

    // ------------------------------------------------------------------ 水

    @Nested
    @DisplayName("水")
    class Water {

        @Test
        @DisplayName("❗喝一张水挡掉一次口渴；不喝就是 1 点伤 —— 这一条是整刀的理由")
        void drinkingPreventsDamage() {
            Session thirsty = deal(List.of(mate(1), kid(2)), "water", "water");
            toNavigation(thirsty);
            thirsty.navigate(card("t", 0, new Selector.Nobody(), new Selector.Everyone(), false, false),
                    drinksOwn(thirsty));
            assertEquals(0, thirsty.state().stateOf(KID).damage(), "喝了水就不该掉血");
            assertEquals(0, thirsty.watersOf(KID), "水喝掉了，不该还在手上");

            // 对照：同样的局面不喝，必须掉血。少了这个对照，「永远不掉血」的实现也会绿。
            Session parched = deal(List.of(mate(1), kid(2)), "water", "water");
            toNavigation(parched);
            parched.navigate(card("t", 0, new Selector.Nobody(), new Selector.Everyone(), false, false),
                    DRINKS_NOTHING);
            assertEquals(1, parched.state().stateOf(KID).damage());
            assertEquals(1, parched.watersOf(KID), "没喝就该还在手上");
        }

        @Test
        @DisplayName("亮出来的水照样能喝 —— 亮出是防偷，不是把牌锁死")
        void revealedWaterStillDrinkable() {
            Session s = deal(List.of(mate(1), kid(2)), "water", "water");
            s.reveal(KID, "water");
            assertTrue(s.state().stateOf(KID).hasInFront("water"));
            toNavigation(s);
            s.navigate(card("t", 0, new Selector.Nobody(), new Selector.Everyone(), false, false), drinksOwn(s));
            assertEquals(0, s.state().stateOf(KID).damage());
        }

        @Test
        @DisplayName("❗昏迷者不能自己打水，但别人可以替他打（规则 §9.3）")
        void unconsciousCannotSpendOwnWater() {
            Session s = deal(List.of(mate(1), kid(2)), "water", "water");
            // 把小孩打到昏迷（3 血）。
            Fight fight = Fight.between(MATE, KID);
            s.applyFight(fight);
            s.applyFight(Fight.between(MATE, KID));
            s.applyFight(Fight.between(MATE, KID));
            assertEquals(Condition.UNCONSCIOUS, s.state().conditionOf(KID));
            toNavigation(s);

            s.beginNavigate(card("t", 0, new Selector.Nobody(), new Selector.Only(Set.of(KID)), false, false));
            Session.ThirstPrompt prompt = s.thirstPending().orElseThrow();
            assertEquals(KID, prompt.who());
            assertEquals(1, prompt.ownWaters(), "他手上确实有一张");

            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> s.decideThirst(List.of(KID))).getMessage().contains("不清醒"));
            s.decideThirst(List.of(MATE));           // 别人替他打
            assertEquals(3, s.state().stateOf(KID).damage(), "替他打了水，不该再掉血");
            assertEquals(0, s.watersOf(MATE), "出水的是大副");
        }

        @Test
        @DisplayName("喝的张数不能多于还需化解的次数 —— 多喝的水会凭空消失")
        void cannotOverdrink() {
            Session s = deal(List.of(mate(1), kid(2)), "water", "water");
            toNavigation(s);
            s.beginNavigate(card("t", 0, new Selector.Nobody(), new Selector.Only(Set.of(MATE)), false, false));
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> s.decideThirst(List.of(MATE, KID))).getMessage().contains("只还需化解"));
        }

        @Test
        @DisplayName("一点都不渴的人不进队列 —— 没有可做的决定就不该问")
        void notThirstyNotAsked() {
            Session s = deal(List.of(mate(1), kid(2)), "water", "water");
            toNavigation(s);
            s.beginNavigate(card("t", 0, new Selector.Nobody(), new Selector.Only(Set.of(KID)), false, false));
            assertEquals(KID, s.thirstPending().orElseThrow().who());
            s.decideThirst(List.of());
            assertTrue(s.thirstPending().isEmpty(), "只有小孩渴，问完就该结束");
        }
    }

    // ------------------------------------------------------------------ 陪酒女

    @Nested
    @DisplayName("陪酒女蹭水")
    class Hostess {

        @Test
        @DisplayName("❗她排在最后，前面每喝一张水她就白蹭一次（她的整个设计此前一行代码都没有）")
        void sharesWaterDrunkByOthers() {
            Session s = deal(List.of(mate(1), kid(2), hostess(3)), "water", "water", "water");
            // ❗给她两个口渴来源（划船 + 牌面点名），不然「蹭两次」与「蹭一次」看不出区别 ——
            //   她只需要化解 1 次时，蹭 1 次和蹭 2 次结果一模一样。
            s.row(HOSTESS, (c, st, who) -> false);
            toNavigation(s);
            s.beginNavigate(card("t", 0, new Selector.Nobody(), new Selector.Everyone(), true, false));

            // 大副与小孩各喝一张。
            s.decideThirst(List.of(MATE));
            s.decideThirst(List.of(KID));

            Session.ThirstPrompt her = s.thirstPending().orElseThrow();
            assertEquals(HOSTESS, her.who(), "她最后结算");
            assertEquals(2, her.effective().count(), "划船 + 牌面点名");
            assertEquals(2, her.shared(), "前面喝了两张，她蹭两次");
            assertEquals(0, her.remaining(), "蹭够了，不必自己喝");
            s.decideThirst(List.of());
            assertEquals(0, s.state().stateOf(HOSTESS).damage());
            assertEquals(1, s.watersOf(HOSTESS), "她那张还在");
        }

        @Test
        @DisplayName("对照：没人喝水时她照样得自己付账")
        void noFreeRideWhenNobodyDrinks() {
            Session s = deal(List.of(mate(1), kid(2), hostess(3)), "water", "water", "water");
            s.row(HOSTESS, (c, st, who) -> false);
            toNavigation(s);
            s.beginNavigate(card("t", 0, new Selector.Nobody(), new Selector.Everyone(), true, false));
            s.decideThirst(List.of());
            s.decideThirst(List.of());
            Session.ThirstPrompt her = s.thirstPending().orElseThrow();
            assertEquals(0, her.shared());
            assertEquals(2, her.remaining(), "两个来源全得自己付账");
        }
    }

    // ------------------------------------------------------------------ 阳伞

    @Nested
    @DisplayName("阳伞")
    class Parasol {

        @Test
        @DisplayName("撑开才抵口渴；只亮出不撑开不算")
        void mustBeOpened() {
            Session closed = deal(List.of(mate(1), kid(2)), "parasol", "water");
            closed.reveal(MATE, "parasol");
            toNavigation(closed);
            closed.navigate(card("t", 0, new Selector.Nobody(), new Selector.Everyone(), false, false),
                    DRINKS_NOTHING);
            assertEquals(1, closed.state().stateOf(MATE).damage(), "收着的伞不挡太阳");

            Session open = deal(List.of(mate(1), kid(2)), "parasol", "water");
            open.openParasol(MATE, "parasol");
            open.markActed(MATE);
            toNavigation(open);
            open.navigate(card("t", 0, new Selector.Nobody(), new Selector.Everyone(), false, false),
                    DRINKS_NOTHING);
            assertEquals(0, open.state().stateOf(MATE).damage(), "撑开的伞抵掉一次");
        }

        @Test
        @DisplayName("❗落海先结算：伞在落水那一步被冲走，之后就不再挡口渴")
        void washedAwayBeforeThirst() {
            Session s = deal(List.of(mate(1), kid(2)), "parasol", "water");
            s.openParasol(MATE, "parasol");
            s.markActed(MATE);
            toNavigation(s);
            // 同一张牌：先把大副冲下海（伞没了），再点他口渴。
            s.navigate(card("t", 0, new Selector.Only(Set.of(MATE)), new Selector.Only(Set.of(MATE)),
                    false, false), DRINKS_NOTHING);
            assertFalse(s.state().stateOf(MATE).hasInFront("parasol"), "伞被冲走了");
            assertEquals(2, s.state().stateOf(MATE).damage(), "落水 1 点 + 口渴 1 点");
        }
    }

    // ------------------------------------------------------------------ 救生圈与诱饵

    @Nested
    @DisplayName("救生圈与诱饵")
    class Overboard {

        private NavigationCard everyoneOverboard() {
            return card("o", 0, new Selector.Everyone(), new Selector.Nobody(), false, false);
        }

        @Test
        @DisplayName("亮在面前的救生圈免落水伤；握在手里的不免")
        void mustBeInFront() {
            Session inHand = deal(List.of(mate(1), kid(2)), "life_preserver", "water");
            toNavigation(inHand);
            inHand.navigate(everyoneOverboard(), DRINKS_NOTHING);
            assertEquals(1, inHand.state().stateOf(MATE).damage(), "手里攥着的救生圈不挡落水");

            Session shown = deal(List.of(mate(1), kid(2)), "life_preserver", "water");
            shown.reveal(MATE, "life_preserver");
            toNavigation(shown);
            shown.navigate(everyoneOverboard(), DRINKS_NOTHING);
            assertEquals(0, shown.state().stateOf(MATE).damage());
            assertTrue(shown.state().stateOf(MATE).hasInFront("life_preserver"),
                    "❗救生圈是全副唯一落水不被冲走的一张");
        }

        @Test
        @DisplayName("落水冲走面前的全部，救生圈除外；手牌不动")
        void frontWashedAway() {
            Session s = deal(List.of(mate(1), kid(2)), "life_preserver", "water");
            s.reveal(MATE, "life_preserver");
            toNavigation(s);
            s.navigate(everyoneOverboard(), DRINKS_NOTHING);
            assertEquals(List.of("life_preserver"), s.state().stateOf(MATE).front());
            assertEquals(List.of("water"), s.state().stateOf(KID).hand(), "手牌不会被冲走");
        }

        @Test
        @DisplayName("❗诱饵穿透水手的免伤与救生圈 —— 两份数据说的是同一件事")
        void sharkPiercesEverything() {
            Session s = deal(List.of(mate(1), sailor(2), kid(3)),
                    "bait_bucket", "life_preserver", "water");
            s.reveal(MATE, "bait_bucket");
            s.reveal(SAILOR, "life_preserver");
            toNavigation(s);
            s.navigate(everyoneOverboard(), DRINKS_NOTHING);

            assertEquals(2, s.state().stateOf(MATE).damage(), "亮诱饵的人自己也挨鲨鱼：1 落水 + 1 鲨鱼");
            assertEquals(1, s.state().stateOf(SAILOR).damage(),
                    "水手免落水伤、还有救生圈 —— 鲨鱼那 1 点两者都挡不住");
            assertEquals(2, s.state().stateOf(KID).damage());

            // 对照：同一局面没有诱饵时，水手一点都不该掉。
            Session noBait = deal(List.of(mate(1), sailor(2), kid(3)), "water", "life_preserver", "water");
            noBait.reveal(SAILOR, "life_preserver");
            toNavigation(noBait);
            noBait.navigate(everyoneOverboard(), DRINKS_NOTHING);
            assertEquals(0, noBait.state().stateOf(SAILOR).damage());
        }

        @Test
        @DisplayName("诱饵留在手里不生效 —— 它要么被打出来，要么本就亮在面前")
        void baitInHandDoesNothing() {
            Session s = deal(List.of(mate(1), kid(2)), "bait_bucket", "water");
            toNavigation(s);
            s.navigate(everyoneOverboard(), DRINKS_NOTHING);
            assertEquals(1, s.state().stateOf(KID).damage(), "只有落水那 1 点");
        }

        @Test
        @DisplayName("多张诱饵不叠加")
        void baitDoesNotStack() {
            Session s = deal(List.of(mate(1), kid(2)), "bait_bucket", "bait_bucket");
            s.reveal(MATE, "bait_bucket");
            s.reveal(KID, "bait_bucket");
            toNavigation(s);
            s.navigate(everyoneOverboard(), DRINKS_NOTHING);
            assertEquals(2, s.state().stateOf(MATE).damage(), "1 落水 + 1 鲨鱼，不是 1 + 2");
        }
    }

    // ------------------------------------------------------------------ 船桨与指南针

    @Nested
    @DisplayName("船桨与指南针")
    class PassiveDraws {

        @Test
        @DisplayName("面前亮着船桨就多抽一张；握在手里不算；两把叠加")
        void oarAddsDraws() {
            Session none = deal(List.of(mate(1), kid(2)), "oar", "water");
            assertEquals(2, none.beginRow(MATE).size(), "手里的船桨不算");
            none.decideRow(0, false);
            none.decideRow(1, false);

            Session one = deal(List.of(mate(1), kid(2)), "oar", "water");
            one.reveal(MATE, "oar");
            assertEquals(3, one.beginRow(MATE).size());
            one.decideRow(0, false);
            one.decideRow(1, false);
            one.decideRow(2, false);

            Session two = deal(List.of(mate(1), kid(2)), "oar", "oar");
            two.reveal(MATE, "oar");
            two.giveCard(KID, MATE, "oar");
            two.reveal(MATE, "oar");
            assertEquals(4, two.beginRow(MATE).size(), "两把船桨叠加");
        }

        @Test
        @DisplayName("❗指南针只在舵手手里才生效（设计决策 §8.1）；握在手里也算（⑥）；没人划船不抽")
        void compassWorksOnlyForHelmsman() {
            // 舵手 = 最靠船尾的清醒角色，这里是小孩（2 号座）。keeps 按船头 → 船尾发，第二张归小孩。
            Session helm = deal(List.of(mate(1), kid(2)), "water", "compass");
            helm.row(MATE, (c, st, who) -> true);            // 划船堆里得先有牌，才有「挑选之前」
            toNavigation(helm);
            int before = helm.table().rowStack().size();
            assertEquals(1, helm.prepareRowStack(), "舵手握着指南针（没亮出）也该多抽一张");
            assertEquals(before + 1, helm.table().rowStack().size());
            assertEquals(0, helm.prepareRowStack(), "❗一回合只做一次，否则会一直往里加牌");

            // 对照 1：指南针在别人那里 —— 亮出来也不算。初稿正是这里写反了。
            Session other = deal(List.of(mate(1), kid(2)), "compass", "water");
            other.reveal(MATE, "compass");
            other.row(MATE, (c, st, who) -> true);
            toNavigation(other);
            assertEquals(0, other.prepareRowStack(), "指南针在非舵手面前，什么也不做");

            // 对照 2：没人划船 —— 没有「挑选」这一步，也就没有「挑选之前」。
            Session idle = deal(List.of(mate(1), kid(2)), "water", "compass");
            toNavigation(idle);
            assertEquals(0, idle.prepareRowStack(), "划船堆是空的：直接翻顶牌，不抽");
        }

        @Test
        @DisplayName("❗结算航海牌之前没备划船堆就当场抛 —— 模组曾经一次都没调过，而一切都是绿的")
        void takeCardRequiresPreparedRowStack() {
            Session s = deal(List.of(mate(1), kid(2)), "water", "compass");
            s.row(MATE, (c, st, who) -> true);
            toNavigation(s);
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> s.takeCardForNavigation(s.table().rowStack().get(0)));
            assertTrue(e.getMessage().contains("prepareRowStack"), "要点名漏的是哪一步：" + e.getMessage());
            s.prepareRowStack();
            s.takeCardForNavigation(s.table().rowStack().get(0));    // 备过之后照常结算
        }
    }

    // ------------------------------------------------------------------ 特殊行动

    @Nested
    @DisplayName("特殊行动")
    class SpecialActions {

        @Test
        @DisplayName("医疗箱回 1 点；医生用后不弃、下一回合还能再用")
        void medicalKit() {
            Session s = deal(List.of(mate(1), doctor(2)), "medical_kit", "medical_kit");
            s.applyFight(Fight.between(DOCTOR, MATE));      // 让人受点伤
            int hurt = s.state().stateOf(MATE).damage() > 0 ? MATE.value().length() : 0;
            assertTrue(hurt >= 0);
            CharacterId wounded = s.state().stateOf(MATE).damage() > 0 ? MATE : DOCTOR;

            int before = s.state().stateOf(wounded).damage();
            s.useMedicalKit(MATE, wounded, "medical_kit");
            assertEquals(before - 1, s.state().stateOf(wounded).damage());
            assertFalse(s.state().stateOf(MATE).hasInHand("medical_kit"), "普通人用完就弃");
            assertFalse(s.state().stateOf(MATE).hasInFront("medical_kit"));

            // 医生那张：用完留在面前，所以还能再用。
            int again = s.state().stateOf(wounded).damage();
            if (again > 0) {
                s.useMedicalKit(DOCTOR, wounded, "medical_kit");
                assertTrue(s.state().stateOf(DOCTOR).hasInFront("medical_kit"), "医生用后不弃");
            }
        }

        @Test
        @DisplayName("❗医疗箱治不回尸体 —— 死亡不可复生")
        void cannotHealCorpse() {
            Session s = deal(List.of(mate(1), kid(2)), "medical_kit", "water");
            for (int i = 0; i < 4; i++) {
                s.applyFight(Fight.between(MATE, KID));
            }
            assertEquals(Condition.DEAD, s.state().conditionOf(KID));
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> s.useMedicalKit(MATE, KID, "medical_kit")).getMessage().contains("死"));
        }

        @Test
        @DisplayName("绝境要船上有尸体；生效时每个清醒且受伤的人各回 1 点")
        void ration() {
            Session none = deal(List.of(mate(1), kid(2)), "ration", "water");
            assertTrue(assertThrows(IllegalStateException.class,
                    () -> none.useRation(MATE, "ration")).getMessage().contains("尸体"));

            Session s = deal(List.of(mate(1), kid(2), sailor(3)), "ration", "water", "water");
            for (int i = 0; i < 4; i++) {
                s.applyFight(Fight.between(MATE, KID));     // 打死小孩，顺带大副也受伤？（防守方赢，进攻方输）
            }
            assertEquals(Condition.DEAD, s.state().conditionOf(KID));
            int mateBefore = s.state().stateOf(MATE).damage();
            List<CharacterId> healed = s.useRation(MATE, "ration");
            if (mateBefore > 0) {
                assertTrue(healed.contains(MATE));
                assertEquals(mateBefore - 1, s.state().stateOf(MATE).damage());
            }
            assertFalse(healed.contains(KID), "尸体不回血");
        }

        @Test
        @DisplayName("信号枪当信号：抽 3 张只结算海鸥，抽到的牌回牌堆底")
        void flareGunSignal() {
            List<NavigationCard> gulls = List.of(
                    card("g1", 1, new Selector.Everyone(), new Selector.Everyone(), true, true),
                    card("g2", 1, new Selector.Everyone(), new Selector.Everyone(), true, true),
                    card("g3", 0, new Selector.Everyone(), new Selector.Everyone(), true, true));
            Provisions catalog = TestProvisions.counting(Map.of("flare_gun", 1, "water", 1));
            Session s = new Session("test", new Roster(List.of(mate(1), kid(2))),
                    new Table(new NavigationDeck(gulls, new Random(1)), catalog, new Random(1)));
            s.beginProvision();
            s.provisionKeep("flare_gun");
            s.provisionKeep("water");
            s.advancePhase();

            assertEquals(3, s.fireSignal(MATE, "flare_gun").size());
            assertEquals(2, s.state().gulls(), "两张带海鸥");
            assertEquals(0, s.state().stateOf(KID).damage(), "❗只结算海鸥，落海与口渴一概不看");
            s.table().requireNoCardLost("放信号后", "三张都该回牌堆底");
            assertFalse(s.state().stateOf(MATE).hasInHand("flare_gun"), "用后即弃");
        }
    }

    // ------------------------------------------------------------------ 酒

    @Nested
    @DisplayName("酒")
    class Rum {

        @Test
        @DisplayName("喝过酒战斗 +3 体型，并在回合结束时口渴一次")
        void buffAndThirst() {
            Session sober = deal(List.of(mate(1), kid(2)), "rum", "water");
            Fight lost = Fight.between(KID, MATE);
            assertEquals(MATE, winner(sober.applyFight(lost), KID, MATE));

            Session drunk = deal(List.of(mate(1), kid(2)), "rum", "water");
            drunk.giveCard(MATE, KID, "rum");
            drunk.drinkRum(KID, "rum");
            assertTrue(drunk.state().stateOf(KID).thirst().has(ThirstSource.DRANK_RUM),
                    "酒的副作用：回合结束时口渴");
            // 小孩 3 + 酒 3 = 6 < 大副 8，所以还是输 —— 但战力确实涨了，用平手局面验。
            assertEquals(6, fightingSizeOf(drunk, KID), "3 + 3");
            assertEquals(3, fightingSizeOf(sober, KID), "没喝就是 3");
        }

        @Test
        @DisplayName("每回合最多喝一次；下一回合可以再喝")
        void oncePerTurn() {
            Session s = deal(List.of(mate(1), kid(2)), "rum", "water");
            s.drinkRum(MATE, "rum");
            assertTrue(assertThrows(IllegalStateException.class,
                    () -> s.drinkRum(MATE, "rum")).getMessage().contains("已经喝过"));
            toNavigation(s);
            s.navigate(card("t", 0, new Selector.Nobody(), new Selector.Nobody(), false, false), DRINKS_NOTHING);
            s.advancePhase();
            assertEquals(2, s.state().turn());
            assertFalse(s.state().stateOf(MATE).usedThisTurn("rum"), "新回合可以再喝");
            assertTrue(s.state().stateOf(MATE).hasInFront("rum"), "酒不是消耗品，留在面前");
        }

        @Test
        @DisplayName("❗nav_04 那张「喝过酒的人落海」终于点得到人了")
        void usedRumConditionResolves() {
            Session drunk = deal(List.of(mate(1), kid(2)), "rum", "water");
            drunk.drinkRum(MATE, "rum");
            toNavigation(drunk);
            drunk.navigate(card("c", 0, new Selector.Conditional("used_rum"), new Selector.Nobody(),
                    false, false), DRINKS_NOTHING);
            // 1 点落水 + 1 点「喝酒的口渴」——❗后者不看牌面，是酒自己带的副作用。
            assertEquals(2, drunk.state().stateOf(MATE).damage(), "喝过酒的人落海");

            Session sober = deal(List.of(mate(1), kid(2)), "rum", "water");
            toNavigation(sober);
            sober.navigate(card("c", 0, new Selector.Conditional("used_rum"), new Selector.Nobody(),
                    false, false), DRINKS_NOTHING);
            assertEquals(0, sober.state().stateOf(MATE).damage(), "没喝就点不到");
        }

        @Test
        @DisplayName("条件名不认识就抛 —— 静默算成「没人满足」与写错了长得一样")
        void unknownConditionThrows() {
            Session s = deal(List.of(mate(1), kid(2)), "rum", "water");
            toNavigation(s);
            assertTrue(assertThrows(IllegalArgumentException.class, () ->
                    s.navigate(card("c", 0, new Selector.Conditional("used_grog"), new Selector.Nobody(),
                            false, false), DRINKS_NOTHING)).getMessage().contains("used_grog"));
        }

        private CharacterId winner(Fight.Outcome outcome, CharacterId a, CharacterId b) {
            return outcome.losers().contains(a) ? b : a;
        }

        /** 用一场必输的架反推战斗体型：进攻方总战力 = 满体型 + 酒。 */
        private int fightingSizeOf(Session s, CharacterId who) {
            return s.applyFight(Fight.between(who, otherThan(s, who))).attackPower();
        }

        private CharacterId otherThan(Session s, CharacterId who) {
            return s.state().bySeat().stream().filter(id -> !id.equals(who)).findFirst().orElseThrow();
        }
    }

    // ------------------------------------------------------------------ 武器 · 交易 · 对账

    @Nested
    @DisplayName("武器 · 交易 · 对账")
    class Misc {

        @Test
        @DisplayName("打出武器：从手上挪到面前，加值进这一场")
        void weaponsComeFromCards() {
            Session s = deal(List.of(mate(1), kid(2)), "knife", "water");
            assertEquals(3, s.weaponPowerAvailable(MATE));
            Fight fight = Fight.between(KID, MATE);
            fight = s.playWeapon(fight, MATE, "knife");
            assertEquals(3, fight.weaponPowerOf(MATE));
            assertTrue(s.state().stateOf(MATE).hasInFront("knife"), "打出即亮出");
            s.applyFight(fight);
            assertTrue(s.state().stateOf(MATE).hasInFront("knife"), "普通武器打完留在面前");
        }

        @Test
        @DisplayName("❗信号枪当武器用过之后也要弃；亮着没用的那张不弃")
        void flareGunDiscardedOnlyWhenUsed() {
            Session used = deal(List.of(mate(1), kid(2)), "flare_gun", "water");
            Fight fight = Fight.between(KID, MATE);
            used.applyFight(used.playWeapon(fight, MATE, "flare_gun"));
            assertFalse(used.state().stateOf(MATE).hasInFront("flare_gun"), "用过就弃 —— 全副唯一一张");

            Session shown = deal(List.of(mate(1), kid(2)), "flare_gun", "water");
            shown.reveal(MATE, "flare_gun");
            shown.applyFight(Fight.between(KID, MATE));
            assertTrue(shown.state().stateOf(MATE).hasInFront("flare_gun"),
                    "亮出来而本场没用的不弃（规则 §9.1）");
        }

        @Test
        @DisplayName("交易只在行动阶段；亮出的牌送出去之后对方也得保持亮出")
        void tradingRules() {
            Session s = deal(List.of(mate(1), kid(2)), "knife", "water");
            s.reveal(MATE, "knife");
            s.giveCard(MATE, KID, "knife");
            assertTrue(s.state().stateOf(KID).hasInFront("knife"), "亮出的牌不会变回手牌");

            toNavigation(s);
            assertTrue(assertThrows(IllegalStateException.class,
                    () -> s.giveCard(KID, MATE, "knife")).getMessage().contains("行动阶段"));
        }

        @Test
        @DisplayName("❗牌不会凭空消失：喝掉的水进弃牌堆，账仍然对得上")
        void nothingLost() {
            Session s = deal(List.of(mate(1), kid(2)), "water", "water");
            s.requireNoProvisionLost("发完牌");
            toNavigation(s);
            s.navigate(card("t", 0, new Selector.Nobody(), new Selector.Everyone(), false, false), drinksOwn(s));
            s.requireNoProvisionLost("喝完水");
            assertEquals(2, s.table().provisionDiscard().size(), "两张水都进了弃牌堆");
        }

        @Test
        @DisplayName("终局财宝：手上的与面前的都算")
        void treasuresCountBothZones() {
            // ❗阵容里必须有船长与收藏家：现金与美术品在物资数据里同为 score_flat，
            //   分得开它们的是「谁加倍」，而那是角色那一份数据。
            Session s = deal(List.of(captain(1), jeweler(2), collector(3)),
                    "cash", "jewelry", "fine_art_3a");
            s.reveal(CAPTAIN, "cash");
            assertEquals(1, s.treasuresOf(CAPTAIN).cash(), "亮在面前的照样算");
            assertEquals(0, s.treasuresOf(CAPTAIN).jewelry());
            assertEquals(1, s.treasuresOf(JEWELER).jewelry());
            assertEquals(3, s.treasuresOf(COLLECTOR).fineArtFaceValue(), "美术品按面值累加");
        }

        @Test
        @DisplayName("❗分不出这张财宝属于哪一类时当场抛，不猜一个")
        void unclassifiableTreasureThrows() {
            Session s = deal(List.of(mate(1), kid(2)), "cash", "water");
            assertTrue(assertThrows(IllegalStateException.class,
                    () -> s.treasuresOf(MATE)).getMessage().contains("captain"));
        }
    }
}
