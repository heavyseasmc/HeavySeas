package io.github.heavyseasmc.mod.net;

import io.github.heavyseasmc.mod.HeavySeasMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 客户端告诉服务端：我现在指着第几张（{@code commit=false}），或者我就留这张（{@code commit=true}）。
 *
 * <h2>为什么高亮也要上报</h2>
 * 决策 ⑨ 规定超时自动选<b>当前高亮</b>那张，而不是随机。高亮是客户端的状态，
 * 服务端不知道 —— 不上报的话超时那一刻服务端只能乱选，规则就落空了。
 *
 * <p>所以每次移动高亮都发一个很小的包。代价是几字节，换来的是<b>超时的结果是可预期的</b>：
 * 玩家看着哪张，超时就拿哪张。
 *
 * <p>客户端掉线时服务端用最后一次收到的高亮；一次都没收到就取第一张
 * （决策 ⑧：离线玩家的座位照常运转，不能卡住整局）。
 *
 * @param index  offer 里的下标
 * @param commit true = 就留这张；false = 只是把高亮移过去
 */
public record ProvisionActionC2S(int index, boolean commit) implements CustomPayload {

    public static final CustomPayload.Id<ProvisionActionC2S> ID =
            new CustomPayload.Id<>(Identifier.of(HeavySeasMod.MOD_ID, "provision_action"));

    public static final PacketCodec<RegistryByteBuf, ProvisionActionC2S> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, ProvisionActionC2S::index,
            PacketCodecs.BOOL, ProvisionActionC2S::commit,
            ProvisionActionC2S::new);

    @Override
    public CustomPayload.Id<ProvisionActionC2S> getId() {
        return ID;
    }
}
