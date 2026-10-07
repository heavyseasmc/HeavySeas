package io.github.heavyseasmc.mod.net;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务端设置的快照（ADR-0099 D6）：每一项此刻的值，加上收件人能不能改。
 *
 * <p>什么时候发：进服时 · 收件人的权限变了（op / deop）· 有人存了盘或文件被改了之后发给每个人（{@code SettingsSync}）。
 *
 * <h2>❗密钥永不在这里</h2>
 * {@code values} 只含会同步的那几项（{@code ServerSettingsTable#synced}）；密钥只给「设了没有」（{@code secretsSet} 里是它的键名），
 * 界面上只显示「已设置」。现在一项密钥都没有；判据在 {@code SettingsProtocolTest}。
 *
 * @param canEdit    收件人能不能改：单人存档的主人 · 局域网房主 · 专用服务端上 2 级以上
 * @param values     键 → 值（字符串，按设置表的顺序）
 * @param secretsSet 已经设了值的密钥的<b>键名</b>（没有值）
 */
public record ServerSettingsS2C(boolean canEdit, Map<String, String> values, List<String> secretsSet)
        implements CustomPayload {

    /** 一项的键与值都不长；给个上限，免得一个坏包让对面分配一大块内存。 */
    static final int MAX_TEXT = 256;
    static final int MAX_ENTRIES = 128;

    public static final CustomPayload.Id<ServerSettingsS2C> ID =
            new CustomPayload.Id<>(Identifier.of(HeavySeasMod.MOD_ID, "server_settings"));

    /** 键 → 值（按写进去的顺序读回来）。存盘包也用这一个。 */
    static final PacketCodec<ByteBuf, Map<String, String>> STRING_MAP =
            PacketCodecs.<ByteBuf, String, String, Map<String, String>>map(LinkedHashMap::new,
                    PacketCodecs.string(MAX_TEXT), PacketCodecs.string(MAX_TEXT), MAX_ENTRIES);

    public static final PacketCodec<ByteBuf, ServerSettingsS2C> CODEC = PacketCodec.tuple(
            PacketCodecs.BOOL, ServerSettingsS2C::canEdit,
            STRING_MAP, ServerSettingsS2C::values,
            PacketCodecs.string(MAX_TEXT).collect(PacketCodecs.toList(MAX_ENTRIES)), ServerSettingsS2C::secretsSet,
            ServerSettingsS2C::new);

    public ServerSettingsS2C {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        secretsSet = List.copyOf(secretsSet);
    }

    @Override
    public CustomPayload.Id<ServerSettingsS2C> getId() {
        return ID;
    }
}
