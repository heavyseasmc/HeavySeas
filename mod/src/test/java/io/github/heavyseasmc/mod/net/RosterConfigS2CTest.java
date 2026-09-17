package io.github.heavyseasmc.mod.net;

import io.github.heavyseasmc.engine.data.RosterData;
import io.github.heavyseasmc.engine.data.RosterLoader;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RosterConfigS2CTest {

    private static final RosterData ROSTER = RosterLoader.load(rosterFile());

    @Test
    void factoryCarriesTheRealUniqueCatalogAndAllThreePresets() {
        for (int players = 6; players <= 8; players++) {
            RosterConfigS2C packet = RosterConfigS2C.from(1234L, players, ROSTER);

            assertEquals(1234L, packet.anchor());
            assertEquals(players, packet.players());
            assertEquals(8, packet.characters().size());
            assertEquals(packet.characters().size(), Set.copyOf(packet.characters()).size());
            assertPreset(packet.characters(), packet.preset6(), 6);
            assertPreset(packet.characters(), packet.preset7(), 7);
            assertPreset(packet.characters(), packet.preset8(), 8);
        }
    }

    @Test
    void factoryRejectsPlayerCountsOutsideTheScreenContract() {
        assertThrows(IllegalArgumentException.class, () -> RosterConfigS2C.from(0L, 5, ROSTER));
        assertThrows(IllegalArgumentException.class, () -> RosterConfigS2C.from(0L, 9, ROSTER));
    }

    private static void assertPreset(List<String> catalog, List<String> preset, int players) {
        assertEquals(players, preset.size());
        assertEquals(preset.size(), Set.copyOf(preset).size(), players + " 人预设不得重复角色");
        assertTrue(catalog.containsAll(preset));
    }

    private static Path rosterFile() {
        for (Path candidate : List.of(
                Path.of("data", "roster", "default.json"),
                Path.of("..", "data", "roster", "default.json"))) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("找不到仓库 data/roster/default.json");
    }
}
