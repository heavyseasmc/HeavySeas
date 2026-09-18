package io.github.heavyseasmc.engine.play;

import io.github.heavyseasmc.engine.model.Ability;
import io.github.heavyseasmc.engine.data.TestProvisions;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.navigation.NavigationDeck;
import io.github.heavyseasmc.engine.navigation.Selector;
import io.github.heavyseasmc.engine.state.Fight;
import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.engine.thirst.ThirstSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 划船：抽 2 张看过，选择 1 张留进划船堆，其余塞回牌堆底部（规则基线 §8.2）。
 *
 * <p>模组里这件事要等人看完再选，所以规则拆成了「先抽 / 再选」两步，中间两张牌待在划船者手上。
 * 下面盯的是这段「手上」的时间：牌算不算得清、别的动作能不能插进来、两步合起来是不是还等于原来那一步。
 */
class RowingTest {

    private static final CharacterId A = CharacterId.of("jeweler");
    private static final CharacterId B = CharacterId.of("mate");
    private static final CharacterId C = CharacterId.of("kid");

    private static Roster roster() {
        return new Roster(List.of(
                new Survivor(A, 1, 8, 8, "base", new Ability.None()),
                new Survivor(B, 4, 6, 4, "base", new Ability.None()),
                new Survivor(C, 8, 3, 9, "base", new Ability.None())));
    }

