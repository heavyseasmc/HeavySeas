package io.github.heavyseasmc.mod.net;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.audio.AudioCue;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.Objects;

/** A live, non-replayed event. Speaker is a public character ID, never a resource path. */
public record AudioCueS2C(String dimension, String epoch, long sequence, AudioCue cue, String speaker)
        implements CustomPayload {
    public static final CustomPayload.Id<AudioCueS2C> ID =
            new CustomPayload.Id<>(Identifier.of(HeavySeasMod.MOD_ID, "audio_cue"));

    public AudioCueS2C {
        bounded(dimension, 256, "dimension");
        bounded(epoch, 36, "epoch");
        bounded(speaker, 32, "speaker");
        Objects.requireNonNull(cue, "cue");
        if (sequence < 0) {
            throw new IllegalArgumentException("Audio sequence must be nonnegative");
        }
    }

    public static final PacketCodec<RegistryByteBuf, AudioCueS2C> CODEC = new PacketCodec<>() {
        @Override
        public AudioCueS2C decode(RegistryByteBuf buf) {
            return new AudioCueS2C(buf.readString(256), buf.readString(36), buf.readVarLong(),
                    buf.readEnumConstant(AudioCue.class), buf.readString(32));
        }

        @Override
        public void encode(RegistryByteBuf buf, AudioCueS2C value) {
            buf.writeString(value.dimension(), 256);
            buf.writeString(value.epoch(), 36);
            buf.writeVarLong(value.sequence());
            buf.writeEnumConstant(value.cue());
            buf.writeString(value.speaker(), 32);
        }
    };

    private static void bounded(String value, int limit, String name) {
        Objects.requireNonNull(value, name);
        if (value.length() > limit) {
            throw new IllegalArgumentException("Audio " + name + " exceeds " + limit + " characters");
        }
    }

    @Override
    public CustomPayload.Id<AudioCueS2C> getId() {
        return ID;
    }
}