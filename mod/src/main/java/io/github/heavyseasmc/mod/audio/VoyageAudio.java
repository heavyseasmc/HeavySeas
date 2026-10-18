package io.github.heavyseasmc.mod.audio;

import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.engine.state.GameState;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.net.AudioCueS2C;
import io.github.heavyseasmc.mod.state.GameComponent;
import io.github.heavyseasmc.mod.state.GameComponents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.WeakHashMap;

/** Server-thread live transport. Only sequence counters survive an emission, never events. */
public final class VoyageAudio {
    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);
    private static final Map<GameComponent, Counter> COUNTERS = new WeakHashMap<>();

    private VoyageAudio() {
    }

    /** Active audience includes voyage spectators; thirst speech belongs only to its human speaker. */
    public static boolean eligibleRecipient(AudioCue cue, boolean activeVoyage, boolean speakerRecipient) {
        Objects.requireNonNull(cue, "cue");
        return activeVoyage && (cue != AudioCue.THIRST || speakerRecipient);
    }

    /** Use the post-operation state so unconscious, dead and offline characters never speak. */
    public static String eligibleSpeaker(GameState state, CharacterId who) {
        return state.canAct(who) ? who.value() : "";
    }

    public static void emit(ServerWorld world, GameComponent component, AudioCue cue, String speaker) {
        if (component.session().isEmpty()) {
            return;
        }
        // Flush first: gull refresh may emit its own cues. Allocate our sequence only afterwards.
        // Gulls publishes its new entity list before emitting, so refresh cannot repeat that change.
        GameComponents.flush(world);
        if (component.session().isEmpty()) {
            return;
        }
        UUID privateRecipient = cue == AudioCue.THIRST && !speaker.isBlank()
                ? component.occupantOf(CharacterId.of(speaker)).filter(o -> !o.isDummy())
                        .map(GameComponent.Occupant::player).orElse(null)
                : null;
        var recipients = new ArrayList<ServerPlayerEntity>();
        for (ServerPlayerEntity player : world.getPlayers()) {
            boolean active = player.getServerWorld() == world && component.shouldSyncWith(player);
            if (!eligibleRecipient(cue, active, player.getUuid().equals(privateRecipient))) {
                continue;
            }
            try {
                if (ServerPlayNetworking.canSend(player, AudioCueS2C.ID)) {
                    recipients.add(player);
                }
            } catch (RuntimeException unavailable) {
                LOGGER.debug("Optional audio channel unavailable", unavailable);
            }
        }
        if (recipients.isEmpty()) {
            return;
        }
        UUID epoch = component.notificationEpoch();
        Counter counter = COUNTERS.computeIfAbsent(component, ignored -> new Counter());
        AudioCueS2C payload;
        try {
            payload = new AudioCueS2C(world.getRegistryKey().getValue().toString(), epoch.toString(),
                    counter.next(epoch), cue, speaker);
        } catch (IllegalArgumentException invalid) {
            LOGGER.debug("Optional audio event exceeds transport bounds", invalid);
            return;
        }
        for (ServerPlayerEntity player : recipients) {
            try {
                // Do not use GameComponents.send: it flushes and can reenter gull refresh.
                ServerPlayNetworking.send(player, payload);
            } catch (RuntimeException unavailable) {
                LOGGER.debug("Optional audio event could not be sent", unavailable);
            }
        }
    }

    public static void reset() {
        COUNTERS.clear();
    }

    private static final class Counter {
        private UUID epoch;
        private long sequence;

        long next(UUID current) {
            if (!current.equals(epoch)) {
                epoch = current;
                sequence = 0;
            }
            if (sequence == Long.MAX_VALUE) {
                throw new IllegalArgumentException("Audio sequence exhausted for this voyage");
            }
            return ++sequence;
        }
    }
}