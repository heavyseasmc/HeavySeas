package io.github.heavyseasmc.mod.state;

import io.github.heavyseasmc.engine.model.Ability;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.model.Provisions;
import io.github.heavyseasmc.engine.model.Provision;
import io.github.heavyseasmc.engine.model.ProvisionEffect;
import io.github.heavyseasmc.engine.model.Roster;
import io.github.heavyseasmc.engine.model.Survivor;
import io.github.heavyseasmc.engine.navigation.NavigationCard;
import io.github.heavyseasmc.engine.navigation.NavigationDeck;
import io.github.heavyseasmc.engine.navigation.Selector;
import io.github.heavyseasmc.engine.play.Session;
import io.github.heavyseasmc.engine.play.Table;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class VoyageEscrowTest {

    @Test
    void oldEscrowDoesNotBecomePartOfNewVoyageButCurrentSpectatorsDo() {
        UUID previous = UUID.randomUUID();
        UUID seated = UUID.randomUUID();
        UUID spectator = UUID.randomUUID();
        CharacterId role = CharacterId.of("mate");
        GameComponent component = new GameComponent(null);
        for (UUID id : List.of(previous, seated, spectator)) {
            component.putVoyageEscrow(new GameComponent.VoyageEscrow(
                    id, "minecraft:overworld", 0, 70, 0, 0, 0, new NbtList()));
        }
        Session session = new Session("voyage-membership",
                new Roster(List.of(new Survivor(role, 1, 8, 4, "base", new Ability.None()))),
                new Table(new NavigationDeck(List.of(new NavigationCard("calm", 0,
                        new Selector.Nobody(), new Selector.Nobody(), false, false)), new Random(1)),
                        new Provisions(List.of(new Provision("score", Provision.Category.TREASURE, 1,
                                new ProvisionEffect.ScoreFlat(1, ""))))));
        component.begin(session, Map.of(role, new GameComponent.Occupant(seated, "seated")),
                Set.of(seated, spectator));
        assertFalse(component.belongsToActiveVoyage(previous));
        assertTrue(component.belongsToActiveVoyage(seated));
        assertTrue(component.belongsToActiveVoyage(spectator));

        NbtCompound saved = new NbtCompound();
        component.writeToNbt(saved, null);
        GameComponent restarted = new GameComponent(null);
        restarted.readFromNbt(saved, null);
        assertTrue(restarted.hasVoyageEscrow(spectator));
        assertFalse(restarted.belongsToActiveVoyage(spectator));
        component.end();
        assertFalse(component.belongsToActiveVoyage(seated));
        assertTrue(component.hasVoyageEscrow(previous));
    }

    @Test
    void returnPointAndInventorySurviveWorldComponentNbt() {
        UUID player = UUID.randomUUID();
        NbtList inventory = new NbtList();
        NbtCompound stack = new NbtCompound();
        stack.putString("id", "minecraft:stone");
        inventory.add(stack);
        GameComponent original = new GameComponent(null);
        original.putVoyageEscrow(new GameComponent.VoyageEscrow(
                player, "minecraft:overworld", 12.5, 70.0, -4.5, 90f, 15f, inventory));

        NbtCompound saved = new NbtCompound();
        original.writeToNbt(saved, null);
        GameComponent loaded = new GameComponent(null);
        loaded.readFromNbt(saved, null);

        assertTrue(loaded.hasVoyageEscrow(player));
        GameComponent.VoyageEscrow restored = loaded.removeVoyageEscrow(player).orElseThrow();
        assertEquals("minecraft:overworld", restored.dimension());
        assertEquals(12.5, restored.x());
        assertEquals(70.0, restored.y());
        assertEquals(-4.5, restored.z());
        assertEquals("minecraft:stone", restored.inventory().getCompound(0).getString("id"));
    }
}
