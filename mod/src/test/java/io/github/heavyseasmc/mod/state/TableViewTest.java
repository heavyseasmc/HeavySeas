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
                List.of(new TableView.Play("life_preserver", "kid"))));
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
