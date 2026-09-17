package io.github.heavyseasmc.mod.net;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.List;

/** 大厅船上玩家打开阵容面板所需的公开目录与三套预设。 */
public record RosterConfigS2C(long anchor, int players, List<String> characters,
                              List<String> preset6, List<String> preset7, List<String> preset8)
        implements CustomPayload {

    public static final Id<RosterConfigS2C> ID =
            new Id<>(Identifier.of(HeavySeasMod.MOD_ID, "roster_config"));
    private static final PacketCodec<ByteBuf, List<String>> STRINGS =
            PacketCodecs.STRING.collect(PacketCodecs.toList(8));
    public static final PacketCodec<RegistryByteBuf, RosterConfigS2C> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_LONG, RosterConfigS2C::anchor,
            PacketCodecs.VAR_INT, RosterConfigS2C::players,
            STRINGS, RosterConfigS2C::characters,
            STRINGS, RosterConfigS2C::preset6,
            STRINGS, RosterConfigS2C::preset7,
            STRINGS, RosterConfigS2C::preset8,
            RosterConfigS2C::new);

    public RosterConfigS2C {
        characters = List.copyOf(characters);
        preset6 = List.copyOf(preset6);
        preset7 = List.copyOf(preset7);
        preset8 = List.copyOf(preset8);
    }

    @Override
    public Id<RosterConfigS2C> getId() {
        return ID;
    }
}
