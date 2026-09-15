package io.github.heavyseasmc.mod.net;

import io.github.heavyseasmc.mod.HeavySeasMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.Optional;

/**
 * 客户端告诉服务端：行动一面上我按下了哪一件（决策 ⑦）。
 *
 * <h2>只有这一版按得动的两件进得了包</h2>
 * 换座位与抢夺要进「指定模式」—— 回到世界里看着那个人右键；用物资要先给物资效果建模（O19）。
 * 它们不是被服务端拒绝，而是<b>根本没有对应的编码</b>：客户端想发也发不出来。
 *
 * <h2>服务端照样校验</h2>
 * 改过的客户端发得出任何字节。所以编码按序号走，读到不认识的序号时 {@link #kind()} 为空、服务端当作没按 ——
 * 不在解码时抛异常：那会把整条连接断掉，一个坏包不值得踢人。
 *
 * @param code {@link Kind} 的序号
 */
public record ActionChoiceC2S(int code) implements CustomPayload {

    /** 这一版按得动的两件。次序就是编码，只许往后加。 */
    public enum Kind {
        ROW,
        PASS
    }

    public static final CustomPayload.Id<ActionChoiceC2S> ID =
            new CustomPayload.Id<>(Identifier.of(HeavySeasMod.MOD_ID, "action_choice"));

    public static final PacketCodec<RegistryByteBuf, ActionChoiceC2S> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, ActionChoiceC2S::code,
            ActionChoiceC2S::new);

    public static ActionChoiceC2S of(Kind kind) {
        return new ActionChoiceC2S(kind.ordinal());
    }

    public Optional<Kind> kind() {
        Kind[] all = Kind.values();
        return code >= 0 && code < all.length ? Optional.of(all[code]) : Optional.empty();
    }

    @Override
    public CustomPayload.Id<ActionChoiceC2S> getId() {
        return ID;
    }
}
