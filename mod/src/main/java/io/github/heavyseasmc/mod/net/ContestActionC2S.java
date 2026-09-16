package io.github.heavyseasmc.mod.net;

import io.github.heavyseasmc.mod.HeavySeasMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.Optional;

/**
 * 这一场进行中的四面上按下的那一下（ADR-0023）：表态 · 站队 · 挂武器 · 挑牌。
 *
 * <h2>四面一个包</h2>
 * 四面各自只有两三下，而每加一个包就多一处「两端字段表要对上」—— 而字段表对不上的表现是
 * 缓冲区错位，随机而且不点名（{@code GameComponent} 的收尾哨兵记的正是这件事）。
 * 所以合成一个包，按 {@link Kind} 分路。
 *
 * <h2>服务端照样校验</h2>
 * 改过的客户端发得出任何字节：谁能表态、谁能加入、押的牌是不是真在手上，全部由服务端重问一遍
 * （{@code ContestPhase#onAction}），而规则那一层还有引擎的守卫。读到不认识的序号时
 * {@link #kind()} 为空、当作没按 —— <b>不在解码时抛异常</b>：那会把整条连接断掉，一个坏包不值得踢人。
 *
 * @param code {@link Kind} 的序号
 * @param card 押哪一张（{@link Kind#COMMIT_WEAPON}）· 挑面前的哪一张（{@link Kind#PICK_FRONT}）；
 *             其余几下是空串 —— ❗手牌那一下<b>不带牌名</b>，下标由服务端随机出（规则 §5：抢手牌是随机的）
 */
public record ContestActionC2S(int code, String card) implements CustomPayload {

    /** 四面上按得出的几下。次序就是编码，只许往后加。 */
    public enum Kind {
        CONSENT_AGREE,
        CONSENT_FIGHT,
        JOIN_ATTACK,
        JOIN_DEFEND,
        COMMIT_WEAPON,
        PICK_FRONT,
        PICK_HAND
    }

    public static final CustomPayload.Id<ContestActionC2S> ID =
            new CustomPayload.Id<>(Identifier.of(HeavySeasMod.MOD_ID, "contest_action"));

    public static final PacketCodec<RegistryByteBuf, ContestActionC2S> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, ContestActionC2S::code,
            PacketCodecs.STRING, ContestActionC2S::card,
            ContestActionC2S::new);

    public static ContestActionC2S of(Kind kind) {
        return new ContestActionC2S(kind.ordinal(), "");
    }

    public static ContestActionC2S of(Kind kind, String card) {
        return new ContestActionC2S(kind.ordinal(), card);
    }

    public Optional<Kind> kind() {
        Kind[] all = Kind.values();
        return code >= 0 && code < all.length ? Optional.of(all[code]) : Optional.empty();
    }

    @Override
    public CustomPayload.Id<ContestActionC2S> getId() {
        return ID;
    }
}
