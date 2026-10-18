package io.github.heavyseasmc.mod.net;

import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.TranslatableTextContent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 决策 ⑫：不踢没装模组的旁人。登录查询阶段只拒绝「装了、但协议版本对不上」的客户端，并给出明确提示。
 *
 * <p>2026-10-13 审查：版本握手第一版对不认识频道的客户端（没装本模组）也断开连接 —— 旁人从此进不了服。
 */
final class LoginVerdictTest {

    @Test
    @DisplayName("没装本模组（不认识频道）→ 当旁人放行")
    void clientWithoutTheModIsAdmittedAsBystander() {
        LoginVerdict verdict = LoginVerdict.of(false, reply());
        assertEquals(LoginVerdict.BYSTANDER, verdict, "没装模组的旁人被拒之门外（决策 ⑫）");
        assertTrue(verdict.admitted());
    }

    @Test
    @DisplayName("装了、版本对 → 放行")
    void matchingVersionIsAdmitted() {
        LoginVerdict verdict = LoginVerdict.of(true, reply(ExperienceProtocol.VERSION));
        assertEquals(LoginVerdict.COMPATIBLE, verdict);
        assertTrue(verdict.admitted());
    }

    @Test
    @DisplayName("装了、版本不对 / 回话读不懂 / 回话多出字节 → 拒绝")
    void mismatchedOrMalformedReplyIsRejected() {
        assertEquals(LoginVerdict.MISMATCH, LoginVerdict.of(true, reply(ExperienceProtocol.VERSION - 1)));
        assertEquals(LoginVerdict.MISMATCH, LoginVerdict.of(true, reply(-1)), "客户端自认不兼容时回 -1");
        assertEquals(LoginVerdict.MISMATCH, LoginVerdict.of(true, reply()), "空回话该算读不懂");
        assertEquals(LoginVerdict.MISMATCH, LoginVerdict.of(true, reply(ExperienceProtocol.VERSION, 7)), "多出来的字节");
        assertFalse(LoginVerdict.MISMATCH.admitted());
    }

    @Test
    @DisplayName("拒绝时的提示：有自己的翻译键，且带着没装语言文件时也读得懂的英文兜底")
    void rejectionCarriesAnExplicitMessage() {
        var content = assertInstanceOf(TranslatableTextContent.class, ExperienceProtocol.mismatch().getContent());
        assertEquals("heavyseas.protocol.mismatch", content.getKey());
        assertTrue(content.getFallback() != null && content.getFallback().contains("versions do not match"),
                "兜底文案缺失：客户端那一侧的语言文件版本不一时，他只看得到一个键名");
    }

    private static PacketByteBuf reply(int... varInts) {
        PacketByteBuf buf = new PacketByteBuf(Unpooled.buffer());
        for (int v : varInts) {
            buf.writeVarInt(v);
        }
        return buf;
    }
}
