package io.github.heavyseasmc.mod.net;

import io.github.heavyseasmc.mod.HeavySeasMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.Optional;

public record CardActionC2S(int code, String card, String target, int token) implements CustomPayload {
    /** 编码只许往后加。{@code OVERBOARD_DONE}：落海那一窗「不用」—— 我这一窗不再出牌（ADR-0095 D1）。 */
    public enum Kind { REVEAL, GIVE_HAND, GIVE_FRONT, OVERBOARD, OVERBOARD_DONE }

    public static final Id<CardActionC2S> ID = new Id<>(Identifier.of(HeavySeasMod.MOD_ID, "card_action"));
    public static final PacketCodec<RegistryByteBuf, CardActionC2S> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, CardActionC2S::code, PacketCodecs.STRING, CardActionC2S::card,
            PacketCodecs.STRING, CardActionC2S::target, PacketCodecs.VAR_INT, CardActionC2S::token,
            CardActionC2S::new);

    public static CardActionC2S of(Kind kind, String card, String target, int token) {
        return new CardActionC2S(kind.ordinal(), card, target, token);
    }

    public Optional<Kind> kind() {
        return code < 0 || code >= Kind.values().length ? Optional.empty() : Optional.of(Kind.values()[code]);
    }

    @Override
    public Id<CardActionC2S> getId() {
        return ID;
    }
}
