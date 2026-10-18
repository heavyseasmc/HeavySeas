package io.github.heavyseasmc.mod.state;

/** Text codecs include item-hover codecs, so real packet round trips need vanilla registries. */
final class MinecraftTestBootstrap {
    private static boolean ready;

    private MinecraftTestBootstrap() { }

    static synchronized void initialize() {
        if (ready) return;
        net.minecraft.SharedConstants.createGameVersion();
        net.minecraft.Bootstrap.initialize();
        ready = true;
    }
}
