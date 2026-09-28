package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.engine.model.*;
import io.github.heavyseasmc.engine.navigation.*;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.play.Table;
import io.github.heavyseasmc.mod.net.CardActionC2S;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class CardActionsTest {
    private static final CharacterId OWNER = CharacterId.of("mate");
    private static final CharacterId OTHER = CharacterId.of("kid");

    private static Session session() {
        Session session = new Session("card-action", new Roster(List.of(
                new Survivor(OWNER, 1, 8, 4, "base", new Ability.None()),
                new Survivor(OTHER, 2, 3, 9, "base", new Ability.None()))),
                new Table(new NavigationDeck(List.of(new NavigationCard("calm", 0,
                        new Selector.Nobody(), new Selector.Nobody(), false, false)), new Random(1)),
                        new Provisions(List.of(new Provision("cash", Provision.Category.TREASURE, 1,
                                new ProvisionEffect.ScoreFlat(1, "")))), new Random(1)));
        session.dealFromPile(OWNER, "cash");
        session.advancePhase();
        return session;
    }

    @Test
    void giftCannotSpendAnothersCardOrReplayTheTransfer() {
        Session session = session();
        var gift = CardActionC2S.of(CardActionC2S.Kind.GIVE_HAND, "cash", OTHER.value(), 0);
        assertThrows(IllegalArgumentException.class, () -> CardActions.apply(session, OTHER,
                CardActionC2S.of(CardActionC2S.Kind.GIVE_HAND, "cash", OWNER.value(), 0)));
        CardActions.apply(session, OWNER, gift);
        assertTrue(session.state().stateOf(OTHER).hasInHand("cash"));
        assertFalse(session.state().stateOf(OWNER).actedThisTurn());
        assertThrows(IllegalArgumentException.class, () -> CardActions.apply(session, OWNER, gift));
        session.requireNoProvisionLost("gift replay");
    }

    @Test
    void badSourceUnknownCodesAndOfflineActorsCannotMutateCards() {
        Session session = session();
        assertThrows(IllegalArgumentException.class, () -> CardActions.apply(session, OWNER,
                CardActionC2S.of(CardActionC2S.Kind.GIVE_FRONT, "cash", OTHER.value(), 0)));
        assertThrows(IllegalArgumentException.class,
                () -> CardActions.apply(session, OWNER, new CardActionC2S(99, "cash", OTHER.value(), 0)));
        session.setOffline(OWNER, true);
        assertThrows(IllegalStateException.class, () -> CardActions.apply(session, OWNER,
                CardActionC2S.of(CardActionC2S.Kind.REVEAL, "cash", "", 0)));
        assertTrue(session.state().stateOf(OWNER).hasInHand("cash"));
        assertTrue(session.state().stateOf(OTHER).hand().isEmpty());
        session.requireNoProvisionLost("rejected card actions");
    }
}
