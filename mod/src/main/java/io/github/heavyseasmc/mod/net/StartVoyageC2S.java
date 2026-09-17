package io.github.heavyseasmc.mod.net;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.List;

/** 房主确认阵容并请求从这艘大厅船开局；服务端会从头复核所有条件。 */
public record StartVoyageC2S(long anchor, List<String> characters) implements CustomPayload {
    public static final Id<StartVoyageC2S> ID =
            new Id<>(Identifier.of(HeavySeasMod.MOD_ID, "start_voyage"));
    private static final PacketCodec<ByteBuf, List<String>> STRINGS =
            PacketCodecs.STRING.collect(PacketCodecs.toList(8));
    public static final PacketCodec<RegistryByteBuf, StartVoyageC2S> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_LONG, StartVoyageC2S::anchor,
            STRINGS, StartVoyageC2S::characters,
            StartVoyageC2S::new);

    public StartVoyageC2S {
        characters = List.copyOf(characters);
    }

    @Override
    public Id<StartVoyageC2S> getId() {
        return ID;
    }
}
