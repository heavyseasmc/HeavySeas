package io.github.heavyseasmc.mod.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;

/** Identity of one client intent; never an authorization to bypass the current rule guards. */
public record RequestStamp(String matchId, String context, long nonce) {
    public static final RequestStamp EMPTY = new RequestStamp("", "", 0);
    public static final PacketCodec<RegistryByteBuf, RequestStamp> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, RequestStamp::matchId, PacketCodecs.STRING, RequestStamp::context,
            PacketCodecs.VAR_LONG, RequestStamp::nonce, RequestStamp::new);
    public boolean present() { return nonce > 0 && !matchId.isEmpty() && !context.isEmpty(); }
}
