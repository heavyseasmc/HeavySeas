package io.github.heavyseasmc.mod.net;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * {@code /seas config} 的回话（ADR-0099 D4 (a)）：服务端说「开设置菜单」，客户端排到下一 tick 再开 ——
 * 回车之后聊天框会把当时的界面关掉，当场开的会被它立刻关掉（ADR-0099 §2.3）。没有字段。
 */
public record OpenSettingsS2C() implements CustomPayload {

    public static final OpenSettingsS2C INSTANCE = new OpenSettingsS2C();

    public static final CustomPayload.Id<OpenSettingsS2C> ID =
            new CustomPayload.Id<>(Identifier.of(HeavySeasMod.MOD_ID, "open_settings"));

    public static final PacketCodec<ByteBuf, OpenSettingsS2C> CODEC = PacketCodec.unit(INSTANCE);

    @Override
    public CustomPayload.Id<OpenSettingsS2C> getId() {
        return ID;
    }
}
