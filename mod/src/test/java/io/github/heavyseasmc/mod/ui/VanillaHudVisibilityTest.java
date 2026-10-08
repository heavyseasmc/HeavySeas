package io.github.heavyseasmc.mod.ui;

import net.minecraft.world.GameMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VanillaHudVisibilityTest {
    @Test
    void onlyCreativeAndSpectatorKeepTheirBarsInMistSea() {
        assertAll(
                () -> assertTrue(VanillaHudVisibility.hidden(true, GameMode.SURVIVAL), "survival must hide vanilla bars"),
                () -> assertTrue(VanillaHudVisibility.hidden(true, GameMode.ADVENTURE), "adventure must hide vanilla bars"),
                () -> assertFalse(VanillaHudVisibility.hidden(true, GameMode.CREATIVE), "creative must keep vanilla bars"),
                () -> assertFalse(VanillaHudVisibility.hidden(true, GameMode.SPECTATOR), "spectator must keep vanilla bars"));
    }

    @Test
    void everyModeKeepsItsBarsOutsideMistSea() {
        for (GameMode mode : GameMode.values()) {
            assertFalse(VanillaHudVisibility.hidden(false, mode), "outside mist sea must keep vanilla bars: " + mode.getName());
        }
    }

    @Test
    void transitionsUseCurrentStateAndNeverLatchHidden() {
        assertTrue(VanillaHudVisibility.hidden(true, GameMode.ADVENTURE));
        assertFalse(VanillaHudVisibility.hidden(true, GameMode.CREATIVE));
        assertTrue(VanillaHudVisibility.hidden(true, GameMode.SURVIVAL));
        assertFalse(VanillaHudVisibility.hidden(true, GameMode.SPECTATOR));
        assertFalse(VanillaHudVisibility.hidden(false, GameMode.SURVIVAL));
        assertFalse(VanillaHudVisibility.hidden(true, null));
    }
}
