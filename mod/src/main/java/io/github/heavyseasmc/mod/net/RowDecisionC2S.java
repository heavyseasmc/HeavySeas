package io.github.heavyseasmc.mod.net;

import io.github.heavyseasmc.mod.HeavySeasMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 客户端告诉服务端：划船抽到的第 {@code index} 张，留进划船堆（{@code keep=true}）还是塞回牌堆底。
 *
 * <h2>一张一个包</h2>
 * 规则是「对每一张分别决定」，桌上的人看得见划船者一张一张放下去 —— 划船堆有几张是公开信息（决策 ⑭）。
 * 两张攒到一起再发，中间那一段别人就看不到堆在变。
 *
 * <h2>服务端照样校验</h2>
 * 只认正在划船的那个人本人、只认还没定的那一张。同一张按了两下这种擦肩而过的包当作没按 ——
 * 引擎对「定两次」是抛的，那一道是给规则层的；网络层先挡掉，一个重复的包不值得把服务端日志刷红。
 *
 * @param index 抽出顺序里的第几张，从 0 起
 * @param keep  true = 留进划船堆；false = 塞回牌堆底部
 */
public record RowDecisionC2S(int index, boolean keep) implements CustomPayload {

    public static final CustomPayload.Id<RowDecisionC2S> ID =
            new CustomPayload.Id<>(Identifier.of(HeavySeasMod.MOD_ID, "row_decision"));

    public static final PacketCodec<RegistryByteBuf, RowDecisionC2S> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, RowDecisionC2S::index,
            PacketCodecs.BOOL, RowDecisionC2S::keep,
            RowDecisionC2S::new);

    @Override
    public CustomPayload.Id<RowDecisionC2S> getId() {
        return ID;
    }
}
