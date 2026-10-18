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
import java.util.Objects;

/**
 * 服务端设置的快照（ADR-0099 D6）：每一项此刻的值，加上收件人能不能改。
 *
 * <p>什么时候发：进服时 · 收件人的权限变了（op / deop）· 有人存了盘或文件被改了之后发给每个人（{@code SettingsSync}）·
 * 收件人发来的存盘包 / 密钥包被拒了（{@code rejection} 里是理由，只发给他）。
 *
 * <h2>❗密钥永不在这里</h2>
 * {@code values} 只含会同步的那几项（{@code ServerSettingsTable#synced}）；密钥只给「设了没有」（{@code secretsSet} 里是它的键名），
 * 界面上只显示「已设置」。判据在 {@code SettingsProtocolTest}。不能改设置的人收到的那一份里，接口地址也不给原值
 * （{@code ServerSettingsTable#EDITORS_ONLY}）。
 *
 * @param canEdit    收件人能不能改：单人存档的主人 · 局域网房主 · 专用服务端上 2 级以上
 * @param values     键 → 值（字符串，按设置表的顺序）
 * @param secretsSet 已经设了值的密钥的<b>键名</b>（没有值）
 * @param rejection  这一份是回给收件人的、他刚发来的那一包被拒了：理由（一行，服务端的原话，截短过）；不是回话是空串。
 *                   菜单据此显示「没存上」，不再把被拒的存盘说成「已保存」（审查 2026-10-07 U2）
 */
public record ServerSettingsS2C(boolean canEdit, Map<String, String> values, List<String> secretsSet, String rejection)
        implements CustomPayload {

    /** 键与密钥键名的上限（UTF-16 单元数）。给个上限，免得一个坏包让对面分配一大块内存。 */
    static final int MAX_TEXT = 256;
    /**
     * 值的上限（UTF-16 单元数）。菜单里文字项按码点数限长（接口地址 256 个字），BMP 以外的字一个占两个单元 ——
     * 原先值也用 256，装不下菜单收得下的最长值时编码就抛、客户端被踢（审查 2026-10-07 U2）。{@code SettingsProtocolTest} 拿最长的值核。
     */
    public static final int MAX_VALUE = 1024;
    /** 被拒的理由最长几个字（码点数，服务端截短；UTF-16 单元数不超过 {@link #MAX_VALUE}）。 */
    public static final int MAX_REJECTION = 200;
    static final int MAX_ENTRIES = 128;

    public static final CustomPayload.Id<ServerSettingsS2C> ID =
            new CustomPayload.Id<>(Identifier.of(HeavySeasMod.MOD_ID, "server_settings"));

    /** 键 → 值（按写进去的顺序读回来）。存盘包也用这一个。 */
    static final PacketCodec<ByteBuf, Map<String, String>> STRING_MAP =
            PacketCodecs.<ByteBuf, String, String, Map<String, String>>map(LinkedHashMap::new,
                    PacketCodecs.string(MAX_TEXT), PacketCodecs.string(MAX_VALUE), MAX_ENTRIES);

    public static final PacketCodec<ByteBuf, ServerSettingsS2C> CODEC = PacketCodec.tuple(
            PacketCodecs.BOOL, ServerSettingsS2C::canEdit,
            STRING_MAP, ServerSettingsS2C::values,
            PacketCodecs.string(MAX_TEXT).collect(PacketCodecs.toList(MAX_ENTRIES)), ServerSettingsS2C::secretsSet,
            PacketCodecs.string(MAX_VALUE), ServerSettingsS2C::rejection,
            ServerSettingsS2C::new);

    public ServerSettingsS2C {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        secretsSet = List.copyOf(secretsSet);
        rejection = Objects.requireNonNullElse(rejection, "");
    }

    /** 不是回话的那种（进服 · 权限变了 · 有人存了盘）。 */
    public ServerSettingsS2C(boolean canEdit, Map<String, String> values, List<String> secretsSet) {
        this(canEdit, values, secretsSet, "");
    }

    @Override
    public CustomPayload.Id<ServerSettingsS2C> getId() {
        return ID;
    }
}
