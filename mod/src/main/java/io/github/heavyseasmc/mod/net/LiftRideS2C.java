package io.github.heavyseasmc.mod.net;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 电梯出发了：发给轿厢里被带走的每一个人。客户端照 {@code ticks} 渐暗、轻晃（{@code ui.LiftRide}），
 * 服务端走完这么多 tick 把人送到目标层 —— 换层那一下落在最暗的时候。
 *
 * @param ticks 这一趟要走多少 tick（每过一层 20）
 */
public record LiftRideS2C(int ticks) implements CustomPayload {

    public static final Id<LiftRideS2C> ID = new Id<>(Identifier.of(HeavySeasMod.MOD_ID, "lift_ride"));

    public static final PacketCodec<ByteBuf, LiftRideS2C> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, LiftRideS2C::ticks, LiftRideS2C::new);

    @Override
    public Id<LiftRideS2C> getId() {
        return ID;
    }
}
