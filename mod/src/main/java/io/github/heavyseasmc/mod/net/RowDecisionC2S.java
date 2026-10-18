package io.github.heavyseasmc.mod.net;

import io.github.heavyseasmc.mod.HeavySeasMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 客户端告诉服务端：划船抽到的牌里，选择第 {@code index} 张放进划船堆。
 *
 * <h2>一次选择，一个包</h2>
 * 服务端把选中的牌放进划船堆，其余牌按抽出顺序塞回牌堆底。客户端不再逐张发送去留，
 * 因而也不能通过改包把同一次划船里的多张牌都留下。
 *
 * <h2>服务端照样校验</h2>
 * 只认正在划船的那个人本人、只认这组待选牌里的下标。重复包与过期包直接忽略。
 *
 * @param index 抽出顺序里的第几张，从 0 起
 */
public record RowDecisionC2S(int index) implements CustomPayload {

    public static final CustomPayload.Id<RowDecisionC2S> ID =
            new CustomPayload.Id<>(Identifier.of(HeavySeasMod.MOD_ID, "row_decision"));

    public static final PacketCodec<RegistryByteBuf, RowDecisionC2S> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, RowDecisionC2S::index,
            RowDecisionC2S::new);

    @Override
    public CustomPayload.Id<RowDecisionC2S> getId() {
        return ID;
    }
}
