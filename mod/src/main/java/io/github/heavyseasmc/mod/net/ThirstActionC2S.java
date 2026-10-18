package io.github.heavyseasmc.mod.net;

import io.github.heavyseasmc.mod.HeavySeasMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 口渴的人告诉服务端：我现在打算喝几张（{@code commit=false}），或者就按这个数喝（{@code commit=true}）。
 *
 * <h2>为什么上报的是「几张」而不是「哪一张」</h2>
 * 水没有编号 —— 手上三张水完全等价。所以这一面的决定是个<b>数</b>，
 * 而不是像补给箱、划船堆那样的「哪一张」。
 *
 * <h2>高亮也要上报</h2>
 * 理由与另外三面同一条：超时认<b>当前高亮</b>，而高亮是客户端的状态。
 *
 * @param waters 打算喝几张
 * @param commit true = 就按这个数喝；false = 只是把高亮挪过去
 */
public record ThirstActionC2S(int waters, boolean commit) implements CustomPayload {

    public static final CustomPayload.Id<ThirstActionC2S> ID =
            new CustomPayload.Id<>(Identifier.of(HeavySeasMod.MOD_ID, "thirst_action"));

    public static final PacketCodec<RegistryByteBuf, ThirstActionC2S> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, ThirstActionC2S::waters,
            PacketCodecs.BOOL, ThirstActionC2S::commit,
            ThirstActionC2S::new);

    @Override
    public CustomPayload.Id<ThirstActionC2S> getId() {
        return ID;
    }
}