    /** {@code n} 张内容无关紧要的牌，id 各不相同 —— 下面只关心牌去了哪。 */
    private static List<NavigationCard> cards(int n) {
        List<NavigationCard> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new NavigationCard("syn_" + i, 0, new Selector.Nobody(), new Selector.Nobody(), false, false));
        }
        return out;
    }

    /** 洗牌用定种子：两局用同一副牌、同一个顺序，才比得出「两种走法结果一样」。 */
    private static Session session(int deckSize) {
        return new Session("test", roster(), new Table(new NavigationDeck(cards(deckSize), new Random(3)), TestProvisions.minimal()));
    }

    /** 开局在物资阶段；划船是行动阶段的事。 */
    private static Session inAction(int deckSize) {
        Session s = session(deckSize);
        s.advancePhase();
        return s;
    }

    /** 把牌堆从顶抽空，按抽出顺序给 id。NavigationDeck 只能从顶抽 —— 测完这一局就不要了。 */
    private static List<String> drainPile(Session s) {
        List<String> ids = new ArrayList<>();
        while (!s.table().pile().isEmpty()) {
            ids.add(s.table().pile().draw().id());
        }
        return ids;
    }

    @Test
    @DisplayName("❗先抽再选：牌堆只剩一张时只看到一张，取消后原牌回到底部")
    void drawsBothBeforeDeciding() {
        Session s = inAction(1);

        List<NavigationCard> seen = s.beginRow(A);

        assertEquals(1, seen.size(),
                "牌堆只有 1 张，就只看得到 1 张；看到 " + seen + " 说明同一张被重复抽了出来");
        assertEquals(1, s.cancelRow());
        assertEquals(1, s.table().pile().size());
    }

    @Test
    @DisplayName("❗抽出来还没选的两张算在账上：牌堆少 2 张、划船堆还空着，对账照样过")
    void drawnCardsAreAccountedWhileUndecided() {
        Session s = inAction(5);

        List<NavigationCard> drawn = s.beginRow(A);

        assertEquals(2, drawn.size());
        assertEquals(3, s.table().pile().size(), "两张离开了牌堆");
        assertTrue(s.table().rowStackIsEmpty(), "还没选，就还没进划船堆");
        assertEquals(drawn, s.table().rowerHand(), "它们在划船者手上");
        assertEquals(List.of(Session.RowFate.UNDECIDED, Session.RowFate.UNDECIDED),
                s.rowing().stream().map(Session.RowCard::fate).toList());
        assertDoesNotThrow(() -> s.table().requireNoCardLost("test", "抽完还没选"));
    }

    @Test
    @DisplayName("选中的一张进划船堆，其余牌自动回到牌堆最底下")
    void eachCardGoesWhereItWasSent() {
        Session s = inAction(5);
        List<NavigationCard> drawn = s.beginRow(A);

        assertEquals(1, s.chooseRow(1));

        assertEquals(List.of(drawn.get(1)), s.table().rowStack(), "只允许选中的一张进入划船堆");
        assertTrue(s.table().rowerHand().isEmpty());
        List<String> pile = drainPile(s);
        assertEquals(drawn.get(0).id(), pile.get(pile.size() - 1), "未选中的牌在牌堆最底下");
    }

    @Test
    @DisplayName("❗选择落定才算划完：选择前不许记为行动过，选择后立即领取划船标记")
    void rowFinishesOnlyWhenEveryCardIsDecided() {
        Session s = inAction(5);
        s.beginRow(A);

        assertEquals(Optional.of(A), s.rower(), "还没选择");
        assertFalse(s.state().stateOf(A).thirst().has(ThirstSource.ROWED), "标记在划完之后才领");
        IllegalStateException early = assertThrows(IllegalStateException.class, () -> s.markActed(A),
                "牌还在手上就记为行动过，下一个人就轮上来了");
        assertTrue(early.getMessage().contains("还没选完"), early.getMessage());

        s.chooseRow(1);

        assertEquals(Optional.empty(), s.rower());
        assertTrue(s.rowing().isEmpty());
        assertTrue(s.state().stateOf(A).thirst().has(ThirstSource.ROWED));
        assertDoesNotThrow(() -> s.markActed(A));
    }

    @Test
    @DisplayName("❗同一次划船只能选择一次：第二次直接抛，划船堆不多出一张")
    void aCardIsDecidedOnlyOnce() {
        Session s = inAction(5);
        s.beginRow(A);
        s.chooseRow(0);

        IllegalStateException twice = assertThrows(IllegalStateException.class, () -> s.chooseRow(0));

        assertTrue(twice.getMessage().contains("没有人在划船"), twice.getMessage());
        assertEquals(1, s.table().rowStack().size());
        assertDoesNotThrow(() -> s.table().requireNoCardLost("test", "定两次之后"));
    }

    @Test
    @DisplayName("没人在划船、或者没有这一张时选择，直接抛 —— 一张牌都不动")
    void decidingWithoutARowOrOutOfRangeThrows() {
        Session idle = inAction(5);
        assertThrows(IllegalStateException.class, () -> idle.chooseRow(0), "没人在划船");

        Session s = inAction(5);
        s.beginRow(A);
        assertThrows(IllegalArgumentException.class, () -> s.chooseRow(2), "只抽了 2 张");
        assertThrows(IllegalArgumentException.class, () -> s.chooseRow(-1));

        assertEquals(3, s.table().pile().size());
        assertTrue(s.table().rowStackIsEmpty());
        assertEquals(2, s.table().rowerHand().size());
    }

    @Test
    @DisplayName("❗还没选中一张时，推进阶段、再划一次、换座位、打架一律抛 —— 否则手上的牌就被带过了那一步")
    void nothingElseHappensWhileCardsAreInHand() {
        Session s = inAction(5);
        s.beginRow(A);

        IllegalStateException advance = assertThrows(IllegalStateException.class, s::advancePhase, "推进阶段");
        IllegalStateException again = assertThrows(IllegalStateException.class, () -> s.beginRow(B), "再划一次");
        IllegalStateException swap = assertThrows(IllegalStateException.class, () -> s.swapSeats(B, C), "换座位");
        IllegalStateException fight = assertThrows(IllegalStateException.class,
                () -> s.applyFight(Fight.between(B, C)), "打架");

        for (IllegalStateException e : List.of(advance, again, swap, fight)) {
            assertTrue(e.getMessage().contains("还没选完"), e.getMessage());
        }
        assertEquals(Phase.ACTION, s.state().phase(), "没推进过去");
        assertEquals(2, s.table().rowerHand().size(), "两张还在手上");
        assertEquals(Optional.of(A), s.rower());
    }

    @Test
    @DisplayName("划船只在行动阶段：物资阶段划船直接抛，一张牌都不动")
    void rowingOnlyInTheActionPhase() {
        Session s = session(5);                    // 开局在物资阶段

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> s.beginRow(A));

        assertTrue(e.getMessage().contains("行动阶段"), e.getMessage());
        assertEquals(5, s.table().pile().size());
        assertEquals(Optional.empty(), s.rower());
    }

    @Test
    @DisplayName("牌堆抽空时有几张抽几张；一张都没抽到，这次划船当场结束，照样领标记")
    void anEmptyPileFinishesTheRowAtOnce() {
        Session s = inAction(2);
        s.row(A, (cards, state, rower) -> 0);
        s.markActed(A);
        s.row(B, (cards, state, rower) -> 0);
        s.markActed(B);
        assertTrue(s.table().pile().isEmpty(), "先确认牌堆真的空了 —— 否则这条什么也没测");

        assertEquals(List.of(), s.beginRow(C));

        assertEquals(Optional.empty(), s.rower(), "没有牌可定，不能停在「正在划船」—— 那样局面就卡住了");
        assertTrue(s.state().stateOf(C).thirst().has(ThirstSource.ROWED), "划了船就领标记，抽没抽到牌不改变这件事");
    }

    @Test
    @DisplayName("❗row() 就是先抽再选：同一副牌、同一个选择，桌面、牌堆顺序与标记一模一样")
    void rowIsTheTwoStepsInDrawOrder() {
        int[] asked = {0};
        Session composed = inAction(6);
        composed.row(A, (cards, state, rower) -> {
            asked[0]++;
            return 1;
        });

        Session stepped = inAction(6);
        stepped.beginRow(A);
        stepped.chooseRow(1);

        assertEquals(1, asked[0], "整组只问一次 —— 模拟器的可复现性靠这个次数");
        assertEquals(stepped.table().rowStack(), composed.table().rowStack());
        assertEquals(stepped.state().stateOf(A), composed.state().stateOf(A));
        assertEquals(drainPile(stepped), drainPile(composed), "塞回去的那张要排在同一个位置");
    }

    @Test
    @DisplayName("❗航海牌只在航海阶段结算，而且每回合只执行一张 —— 超时与指令前后脚到时，第二下不能再翻一张")
    void oneNavigationCardPerTurn() {
        Session s = inAction(5);
        IllegalStateException wrongPhase = assertThrows(IllegalStateException.class,
                () -> s.takeCardForNavigation(null), "行动阶段不结算航海牌");
        assertTrue(wrongPhase.getMessage().contains("航海阶段"), wrongPhase.getMessage());

        s.advancePhase();                          // 行动 → 航海
        s.prepareRowStack();                       // 结算之前先备划船堆（没人划船，这一步什么也不抽）
        NavigationCard first = s.takeCardForNavigation(null);
        assertEquals(Optional.of(first), s.navigatedThisTurn(), "执行的那张是公开的");
        IllegalStateException twice = assertThrows(IllegalStateException.class, () -> s.takeCardForNavigation(null));
        assertTrue(twice.getMessage().contains("每回合只执行一张"), twice.getMessage());

        s.advancePhase();                          // 航海 → 下一回合的物资
        assertEquals(Optional.empty(), s.navigatedThisTurn(), "下一回合还没结算");
        s.advancePhase();
        s.advancePhase();                          // 又到航海
        s.prepareRowStack();
        assertDoesNotThrow(() -> s.takeCardForNavigation(null));
    }
}
