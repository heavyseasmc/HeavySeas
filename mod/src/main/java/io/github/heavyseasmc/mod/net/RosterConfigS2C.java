package io.github.heavyseasmc.mod.net;

import io.github.heavyseasmc.engine.data.RosterData;
import io.github.heavyseasmc.engine.model.CharacterId;
import io.github.heavyseasmc.mod.HeavySeasMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.List;
import java.util.Objects;

/** 大厅船上玩家打开阵容面板所需的公开目录与三套预设。 */
public record RosterConfigS2C(long anchor, int players, List<String> characters,
                              List<String> preset6, List<String> preset7, List<String> preset8)
        implements CustomPayload {

    public static final Id<RosterConfigS2C> ID =
            new Id<>(Identifier.of(HeavySeasMod.MOD_ID, "roster_config"));
    private static final PacketCodec<ByteBuf, List<String>> STRINGS =
            PacketCodecs.STRING.collect(PacketCodecs.toList(8));
    public static final PacketCodec<RegistryByteBuf, RosterConfigS2C> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_LONG, RosterConfigS2C::anchor,
            PacketCodecs.VAR_INT, RosterConfigS2C::players,
            STRINGS, RosterConfigS2C::characters,
            STRINGS, RosterConfigS2C::preset6,
            STRINGS, RosterConfigS2C::preset7,
            STRINGS, RosterConfigS2C::preset8,
            RosterConfigS2C::new);

    public RosterConfigS2C {
        characters = List.copyOf(characters);
        preset6 = List.copyOf(preset6);
        preset7 = List.copyOf(preset7);
        preset8 = List.copyOf(preset8);
    }

    /**
     * 从正式角色数据构造大厅面板包。大厅交互与开发验收共用这一条，避免两边字段漂移。
     */
    public static RosterConfigS2C from(long anchor, int players, RosterData roster) {
        if (players < 6 || players > 8) {
            throw new IllegalArgumentException("阵容面板只支持 6–8 人，实际是 " + players);
        }
        Objects.requireNonNull(roster, "roster");
        return new RosterConfigS2C(anchor, players,
                roster.characters().stream().map(survivor -> survivor.id().value()).toList(),
                ids(roster.presets().get(6)), ids(roster.presets().get(7)), ids(roster.presets().get(8)));
    }

    private static List<String> ids(List<CharacterId> ids) {
        if (ids == null) {
            throw new IllegalArgumentException("角色数据缺少 6、7 或 8 人预设");
        }
        return ids.stream().map(CharacterId::value).toList();
    }

    @Override
    public Id<RosterConfigS2C> getId() {
        return ID;
    }
}
