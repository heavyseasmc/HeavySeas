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
    /**
     * 角色目录与目录包（{@link CatalogS2C#MAX_CHARACTERS}）同一个上限；预设最多 8 人（阵容只有 6–8 人）。
     *
     * <p>❗审查 2026-10-07 U8：原先角色目录也按 8 个封顶 —— 数据包里的角色超过 8 个（数据那一侧不限个数，只限选出来的 6–8 人），
     * 坐在演习艇里打开阵容面板的人在编码那一刻被踢下线。
     */
    static final int MAX_PRESET = 8;
    private static final PacketCodec<ByteBuf, List<String>> CATALOG =
            PacketCodecs.STRING.collect(PacketCodecs.toList(CatalogS2C.MAX_CHARACTERS));
    private static final PacketCodec<ByteBuf, List<String>> PRESET =
            PacketCodecs.STRING.collect(PacketCodecs.toList(MAX_PRESET));
    public static final PacketCodec<RegistryByteBuf, RosterConfigS2C> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_LONG, RosterConfigS2C::anchor,
            PacketCodecs.VAR_INT, RosterConfigS2C::players,
            CATALOG, RosterConfigS2C::characters,
            PRESET, RosterConfigS2C::preset6,
            PRESET, RosterConfigS2C::preset7,
            PRESET, RosterConfigS2C::preset8,
            RosterConfigS2C::new);

    /** 超上限在造包这一刻就拒（与 {@link CatalogS2C} 同一个理由：编码是异步的，造好的包编码失败接不住、会踢人）。 */
    public RosterConfigS2C {
        characters = List.copyOf(characters);
        preset6 = List.copyOf(preset6);
        preset7 = List.copyOf(preset7);
        preset8 = List.copyOf(preset8);
        CatalogS2C.requireAtMost("角色", characters.size(), CatalogS2C.MAX_CHARACTERS);
        CatalogS2C.requireAtMost("预设角色", Math.max(preset6.size(), Math.max(preset7.size(), preset8.size())), MAX_PRESET);
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
