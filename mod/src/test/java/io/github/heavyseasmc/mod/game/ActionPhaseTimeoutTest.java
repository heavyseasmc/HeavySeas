package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.model.Ability;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Provision;
import io.github.heavyseasmc.engine.model.ProvisionEffect;
import io.github.heavyseasmc.engine.model.Provisions;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.navigation.NavigationDeck;
import io.github.heavyseasmc.engine.navigation.Selector;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.play.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionPhaseTimeoutTest {

    private static final CharacterId ROWER = CharacterId.of("jeweler");

    @Test
    @DisplayName("行动选择比划船留得久，两个窗口都有明确的掉线保底")
    void actionWindowIsLongerThanRowWindow() {
        assertEquals(60_000L, ActionPhase.ACTION_MILLIS);
        assertEquals(20_000L, ActionPhase.ROW_MILLIS);
        assertTrue(ActionPhase.ACTION_MILLIS > ActionPhase.ROW_MILLIS);
        assertFalse(ActionPhase.windowExpired(0L, 10_000L), "0 表示没有窗口，不能被当成已经超时");
        assertFalse(ActionPhase.windowExpired(10_001L, 10_000L));
        assertTrue(ActionPhase.windowExpired(10_000L, 10_000L));
    }

    @Test
    @DisplayName("划船超时只把尚未决定的牌塞回牌堆底，已经留下的选择不反悔")
    void rowTimeoutReturnsOnlyUndecidedCards() {
        Session session = session();
        session.beginRow(ROWER);
        session.decideRow(0, true);

        assertEquals(1, ActionPhase.returnUndecided(session));

        assertTrue(session.rower().isEmpty());
        assertEquals(1, session.table().rowStack().size(), "先前留下的牌仍在划船堆");
        assertTrue(session.table().rowerHand().isEmpty(), "超时后不能还有牌卡在划船者手里");
    }

    private static Session session() {
        Roster roster = new Roster(List.of(
                new Survivor(ROWER, 1, 8, 8, "base", new Ability.None()),
                new Survivor(CharacterId.of("mate"), 4, 6, 4, "base", new Ability.None()),
                new Survivor(CharacterId.of("kid"), 8, 3, 9, "base", new Ability.None())));
        List<NavigationCard> cards = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            cards.add(new NavigationCard("timeout_" + i, 0,
                    new Selector.Nobody(), new Selector.Nobody(), false, false));
        }
        Provisions provisions = new Provisions(List.of(new Provision("score", Provision.Category.TREASURE, 1,
                new ProvisionEffect.ScoreFlat(1, ""))));
        Session session = new Session("timeout-test", roster,
                new Table(new NavigationDeck(cards, new Random(3)), provisions));
        session.advancePhase();
        return session;
    }
}
