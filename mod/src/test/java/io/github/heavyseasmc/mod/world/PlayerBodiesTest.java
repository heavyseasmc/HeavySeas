package io.github.heavyseasmc.mod.world;

import io.github.heavyseasmc.mod.state.GameComponent;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PlayerBodiesTest {
    @Test
    void mirrorsHeartsButNeverCreatesVanillaDeath() {
        assertEquals(16, PlayerBodies.mirrorHealth(8, 0));
        assertEquals(12, PlayerBodies.mirrorHealth(8, 2));
        assertEquals(1, PlayerBodies.mirrorHealth(3, 3));
        assertEquals(1, PlayerBodies.mirrorHealth(3, 5));
    }

    @Test
    void bodySnapshotSurvivesRestartAlongsideTheOriginalInventory() {
        NbtCompound hunger = new NbtCompound();
        hunger.putInt("foodLevel", 9);
        hunger.putFloat("foodSaturationLevel", 1.5f);
        hunger.putFloat("foodExhaustionLevel", 2.5f);
        var snapshot = new GameComponent.BodySnapshot(26, 17, 4, "adventure", hunger);
        UUID id = UUID.randomUUID();
        var original = new GameComponent(null);
        original.putVoyageEscrow(new GameComponent.VoyageEscrow(id, "minecraft:overworld",
                1, 70, 2, 0, 0, new NbtList(), Optional.of(snapshot)));
        NbtCompound tag = new NbtCompound();
        original.writeToNbt(tag, null);
        var loaded = new GameComponent(null);
        loaded.readFromNbt(tag, null);
        var body = loaded.removeVoyageEscrow(id).orElseThrow().body().orElseThrow();
        assertEquals(snapshot, body);
        assertEquals(9, body.hunger().getInt("foodLevel"));
        assertEquals(2.5f, body.hunger().getFloat("foodExhaustionLevel"));
    }
}
