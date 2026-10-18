package io.github.heavyseasmc.mod.ui;

import net.minecraft.world.GameMode;

/** Dimension and current mode alone determine whether the vanilla bars are hidden. */
public final class VanillaHudVisibility {
    private VanillaHudVisibility() {
    }

    public static boolean hidden(boolean inMistSea, GameMode mode) {
        return inMistSea && mode != null && mode != GameMode.CREATIVE && mode != GameMode.SPECTATOR;
    }
}
