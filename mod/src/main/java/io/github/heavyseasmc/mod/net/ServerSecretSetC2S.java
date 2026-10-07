package io.github.heavyseasmc.mod.net;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.Objects;

/**
 * 设一项密钥（ADR-0099 D6 · 只写不读）：键 + 新值；新值是空串 = 清掉。
 *
 * <p>服务端再查权限、只认表里登记过的密钥、按那一项的规矩核过才存（{@code ServerSettings#setSecret}）。
 * <b>值不回显</b>：回来的只有新快照，快照里密钥只有「设了没有」；服务端日志只写「谁设了 / 清了哪一项」。
 *
 * @param key   密钥的键（{@code SettingDef#secret} 那一类）
 * @param value 新值；空串 = 清掉
 */
public record ServerSecretSetC2S(String key, String value) implements CustomPayload {

    public static final CustomPayload.Id<ServerSecretSetC2S> ID =
            new CustomPayload.Id<>(Identifier.of(HeavySeasMod.MOD_ID, "server_secret_set"));

    public static final PacketCodec<ByteBuf, ServerSecretSetC2S> CODEC = PacketCodec.tuple(
            PacketCodecs.string(ServerSettingsS2C.MAX_TEXT), ServerSecretSetC2S::key,
            PacketCodecs.string(ServerSettingsS2C.MAX_TEXT), ServerSecretSetC2S::value,
            ServerSecretSetC2S::new);

    public ServerSecretSetC2S {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
    }

    /** ❗不带值：record 自动生成的那一版会把值打出来，哪天有人把包写进日志就漏了。 */
    @Override
    public String toString() {
        return "ServerSecretSetC2S[" + key + (value.isEmpty() ? " 清掉" : " 设新值") + "]";
    }

    @Override
    public CustomPayload.Id<ServerSecretSetC2S> getId() {
        return ID;
    }
}
