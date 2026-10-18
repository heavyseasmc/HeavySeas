package io.github.heavyseasmc.mod.net;

import io.github.heavyseasmc.mod.HeavySeasMod;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * 口渴窗口里，旁人替当前角色打水。
 *
 * <p>界面一次只打 1 张，但仍把数量放进包里：服务端只接受 {@code 1}，让坏包可以被静默拒绝，
 * 也给以后确实需要批量选择时留出兼容位置。
 */
public record WaterDonationC2S(int waters) implements CustomPayload {

    public static final CustomPayload.Id<WaterDonationC2S> ID =
            new CustomPayload.Id<>(Identifier.of(HeavySeasMod.MOD_ID, "water_donation"));

    public static final PacketCodec<RegistryByteBuf, WaterDonationC2S> CODEC = PacketCodec.tuple(
            PacketCodecs.VAR_INT, WaterDonationC2S::waters,
            WaterDonationC2S::new);

    @Override
    public CustomPayload.Id<WaterDonationC2S> getId() {
        return ID;
    }
}
