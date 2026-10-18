package io.github.heavyseasmc.mod.state;

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
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class TableViewTest {
    @Test
    void publicProjectionContainsFrontCardsButNeverPrivateHands() {
        CharacterId first = CharacterId.of("mate");
        CharacterId last = CharacterId.of("kid");
        Session session = new Session("table-view", new Roster(List.of(
                new Survivor(first, 1, 8, 4, "base", new Ability.None()),
                new Survivor(last, 2, 3, 9, "base", new Ability.None()))),
                new Table(new NavigationDeck(List.of(new NavigationCard("calm", 0,
                        new Selector.Nobody(), new Selector.Nobody(), false, false)), new Random(1)),
                        new Provisions(List.of(
                                new Provision("public_card", Provision.Category.TREASURE, 1,
                                        new ProvisionEffect.ScoreFlat(1, "")),
                                new Provision("private_card", Provision.Category.TREASURE, 1,
                                        new ProvisionEffect.ScoreFlat(1, "")))), new Random(1)));
        session.dealFromPile(first, "public_card");
        session.reveal(first, "public_card");
        session.dealFromPile(first, "private_card");
        session.setOffline(last, true);
        TableView spectator = TableView.of(session, Optional.empty(), 0);
        assertEquals(List.of("public_card"), spectator.seats().getFirst().front());
        assertTrue(spectator.seats().getLast().offline());
        assertFalse(spectator.toString().contains("private_card"));
        assertTrue(spectator.plays().isEmpty());
        roundTrip(spectator);
        roundTrip(TableView.EMPTY);
    }

    @Test
    void windowAndPrivateChoicesRoundTripWithoutShiftingFollowingFields() {
        roundTrip(new TableView(List.of(), 14, 12000, 20_000, List.of("kid"),
                List.of(new TableView.Play("life_preserver", "kid"),
                        new TableView.Play("surge_flask", "kid", TableView.Scope.ALL_SWIMMERS, 3))));
    }

    @Test
    void realOverboardChoicesDescribeEffectScopeAndDamageWithoutChangingEngineTargets() {
        Session session = overboardSession();
        CharacterId owner = CharacterId.of("mate");
        TableView view = TableView.of(session, Optional.of(owner), 12000, 20_000, id -> "");

        assertTrue(view.overboardOpen());
        assertEquals(List.of("mate", "kid"), view.swimmers());
        assertEquals(List.of(
                new TableView.Play("life_preserver", "mate"),
                new TableView.Play("life_preserver", "kid"),
                new TableView.Play("bait_bucket", "mate", TableView.Scope.ALL_SWIMMERS, 1),
                new TableView.Play("surge_flask", "mate", TableView.Scope.ALL_SWIMMERS, 3)), view.plays());
        assertEquals(session.overboardPlays(owner).stream()
                        .map(p -> p.card() + ":" + p.target().value()).toList(),
                view.plays().stream().map(p -> p.card() + ":" + p.target()).toList(),
                "The description must not replace the engine target with a synthetic group target");
        roundTrip(view);
    }

    @Test
    void interventionMetadataStaysPrivateAndDisappearsWhenTheWindowCloses() {
        Session session = overboardSession();
        for (Optional<CharacterId> recipient : List.of(Optional.<CharacterId>empty(), Optional.of(CharacterId.of("kid")))) {
            TableView view = TableView.of(session, recipient, 12000, 20_000, id -> "");
            assertEquals(List.of("mate", "kid"), view.swimmers(), "The pending swimmers remain public");
            assertTrue(view.plays().isEmpty());
            assertFalse(view.toString().contains("life_preserver"));
            assertFalse(view.toString().contains("bait_bucket"));
            assertFalse(view.toString().contains("surge_flask"));
            roundTrip(view);
        }
        TableView closed = TableView.of(session, Optional.of(CharacterId.of("mate")), 0);
        assertFalse(closed.overboardOpen());
        assertTrue(closed.plays().isEmpty());
        roundTrip(closed);
    }

    @Test
    void damagePreviewMatchesTheBatchAfterRepeatedAndStackingInterventions() {
        Session session = overboardSession();
        CharacterId owner = CharacterId.of("mate");
        session.dealFromPile(owner, "bait_bucket");
        session.dealFromPile(owner, "bait_bucket");
        session.dealFromPile(owner, "stacking_flask");

        playAndCheckDamage(session, owner, "bait_bucket", 1);
        playAndCheckDamage(session, owner, "bait_bucket", 1);
        playAndCheckDamage(session, owner, "surge_flask", 3);
        playAndCheckDamage(session, owner, "bait_bucket", 3);
        playAndCheckDamage(session, owner, "stacking_flask", 5);
    }

    @Test
    void privateChoiceIncludesFaceUpDamageAndMatchesActualWaterResolution() {
        Session session = overboardSession();
        CharacterId owner = CharacterId.of("mate");
        session.reveal(owner, "surge_flask");
        TableView before = TableView.of(session, Optional.empty(), 12000);
        TableView.Play play = TableView.of(session, Optional.of(owner), 12000).plays().stream()
                .filter(p -> p.card().equals("bait_bucket")).findFirst().orElseThrow();

        assertEquals(3, play.extraDamage(), "A stronger face-up card still affects this batch");
        assertPreviewMatchesResolution(session, owner, play);
        assertEquals(before, TableView.of(session, Optional.empty(), 12000),
                "Generating a private choice must not change the public projection");
        assertFalse(before.toString().contains("bait_bucket"), "The unplayed hand card is still private");
    }

    private static void playAndCheckDamage(Session session, CharacterId owner, String card, int expected) {
        TableView view = TableView.of(session, Optional.of(owner), 12000);
        TableView.Play play = view.plays().stream().filter(p -> p.card().equals(card)).findFirst().orElseThrow();
        assertEquals(TableView.Scope.ALL_SWIMMERS, play.scope());
        assertEquals(expected, play.extraDamage(), "The preview is the resulting total, not the increase");
        roundTrip(view);
        assertPreviewMatchesResolution(session, owner, play);
        session.playOverboardCard(owner, CharacterId.of(play.target()), card, view.token());
        assertEquals(expected, session.overboardBait(), "The preview must agree with the real engine result");
    }

    private static void assertPreviewMatchesResolution(Session original, CharacterId owner, TableView.Play play) {
        Session resolved = original.copy();
        Session.OverboardPrompt prompt = resolved.overboardPending().orElseThrow();
        resolved.playOverboardCard(owner, CharacterId.of(play.target()), play.card(), prompt.token());
        resolved.finishOverboard();
        for (CharacterId swimmer : prompt.swimmers()) {
            assertEquals(1 + play.extraDamage(),
                    resolved.state().stateOf(swimmer).damage() - original.state().stateOf(swimmer).damage(),
                    "The displayed extra damage must match the actual damage beyond the ordinary fall");
            assertFalse(resolved.state().isRemoved(swimmer), "The high-health fixture must survive the comparison");
        }
    }

    private static Session overboardSession() {
        CharacterId first = CharacterId.of("mate");
        CharacterId last = CharacterId.of("kid");
        NavigationCard navigation = new NavigationCard("swell", 0, new Selector.Everyone(),
                new Selector.Nobody(), false, false);
        Session session = new Session("overboard-view", new Roster(List.of(
                new Survivor(first, 1, 20, 4, "base", new Ability.None()),
                new Survivor(last, 2, 20, 9, "base", new Ability.None()))),
                new Table(new NavigationDeck(List.of(navigation), new Random(1)),
                        new Provisions(List.of(
                                new Provision("life_preserver", Provision.Category.EQUIPMENT, 1,
                                        new ProvisionEffect.PreventOverboardDamage(true, true, true, true, false)),
                                new Provision("bait_bucket", Provision.Category.CONSUMABLE, 3,
                                        new ProvisionEffect.DamageInWater(1, false, true, List.of("life_preserver"))),
                                new Provision("surge_flask", Provision.Category.CONSUMABLE, 1,
                                        new ProvisionEffect.DamageInWater(3, false, true, List.of("life_preserver"))),
                                new Provision("stacking_flask", Provision.Category.CONSUMABLE, 1,
                                        new ProvisionEffect.DamageInWater(2, true, true, List.of("life_preserver"))))), new Random(1)));
        session.dealFromPile(first, "life_preserver");
        session.dealFromPile(first, "bait_bucket");
        session.dealFromPile(first, "surge_flask");
        session.advancePhase();
        session.markActed(first);
        session.markActed(last);
        session.advancePhase();
        session.beginNavigation(navigation);
        assertEquals(List.of(first, last), session.overboardPending().orElseThrow().swimmers());
        return session;
    }

    private static void roundTrip(TableView original) {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        try {
            original.write(buf);
            buf.writeInt(0x53454153);
            assertEquals(original, TableView.read(buf));
            assertEquals(0x53454153, buf.readInt());
            assertFalse(buf.isReadable());
        } finally {
            buf.release();
        }
    }
}
