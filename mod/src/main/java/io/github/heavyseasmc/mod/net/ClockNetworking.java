package io.github.heavyseasmc.mod.net;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ClockNetworking {
    private static final Logger LOGGER = LoggerFactory.getLogger("heavyseas");
    private static final Map<UUID, Long> LAST_PROBE = new HashMap<>();
    private ClockNetworking() { }

    public static void register() {
        PayloadTypeRegistry.playC2S().register(ClockProbeC2S.ID, ClockProbeC2S.CODEC);
        PayloadTypeRegistry.playS2C().register(ClockSampleS2C.ID, ClockSampleS2C.CODEC);
        ServerLoginConnectionEvents.QUERY_START.register((handler, server, sender, synchronizer) -> {
            var buf = PacketByteBufs.create();
            buf.writeVarInt(ExperienceProtocol.VERSION);
            sender.sendPacket(ExperienceProtocol.CHANNEL, buf);
        });
        ServerLoginNetworking.registerGlobalReceiver(ExperienceProtocol.CHANNEL,
                (server, handler, understood, buf, synchronizer, sender) -> {
                    LoginVerdict verdict = LoginVerdict.of(understood, buf);
                    if (!verdict.admitted()) {
                        synchronizer.waitFor(server.submit(() -> handler.disconnect(ExperienceProtocol.mismatch())));
                    } else if (verdict == LoginVerdict.BYSTANDER) {
                        LOGGER.debug("Login without Heavy Seas installed; admitted as a bystander");
                    }
                });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (ServerPlayNetworking.canSend(handler.player, ClockSampleS2C.ID)) {
                ServerPlayNetworking.send(handler.player, new ClockSampleS2C(0, 0, System.currentTimeMillis()));
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(ClockProbeC2S.ID, (probe, context) -> {
            var player = context.player();
            long now = System.nanoTime();
            Long previous = LAST_PROBE.get(player.getUuid());
            if (previous != null && now - previous < 250_000_000L) return;
            LAST_PROBE.put(player.getUuid(), now);
            ServerPlayNetworking.send(player, new ClockSampleS2C(probe.sequence(), probe.sentNanos(), System.currentTimeMillis()));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                server.execute(() -> LAST_PROBE.remove(handler.player.getUuid())));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> LAST_PROBE.clear());
    }
}
