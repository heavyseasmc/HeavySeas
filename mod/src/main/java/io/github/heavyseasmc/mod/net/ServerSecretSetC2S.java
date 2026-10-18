package io.github.heavyseasmc.mod.net;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.config.ServerSettingsTable;
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
 * @param scope 这项密钥绑的那一项设置，发包的人在菜单里看到的值（{@code llm.api_key} 绑 {@code llm.base_url}；
 *              不绑的密钥是空串）。服务端拿它与此刻的地址比，对不上就拒 —— 密钥只发往设它时的那个地址（审查 2026-10-07 L1）
 */
public record ServerSecretSetC2S(String key, String value, String scope) implements CustomPayload {

    /**
     * 值的上限（UTF-16 单元数）：密钥按码点数限长（{@link ServerSettingsTable#KEY_MAX}），BMP 以外的字一个占两个单元。
     * 原先用的是 256，菜单收得下 512 字的密钥、包装不下，一存就把人踢下线（审查 2026-10-07 U2）。
     */
    public static final int MAX_VALUE = 2 * ServerSettingsTable.KEY_MAX;

    public static final CustomPayload.Id<ServerSecretSetC2S> ID =
            new CustomPayload.Id<>(Identifier.of(HeavySeasMod.MOD_ID, "server_secret_set"));

    public static final PacketCodec<ByteBuf, ServerSecretSetC2S> CODEC = PacketCodec.tuple(
            PacketCodecs.string(ServerSettingsS2C.MAX_TEXT), ServerSecretSetC2S::key,
            PacketCodecs.string(MAX_VALUE), ServerSecretSetC2S::value,
            PacketCodecs.string(ServerSettingsS2C.MAX_VALUE), ServerSecretSetC2S::scope,
            ServerSecretSetC2S::new);

    public ServerSecretSetC2S {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        scope = Objects.requireNonNullElse(scope, "");
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
