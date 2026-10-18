package io.github.heavyseasmc.mod.net;

import io.github.heavyseasmc.mod.HeavySeasMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 服务端告诉舵手：<b>这张是替你挑的</b>（超时），是划船堆里的第几张。
 *
 * <h2>为什么单独一个包、为什么带下标</h2>
 * 与 {@link ProvisionAutoPickS2C} 同一套理由：它是事件不是状态；服务端拿的是它<b>最后收到</b>的高亮，
 * 玩家最后一次移动高亮的包可能还在路上 —— 客户端先把高亮挪到这一张，再播「顿」。
 *
 * <h2>只发给舵手本人</h2>
 * 超时这件事不公开：舵手可以对全船说「时间到了，不是我挑的」，也可以不说 —— 那是他的筹码（决策 ⑭：界面不能揭穿舵手）。
 *
 * @param index 服务端实际执行的那张在划船堆里的下标
 */
public record HelmAutoPickS2C(int index) implements CustomPayload {

    public static final CustomPayload.Id<HelmAutoPickS2C> ID =
            new CustomPayload.Id<>(Identifier.of(HeavySeasMod.MOD_ID, "helm_auto_pick"));

    public static final PacketCodec<RegistryByteBuf, HelmAutoPickS2C> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, HelmAutoPickS2C::index,
            HelmAutoPickS2C::new);

    @Override
    public CustomPayload.Id<HelmAutoPickS2C> getId() {
        return ID;
    }
}
