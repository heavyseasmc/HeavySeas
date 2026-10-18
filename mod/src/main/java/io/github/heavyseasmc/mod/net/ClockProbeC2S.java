package io.github.heavyseasmc.mod.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record ClockProbeC2S(long sequence, long sentNanos) implements CustomPayload {
    public static final Id<ClockProbeC2S> ID = new Id<>(Identifier.of("heavyseas", "clock_probe"));
    public static final PacketCodec<RegistryByteBuf, ClockProbeC2S> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_LONG, ClockProbeC2S::sequence,
            PacketCodecs.VAR_LONG, ClockProbeC2S::sentNanos, ClockProbeC2S::new);
    @Override public Id<ClockProbeC2S> getId() { return ID; }
}
