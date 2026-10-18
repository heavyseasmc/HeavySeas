package io.github.heavyseasmc.engine.play;

import io.github.heavyseasmc.engine.data.TestProvisions;
import io.github.heavyseasmc.engine.model.Ability;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.navigation.NavigationDeck;
import io.github.heavyseasmc.engine.navigation.Selector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OverboardWindowTest {
    private static final CharacterId DONOR = CharacterId.of("mate");
    private static final CharacterId SWIMMER = CharacterId.of("kid");
    private static final NavigationCard FALL = new NavigationCard("fall", 0,
            new Selector.Only(Set.of(SWIMMER)), new Selector.Only(Set.of(SWIMMER)), false, false);

    private static Session session() {
        Session s = new Session("overboard-window", new Roster(List.of(
                new Survivor(DONOR, 1, 8, 4, "base", new Ability.None()),
                new Survivor(SWIMMER, 2, 3, 9, "base", new Ability.None()))),
                new Table(new NavigationDeck(List.of(FALL), new Random(1)),
                        TestProvisions.counting(Map.of("bait_bucket", 2, "life_preserver", 1, "water", 2)),
                        new Random(1)));
        s.dealFromPile(DONOR, "life_preserver");
        s.dealFromPile(DONOR, "bait_bucket");
        s.dealFromPile(DONOR, "bait_bucket");
        s.advancePhase();
        s.advancePhase();
        return s;
    }

    @Test
    void injuryAndThirstWaitForTheOverboardWindow() {
        Session s = session();
        s.beginNavigation(FALL);
        assertEquals(List.of(SWIMMER), s.overboardPending().orElseThrow().swimmers());
        assertEquals(0, s.state().stateOf(SWIMMER).damage());
        assertTrue(s.thirstPending().isEmpty());
        assertThrows(IllegalStateException.class, s::advancePhase);
        s.finishOverboard();
        assertEquals(1, s.state().stateOf(SWIMMER).damage());
        assertEquals(SWIMMER, s.thirstPending().orElseThrow().who());
        assertTrue(s.overboardPending().isEmpty());
        assertThrows(IllegalStateException.class, s::finishOverboard);
    }

    @Test
    void donatedRingIsVisibleBeforeDamageAndStaysWithReceiver() {
        Session s = session();
        s.beginNavigation(FALL);
        int token = s.overboardPending().orElseThrow().token();
        s.playOverboardCard(DONOR, SWIMMER, "life_preserver", token);
        assertFalse(s.state().stateOf(DONOR).hasInHand("life_preserver"));
        assertTrue(s.state().stateOf(SWIMMER).hasInFront("life_preserver"));
        s.finishOverboard();
        assertEquals(0, s.state().stateOf(SWIMMER).damage());
        assertTrue(s.state().stateOf(SWIMMER).hasInFront("life_preserver"));
        s.requireNoProvisionLost("rescue");
    }

    @Test
    void temporaryBaitFromBoatPenetratesRingButDoesNotStack() {
        Session s = session();
        s.beginNavigation(FALL);
        int token = s.overboardPending().orElseThrow().token();
        s.playOverboardCard(DONOR, SWIMMER, "life_preserver", token);
        s.playOverboardCard(DONOR, SWIMMER, "bait_bucket", token);
        s.playOverboardCard(DONOR, SWIMMER, "bait_bucket", token);
        s.finishOverboard();
        assertEquals(1, s.state().stateOf(SWIMMER).damage());
        assertEquals(0, s.state().stateOf(DONOR).damage());
        assertEquals(List.of("bait_bucket", "bait_bucket"), s.table().provisionDiscard());
        s.requireNoProvisionLost("bait");
    }

    @Test
    void offlineReceiverAndStaleWindowCannotConsumeCards() {
        Session s = session();
        s.setOffline(SWIMMER, true);
        s.beginNavigation(FALL);
        int token = s.overboardPending().orElseThrow().token();
        assertThrows(IllegalArgumentException.class,
                () -> s.playOverboardCard(DONOR, SWIMMER, "life_preserver", token));
        assertThrows(IllegalStateException.class,
                () -> s.playOverboardCard(DONOR, SWIMMER, "bait_bucket", token + 1));
        assertTrue(s.state().stateOf(DONOR).hasInHand("life_preserver"));
        assertEquals(2, s.state().stateOf(DONOR).countInHand("bait_bucket"));
        s.finishOverboard();
        assertThrows(IllegalStateException.class,
                () -> s.playOverboardCard(DONOR, SWIMMER, "bait_bucket", token));
    }

    @Test
    void publicCardTransferMustUseTheExplicitSourceEvenWhenBothZonesHoldIt() {
        Session s = session();
        s.advancePhase();
        s.advancePhase();
        s.reveal(DONOR, "bait_bucket");
        s.giveCard(DONOR, SWIMMER, "bait_bucket", true);
        assertEquals(1, s.state().stateOf(DONOR).countInHand("bait_bucket"));
        assertTrue(s.state().stateOf(SWIMMER).hasInFront("bait_bucket"));
        assertFalse(s.state().stateOf(SWIMMER).hasInHand("bait_bucket"));
        s.requireNoProvisionLost("explicit source");
    }
}
