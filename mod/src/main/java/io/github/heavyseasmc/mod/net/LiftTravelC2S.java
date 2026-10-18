package io.github.heavyseasmc.mod.net;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 楼层面板上点了一层。服务端不信这里的任何坐标当落点：只认「{@code gate} 那一列里第 {@code target} 扇门」，
 * {@code targetY} 只用来认出「面板开着时那一列的门变了」（变了就拒，不把人送去另一层）。
 *
 * @param gate    打开面板的那扇门（下半块）
 * @param target  去第几层（从 0 数，从下往上）
 * @param targetY 面板上那一层的门在哪个 y
 */
public record LiftTravelC2S(long gate, int target, int targetY) implements CustomPayload {

    public static final Id<LiftTravelC2S> ID = new Id<>(Identifier.of(HeavySeasMod.MOD_ID, "lift_travel"));

    public static final PacketCodec<ByteBuf, LiftTravelC2S> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_LONG, LiftTravelC2S::gate,
            PacketCodecs.VAR_INT, LiftTravelC2S::target,
            PacketCodecs.VAR_INT, LiftTravelC2S::targetY,
            LiftTravelC2S::new);

    @Override
    public Id<LiftTravelC2S> getId() {
        return ID;
    }
}
