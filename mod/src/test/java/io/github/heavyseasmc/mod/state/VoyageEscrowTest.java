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
        // 散局之后回北辰号的是刚才那一局的人（入座的与观众），不是存档里早就有托管的人（ADR-0083）
        assertEquals(Set.of(seated, spectator), component.endedVoyagePlayers());
    }

    /** 过魔镜的托管（ADR-0083）：记着从哪面镜子过来，重启之后照样认得出它是过魔镜的、镜子在哪。 */
    @Test
    void mirrorCrossingSurvivesWorldComponentNbt() {
        UUID crossed = UUID.randomUUID();
        UUID legacy = UUID.randomUUID();
        GameComponent original = new GameComponent(null);
        original.putVoyageEscrow(new GameComponent.VoyageEscrow(crossed, "minecraft:overworld", 10.5, 64, -3.5, 90f, 0f,
                new NbtList(), java.util.Optional.empty(),
                java.util.Optional.of(new GameComponent.MirrorAt("minecraft:overworld", new net.minecraft.util.math.BlockPos(11, 64, -3)))));
        original.putVoyageEscrow(new GameComponent.VoyageEscrow(legacy, "minecraft:overworld", 0, 70, 0, 0, 0, new NbtList()));

        NbtCompound saved = new NbtCompound();
        original.writeToNbt(saved, null);
        GameComponent loaded = new GameComponent(null);
        loaded.readFromNbt(saved, null);

        GameComponent.VoyageEscrow back = loaded.voyageEscrow(crossed).orElseThrow();
        assertTrue(back.viaMirror());
        assertEquals(new net.minecraft.util.math.BlockPos(11, 64, -3), back.mirror().orElseThrow().pos());
        assertEquals("minecraft:overworld", back.mirror().orElseThrow().dimension());
        assertFalse(loaded.voyageEscrow(legacy).orElseThrow().viaMirror(), "开局时托管的老路不该被认成过魔镜的");
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
