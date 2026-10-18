package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.net.ActionChoiceC2S;
import io.github.heavyseasmc.mod.net.ClockProbeC2S;
import io.github.heavyseasmc.mod.net.ClockSampleS2C;
import io.github.heavyseasmc.mod.net.ExperienceProtocol;
import io.github.heavyseasmc.mod.ui.ServerClockEstimate;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;

import java.util.concurrent.CompletableFuture;

/** Display time only. Server window identities and local animation clocks are never rewritten. */
public final class GameClock {
    private static final ServerClockEstimate CLOCK = new ServerClockEstimate();
    private static volatile boolean compatible;
    private static long sequence;
    private static long pendingSequence;
    private static long pendingSent;
    private static long nextProbe;
    private GameClock() { }

    public static long now() { return CLOCK.now(System.nanoTime(), System.currentTimeMillis()); }
    public static long remaining(long serverDeadline) { return Math.max(0, serverDeadline - now()); }

    private static void reset() {
        CLOCK.reset(); compatible = false;
        sequence = 0; pendingSequence = 0; pendingSent = 0; nextProbe = 0;
    }

    public static void register() {
        ClientLoginConnectionEvents.INIT.register((handler, client) -> reset());
        ClientLoginNetworking.registerGlobalReceiver(ExperienceProtocol.CHANNEL, (client, handler, buf, sender) -> {
            boolean valid;
            try { valid = buf.readVarInt() == ExperienceProtocol.VERSION && !buf.isReadable(); }
            catch (RuntimeException ignored) { valid = false; }
            compatible = valid;
            var answer = PacketByteBufs.create();
            answer.writeVarInt(valid ? ExperienceProtocol.VERSION : -1);
            return CompletableFuture.completedFuture(answer);
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            // Unmodified Minecraft servers remain usable; an older Heavy Seas server needs the matching client.
            if (!compatible && ClientPlayNetworking.canSend(ActionChoiceC2S.ID)) {
                handler.getConnection().disconnect(ExperienceProtocol.mismatch());
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        ClientPlayNetworking.registerGlobalReceiver(ClockSampleS2C.ID, (sample, context) -> {
            long received = System.nanoTime();
            if (sample.sequence() == 0) {
                CLOCK.seed(sample.serverMillis(), received);
            } else if (sample.sequence() == pendingSequence && sample.sentNanos() == pendingSent) {
                CLOCK.sample(sample.sequence(), sample.sentNanos(), sample.serverMillis(), received);
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.getNetworkHandler() == null || !compatible || !ClientPlayNetworking.canSend(ClockProbeC2S.ID)) return;
            long now = System.nanoTime();
            if (now < nextProbe) return;
            pendingSequence = ++sequence;
            pendingSent = now;
            ClientPlayNetworking.send(new ClockProbeC2S(pendingSequence, pendingSent));
            nextProbe = now + (sequence < 3 ? 1_000_000_000L : 30_000_000_000L);
        });
    }
}
