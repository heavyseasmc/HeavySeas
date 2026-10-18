package io.github.heavyseasmc.mod.net;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户端在设置菜单里存盘（ADR-0099 D6）：<b>只发改过的那几项</b>（键 → 新值，字符串）。
 *
 * <p>服务端不信它：再查一遍权限、整批核对（一项不合法整批拒、一个值都不动），过了才经 FCAP 改值存盘，
 * 然后把新快照发给每个人（{@code SettingsSync#onSave}）。界面在 cut 2，协议先在这里立好、测好。
 */
public record ServerSettingsSaveC2S(Map<String, String> changes) implements CustomPayload {

    public static final CustomPayload.Id<ServerSettingsSaveC2S> ID =
            new CustomPayload.Id<>(Identifier.of(HeavySeasMod.MOD_ID, "server_settings_save"));

    public static final PacketCodec<ByteBuf, ServerSettingsSaveC2S> CODEC = ServerSettingsS2C.STRING_MAP
            .xmap(ServerSettingsSaveC2S::new, ServerSettingsSaveC2S::changes);

    public ServerSettingsSaveC2S {
        changes = Collections.unmodifiableMap(new LinkedHashMap<>(changes));
    }

    @Override
    public CustomPayload.Id<ServerSettingsSaveC2S> getId() {
        return ID;
    }
}
