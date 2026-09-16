package io.github.heavyseasmc.mod.net;

import io.github.heavyseasmc.mod.HeavySeasMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.Optional;

/**
 * 手牌一面打出特殊物资，以及医疗箱挑目标的第二步。
 *
 * <p>两步共用一个包，避免再造两份几乎一样的字段表：先发 {@link Kind#PLAY} 与牌 id；
 * 服务端确认它是需要目标的治疗牌后，把候选人只投影给本人；本人再发 {@link Kind#TARGET}。
 * {@link Kind#CANCEL} 只收起这个目标选择，不花掉行动。
 *
 * <p>改过的客户端可以填任意牌与角色，所以服务端会重问阶段、行动者、持牌、效果与候选目标。
 * 枚举编码只许往后加；未知编码静默忽略，不为一个坏包断开整条连接。
 */
public record UseProvisionC2S(int code, String card, String target) implements CustomPayload {

    public enum Kind {
        PLAY,
        TARGET,
        CANCEL
    }

    public static final CustomPayload.Id<UseProvisionC2S> ID =
            new CustomPayload.Id<>(Identifier.of(HeavySeasMod.MOD_ID, "use_provision"));

    public static final PacketCodec<RegistryByteBuf, UseProvisionC2S> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, UseProvisionC2S::code,
            PacketCodecs.STRING, UseProvisionC2S::card,
            PacketCodecs.STRING, UseProvisionC2S::target,
            UseProvisionC2S::new);

    public static UseProvisionC2S play(String card) {
        return new UseProvisionC2S(Kind.PLAY.ordinal(), card, "");
    }

    public static UseProvisionC2S target(String card, String target) {
        return new UseProvisionC2S(Kind.TARGET.ordinal(), card, target);
    }

    public static UseProvisionC2S cancel() {
        return new UseProvisionC2S(Kind.CANCEL.ordinal(), "", "");
    }

    public Optional<Kind> kind() {
        Kind[] all = Kind.values();
        return code >= 0 && code < all.length ? Optional.of(all[code]) : Optional.empty();
    }

    @Override
    public CustomPayload.Id<UseProvisionC2S> getId() {
        return ID;
    }
}
