package io.github.heavyseasmc.mod.net;

import io.github.heavyseasmc.mod.HeavySeasMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 服务端告诉持有者：<b>这张是替你选的</b>，是第几张。
 *
 * <h2>为什么要单独一个包</h2>
 * {@link ProvisionUpdateS2C} 是发给全船的状态快照，而这件事只跟一个人有关，
 * 而且只发生一次 —— 它是<b>事件</b>不是状态。塞进状态包的话，每个人都会收到
 * 「谁被替选了」，而那不是公开信息；何况状态包一轮要发好几次，事件会被重放。
 *
 * <h2>为什么不让客户端自己看倒计时到点</h2>
 * 服务端才是计时的权威（改过的客户端可以永远不超时）。而且服务端实际拿的是
 * <b>它最后收到的那个高亮</b>，玩家最后一次移动高亮的包可能还在路上 ——
 * 客户端自己猜的话，屏幕上「顿」的那张会和真正进手里的那张不是同一张。
 *
 * <p>所以这里带上 {@code index}：客户端先把高亮挪到服务端说的那张，再播「顿」。
 *
 * @param index 服务端实际留下的那张在 offer 里的下标
 */
public record ProvisionAutoPickS2C(int index) implements CustomPayload {

    public static final CustomPayload.Id<ProvisionAutoPickS2C> ID =
            new CustomPayload.Id<>(Identifier.of(HeavySeasMod.MOD_ID, "provision_auto_pick"));

    public static final PacketCodec<RegistryByteBuf, ProvisionAutoPickS2C> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, ProvisionAutoPickS2C::index,
            ProvisionAutoPickS2C::new);

    @Override
    public CustomPayload.Id<ProvisionAutoPickS2C> getId() {
        return ID;
    }
}
