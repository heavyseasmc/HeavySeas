package io.github.heavyseasmc.mod.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record ClockSampleS2C(long sequence, long sentNanos, long serverMillis) implements CustomPayload {
    public static final Id<ClockSampleS2C> ID = new Id<>(Identifier.of("heavyseas", "clock_sample"));
    public static final PacketCodec<RegistryByteBuf, ClockSampleS2C> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_LONG, ClockSampleS2C::sequence,
            PacketCodecs.VAR_LONG, ClockSampleS2C::sentNanos,
            PacketCodecs.VAR_LONG, ClockSampleS2C::serverMillis, ClockSampleS2C::new);
    @Override public Id<ClockSampleS2C> getId() { return ID; }
}
