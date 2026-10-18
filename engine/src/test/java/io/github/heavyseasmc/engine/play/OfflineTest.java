package io.github.heavyseasmc.engine.play;

import io.github.heavyseasmc.engine.data.TestProvisions;
import io.github.heavyseasmc.engine.model.Ability;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.navigation.NavigationDeck;
import io.github.heavyseasmc.engine.navigation.Selector;
import io.github.heavyseasmc.engine.state.Condition;
import io.github.heavyseasmc.engine.state.Fight;
import io.github.heavyseasmc.engine.state.GameState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OfflineTest {
    private static final CharacterId FIRST = CharacterId.of("mate");
    private static final CharacterId SECOND = CharacterId.of("captain");
    private static final CharacterId LAST = CharacterId.of("kid");
    private static final NavigationCard CALM = new NavigationCard("calm", 0,
            new Selector.Nobody(), new Selector.Nobody(), false, false);

    private static Roster roster() {
        return new Roster(List.of(
                new Survivor(FIRST, 1, 8, 4, "base", new Ability.None()),
                new Survivor(SECOND, 2, 7, 5, "base", new Ability.None()),
                new Survivor(LAST, 3, 3, 9, "base", new Ability.None())));
    }

    private static Session session() {
        return new Session("offline-test", roster(), new Table(
                new NavigationDeck(List.of(CALM, new NavigationCard("calm2", 0,
                        new Selector.Nobody(), new Selector.Nobody(), false, false)), new Random(1)),
                TestProvisions.counting(Map.of("water", 8, "medical_kit", 1, "oar", 1)),
                new Random(1)));
    }

    @Test
    void offlineIsIndependentOfWoundsAndSurvivesEveryStateCopy() {
        GameState original = GameState.start(roster());
        GameState g = original.withState(LAST, original.stateOf(LAST).hurt(2)).withOffline(LAST, true);
        assertFalse(original.isOffline(LAST));
        assertTrue(g.isOffline(LAST));
        assertEquals(Condition.CONSCIOUS, g.conditionOf(LAST));
        assertFalse(g.canAct(LAST));
        assertEquals(SECOND, g.helmsman().orElseThrow());
        g = g.withGulls(1).withState(LAST, g.stateOf(LAST).heal(1)).withRemoved(SECOND);
        g = g.advancePhase().advancePhase().advancePhase();
        assertTrue(g.isOffline(LAST));
        assertEquals(1, g.stateOf(LAST).damage());
        assertTrue(g.withOffline(LAST, false).canAct(LAST));
        g = g.withState(LAST, g.stateOf(LAST).hurt(2)).withOffline(LAST, false);
        assertFalse(g.canAct(LAST));
        assertEquals(Condition.UNCONSCIOUS, g.conditionOf(LAST));
    }

    @Test
    void allOfflineStillAliveButNobodyCanActOrHelm() {
        GameState g = GameState.start(roster());
        for (CharacterId id : g.bySeat()) {
            g = g.withOffline(id, true);
        }
        assertTrue(g.nextActor().isEmpty());
        assertTrue(g.helmsman().isEmpty());
        assertTrue(g.consciousBySeat().isEmpty());
        assertFalse(g.isOver());
    }

    @Test
    void offlineSeatsDoNotDrawProvisions() {
        Session s = session();
        s.setOffline(FIRST, true);
        assertEquals(2, s.beginProvision().size());
        assertEquals(SECOND, s.provisionHolder().orElseThrow());
        s.requireNoProvisionLost("offline draw");
    }

    /**
     * 持箱人中途掉线：箱子照旧在他手上，由补给箱的超时替他留一张（模组那一侧，决策 ⑧「局面不能卡住」）；
     * 牌堆只减不增（规则基线 §8.1「抽完即止，不洗回」）。
     *
     * <p>ADR-0051（用户 2026-10-01 拍板，O36 B1）取代协作者 2adbbe4 那一版：跳过他、传到链尾把余牌放回牌堆底 ——
     * 前面看过牌的人于是知道牌堆底是什么，牌堆本已空时还会让下一回合的物资阶段凭空复活。
     * 开局时就掉线的人不进传递链、不拿新牌（{@link #offlineSeatsDoNotDrawProvisions}），那一条不变。
     */
    @Test
    void disconnectDuringProvisionKeepsTheCrateAndPileOnlyShrinks() {
        Session s = session();
        s.beginProvision();
        int left = s.table().provisionsLeft();
        s.setOffline(FIRST, true);
        assertEquals(FIRST, s.provisionHolder().orElseThrow(), "掉线不换持箱人：等超时替他选");
        s.provisionKeep(s.provisionOffer().getFirst());       // 模组那一侧的超时替他留第一张
        assertEquals(1, s.state().stateOf(FIRST).hand().size());
        assertEquals(SECOND, s.provisionHolder().orElseThrow());
        s.provisionKeep(s.provisionOffer().getFirst());
        s.setOffline(LAST, true);
        assertEquals(LAST, s.provisionHolder().orElseThrow(), "传到掉线的那一位也照旧停在他手上");
        s.provisionKeep(s.provisionOffer().getFirst());
        assertFalse(s.provisionInProgress());
        assertEquals(left, s.table().provisionsLeft(), "牌堆只减不增：一张都没放回去");
        s.requireNoProvisionLost("offline holder");
    }

    @Test
    void offlineTargetCannotRefuseAndReconnectionDoesNotHeal() {
        Session s = session();
        s.advancePhase();
        s.applyFight(Fight.between(FIRST, LAST));
        s.setOffline(LAST, true);
        s.declare(FIRST, Contest.Kind.SWAP, LAST);
        assertTrue(s.contest().isEmpty());
        assertEquals(1, s.state().stateOf(LAST).damage());
        s.setOffline(LAST, false);
        assertEquals(1, s.state().stateOf(LAST).damage());
    }

    @Test
    void disconnectDuringConsentPreventsRefusal() {
        Session s = session();
        s.advancePhase();
        s.declare(FIRST, Contest.Kind.SWAP, LAST);
        s.setOffline(LAST, true);
        assertThrows(IllegalArgumentException.class, () -> s.consent(true));
        s.consent(false);
        assertTrue(s.contest().isEmpty());
    }

    @Test
    void disconnectDuringRowReturnsCardsAndConsumesOnlyThatAction() {
        Session s = session();
        s.advancePhase();
        s.beginRow(FIRST);
        s.setOffline(FIRST, true);
        assertTrue(s.rower().isEmpty());
        assertTrue(s.table().rowerHand().isEmpty());
        assertTrue(s.table().rowStack().isEmpty());
        assertTrue(s.state().stateOf(FIRST).actedThisTurn());
        assertEquals(SECOND, s.nextActor().orElseThrow());
        s.setOffline(FIRST, false);
        assertEquals(SECOND, s.nextActor().orElseThrow());
        s.table().requireNoCardLost("test", "disconnect");
    }

    @Test
    void invalidWaterBatchDoesNotConsumeEarlierDonors() {
        Session s = session();
        s.dealFromPile(FIRST, "water");
        s.advancePhase();
        s.applyFight(Fight.between(FIRST, LAST));
        s.advancePhase();
        s.beginNavigate(new NavigationCard("thirst", 0, new Selector.Nobody(),
                new Selector.Only(Set.of(LAST)), false, true));
        s.decideThirst(List.of());
        assertEquals(LAST, s.thirstPending().orElseThrow().who());
        s.setOffline(SECOND, true);
        assertThrows(IllegalArgumentException.class, () -> s.decideThirst(List.of(FIRST, SECOND)));
        assertEquals(1, s.watersOf(FIRST));
        assertTrue(s.table().provisionDiscard().isEmpty());
    }

    @Test
    void offlineCannotRevealGiveDrinkOrCommitNewWeapon() {
        Session s = session();
        s.dealFromPile(FIRST, "oar");
        s.advancePhase();
        s.declare(FIRST, Contest.Kind.SWAP, SECOND);
        s.consent(true);
        s.closeStances();
        s.setOffline(FIRST, true);
        assertThrows(IllegalArgumentException.class, () -> s.commitWeapon(FIRST, "oar"));
        assertThrows(IllegalArgumentException.class, () -> s.reveal(FIRST, "oar"));
        s.resolveContest();
        assertThrows(IllegalArgumentException.class, () -> s.giveCard(FIRST, LAST, "oar"));
    }

    @Test
    void offlineStillThirstsCanBeDonatedWaterAndHealedWithoutWaking() {
        Session s = session();
        s.dealFromPile(FIRST, "water");
        s.dealFromPile(FIRST, "medical_kit");
        s.dealFromPile(LAST, "water");
        s.advancePhase();
        s.applyFight(Fight.between(FIRST, LAST));
        s.setOffline(LAST, true);
        s.useMedicalKit(FIRST, LAST, "medical_kit");
        assertEquals(0, s.state().stateOf(LAST).damage());
        assertFalse(s.state().canAct(LAST));
        s.advancePhase();
        s.beginNavigate(new NavigationCard("thirst", 0, new Selector.Nobody(),
                new Selector.Only(Set.of(LAST)), false, false));
        assertEquals(LAST, s.thirstPending().orElseThrow().who());
        assertThrows(IllegalArgumentException.class, () -> s.decideThirst(List.of(LAST)));
        s.decideThirst(List.of(FIRST));
        assertEquals(0, s.state().stateOf(LAST).damage());
        assertEquals(1, s.watersOf(LAST));
        s.requireNoProvisionLost("offline rescue");
    }
}
