package io.github.heavyseasmc.mod.net;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * 服务端告诉一个玩家：补给箱现在在谁手上、还剩几张、什么时候超时。
 *
 * <h2>❗{@code offer} 只发给持有者</h2>
 * 信息梯度是<b>规则</b>，不是显示差异（决策 ⑨：绝不能让所有人同时看到完整牌池）。
 * 所以裁剪必须在服务端做：给别人发空列表，而不是发全池再让客户端藏起来 ——
 * 后者对改过客户端的人完全无效，而那正好摧毁船头位的信息优势。
 *
 * <p>{@code chain} 与 {@code remaining} 是<b>公开信息</b>：全船本来就看得见箱子传到谁手上、
 * 还剩几个人没拿。把它们发给所有人，「等待变成可见的」才成立。
 *
 * @param chain      这一轮的传递顺序（角色 id，船头到船尾），开始时定死
 * @param at         箱子传到第几位；{@code >= chain.size()} 表示这一轮结束
 * @param remaining  箱子里还剩几张（公开）
 * @param deadlineMs 服务端的超时时刻（{@code System.currentTimeMillis()} 同一时钟）；0 表示没有计时
 * @param offer      <b>只有持有者拿得到内容</b>，其余人一律是空列表
 */
public record ProvisionUpdateS2C(List<String> chain, int at, int remaining,
                                 long deadlineMs, List<String> offer) implements CustomPayload {

    public static final CustomPayload.Id<ProvisionUpdateS2C> ID =
            new CustomPayload.Id<>(Identifier.of(HeavySeasMod.MOD_ID, "provision_update"));

    // ❗声明成 ByteBuf 而不是 RegistryByteBuf：PacketCodecs.STRING 本身就是 ByteBuf 上的，
    //   而 tuple 收的是 `? super B`，所以 ByteBuf 版正好能用在 RegistryByteBuf 的包上。
    private static final PacketCodec<ByteBuf, List<String>> STRINGS =
            PacketCodecs.STRING.collect(PacketCodecs.toList());

    public static final PacketCodec<RegistryByteBuf, ProvisionUpdateS2C> CODEC = PacketCodec.tuple(
            STRINGS, ProvisionUpdateS2C::chain,
            PacketCodecs.VAR_INT, ProvisionUpdateS2C::at,
            PacketCodecs.VAR_INT, ProvisionUpdateS2C::remaining,
            PacketCodecs.VAR_LONG, ProvisionUpdateS2C::deadlineMs,
            STRINGS, ProvisionUpdateS2C::offer,
            ProvisionUpdateS2C::new);

    /** 这一轮结束时发的那一包：客户端收到就关界面。 */
    public static ProvisionUpdateS2C finished() {
        return new ProvisionUpdateS2C(List.of(), 0, 0, 0L, List.of());
    }

    public boolean active() {
        return !chain.isEmpty() && at < chain.size();
    }

    @Override
    public CustomPayload.Id<ProvisionUpdateS2C> getId() {
        return ID;
    }
}
