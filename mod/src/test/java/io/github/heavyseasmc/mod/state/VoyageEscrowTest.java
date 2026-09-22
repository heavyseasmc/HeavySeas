package io.github.heavyseasmc.mod.state;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class VoyageEscrowTest {

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
