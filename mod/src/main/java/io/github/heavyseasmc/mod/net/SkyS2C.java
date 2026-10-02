package io.github.heavyseasmc.mod.net;

import io.github.heavyseasmc.mod.HeavySeasMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 服务端告诉这一个人：<b>在哪个维度里、把天画成什么样</b>（ADR-0054 §9.8 D12 第 4 条 (a)：每个人的屏幕各自画天色）。
 *
 * <h2>为什么不能用游戏自带的时刻与天气包</h2>
 * 一个维度只有一口钟、一场雨，游戏按维度广播给所有人；而北辰号上的人要看 1912-04-14 的夜，同一维度里艇上的人
 * 要看当日天候的昼夜与雨。所以由服务端按人发这一包，客户端拿它替换自己读到的时刻与雨（client 侧的两个 mixin）。
 *
 * @param dimension 只在这个维度里接管（维度 id）；空串 = 不接管，游戏本来是什么就画什么
 * @param timeOfDay 一天里的时刻（0–23999，Minecraft 的刻度）
 * @param rain      雨的强度 0–1
 * @param thunder   雷暴的强度 0–1（游戏会再乘一次雨）
 */
public record SkyS2C(String dimension, long timeOfDay, float rain, float thunder) implements CustomPayload {

    public static final SkyS2C NONE = new SkyS2C("", 0L, 0f, 0f);

    public static final CustomPayload.Id<SkyS2C> ID = new CustomPayload.Id<>(Identifier.of(HeavySeasMod.MOD_ID, "sky"));

    public static final PacketCodec<RegistryByteBuf, SkyS2C> CODEC = PacketCodec.tuple(
            PacketCodecs.STRING, SkyS2C::dimension,
            PacketCodecs.VAR_LONG, SkyS2C::timeOfDay,
            PacketCodecs.FLOAT, SkyS2C::rain,
            PacketCodecs.FLOAT, SkyS2C::thunder,
            SkyS2C::new);

    public boolean active() {
        return !dimension.isEmpty();
    }

    @Override
    public CustomPayload.Id<SkyS2C> getId() {
        return ID;
    }
}
