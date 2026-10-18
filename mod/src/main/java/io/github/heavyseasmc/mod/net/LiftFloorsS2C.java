package io.github.heavyseasmc.mod.net;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * 右键电梯门的回话：这一列的楼层表（从低到高）与「你在第几层」，客户端照它弹楼层面板。
 *
 * <p>甲板名传名字（{@code "e"}、{@code "boat"}……），不传枚举序号：常量的次序改了，线上的含义不变。
 * 面板上点了一层回 {@link LiftTravelC2S}，服务端从头核对。
 *
 * @param gate    右键的那扇门（下半块）的位置
 * @param current 这扇门是表里第几层（从 0 数）
 * @param stops   每一层：门的 y 与甲板名
 */
public record LiftFloorsS2C(long gate, int current, List<Stop> stops) implements CustomPayload {

    /** 一部电梯最多认这么多层（世界从底到顶 384 格，两格一扇门也到不了；超了是数据坏了，编码时就拒）。 */
    public static final int MAX_STOPS = 64;

    public static final Id<LiftFloorsS2C> ID = new Id<>(Identifier.of(HeavySeasMod.MOD_ID, "lift_floors"));

    /** 一层：门的 y · 甲板名（{@code none} = 照位置编号）。 */
    public record Stop(int y, String deck) {
        static final PacketCodec<ByteBuf, Stop> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, Stop::y, PacketCodecs.string(16), Stop::deck, Stop::new);
    }

    public static final PacketCodec<ByteBuf, LiftFloorsS2C> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_LONG, LiftFloorsS2C::gate,
            PacketCodecs.VAR_INT, LiftFloorsS2C::current,
            Stop.CODEC.collect(PacketCodecs.toList(MAX_STOPS)), LiftFloorsS2C::stops,
            LiftFloorsS2C::new);

    public LiftFloorsS2C {
        stops = List.copyOf(stops);
    }

    @Override
    public Id<LiftFloorsS2C> getId() {
        return ID;
    }
}
