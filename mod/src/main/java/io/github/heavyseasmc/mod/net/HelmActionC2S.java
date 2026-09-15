package io.github.heavyseasmc.mod.net;

import io.github.heavyseasmc.mod.HeavySeasMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 舵手告诉服务端：我现在指着划船堆里的第几张（{@code commit=false}），或者就执行这张（{@code commit=true}）。
 *
 * <h2>高亮也要上报</h2>
 * 理由与补给箱同一条（{@link ProvisionActionC2S}）：超时认的是<b>当前高亮</b>（用户 2026-09-15 定，与补给箱同一条规则），
 * 而高亮是客户端的状态 —— 不上报，超时那一刻服务端就只能乱挑。
 *
 * @param index  划船堆里的下标
 * @param commit true = 就执行这张；false = 只是把高亮移过去
 */
public record HelmActionC2S(int index, boolean commit) implements CustomPayload {

    public static final CustomPayload.Id<HelmActionC2S> ID =
            new CustomPayload.Id<>(Identifier.of(HeavySeasMod.MOD_ID, "helm_action"));

    public static final PacketCodec<RegistryByteBuf, HelmActionC2S> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, HelmActionC2S::index,
            PacketCodecs.BOOL, HelmActionC2S::commit,
            HelmActionC2S::new);

    @Override
    public CustomPayload.Id<HelmActionC2S> getId() {
        return ID;
    }
}
