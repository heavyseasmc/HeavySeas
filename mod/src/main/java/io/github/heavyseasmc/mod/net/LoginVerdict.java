package io.github.heavyseasmc.mod.net;

import net.minecraft.network.PacketByteBuf;

/**
 * 登录查询阶段对客户端那一句回话的判定（{@link ClockNetworking}）。
 */
public enum LoginVerdict {
    /** 客户端不认识这个频道：没装本模组，当旁人放行（决策 ⑫：不踢没装模组的旁人）。 */
    BYSTANDER,
    /** 装了本模组，协议版本对得上。 */
    COMPATIBLE,
    /** 装了本模组，协议版本对不上（或回话读不懂）：拒绝，并告诉他两端要一起更新。 */
    MISMATCH;

    /**
     * @param understood 客户端认不认识 {@link ExperienceProtocol#CHANNEL}（Fabric 登录查询的回执）
     * @param reply      认识时它回的那一句；不认识时不读
     */
    public static LoginVerdict of(boolean understood, PacketByteBuf reply) {
        if (!understood) {
            return BYSTANDER;       // ❗第一版在这里也拒：没装模组的人一律进不了服（2026-10-13 审查）
        }
        try {
            return reply.readVarInt() == ExperienceProtocol.VERSION && !reply.isReadable() ? COMPATIBLE : MISMATCH;
        } catch (RuntimeException malformed) {
            return MISMATCH;
        }
    }

    public boolean admitted() {
        return this != MISMATCH;
    }
}
