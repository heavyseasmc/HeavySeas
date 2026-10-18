package io.github.heavyseasmc.mod.net;

import io.github.heavyseasmc.mod.HeavySeasMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.UUID;

/**
 * 指定模式里「看着谁」的那一下（ADR-0095 F2）：客户端按准星方向挑出夹角最小的那个人，右键时把那具身体的 UUID 发来。
 *
 * <p>为什么不再靠 Minecraft 的右键实体：大家在艇上坐成一排，准星射线先打到中间那个人，
 * 交互距离加多长都点不到后面的（用户 2026-10-07：「实际上手长 16 也点不到，因为大家都是坐成一排的」）。
 * 仍然是「回到世界里转头看着那个人」（决策 ⑦），只是前面坐着的人不再碍事。
 *
 * <p>服务端照样只认举着拳头的本人、只认船上的人（{@code DesignationPhase#onDesignate}）；UUID 是那具身体的
 * （真人是玩家实体、替身是人形），服务端按身体认角色，与右键实体那条路同一份判据。
 */
public record DesignateC2S(UUID body) implements CustomPayload {

    public static final CustomPayload.Id<DesignateC2S> ID =
            new CustomPayload.Id<>(Identifier.of(HeavySeasMod.MOD_ID, "designate"));

    public static final PacketCodec<RegistryByteBuf, DesignateC2S> CODEC = PacketCodec.tuple(
            net.minecraft.util.Uuids.PACKET_CODEC, DesignateC2S::body,
            DesignateC2S::new);

    @Override
    public CustomPayload.Id<DesignateC2S> getId() {
        return ID;
    }
}
