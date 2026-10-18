package io.github.heavyseasmc.mod.command;

import net.minecraft.server.command.CommandOutput;
import net.minecraft.text.Text;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 控制台与 RCON 收到的回话不带格式记号（{@link Replies}，2026-10-13 第二轮验收：RCON 回话里夹着 §l）。 */
final class RepliesTest {

    @Test
    @DisplayName("写到控制台 / RCON 的那一份：动词记号剥掉、字一个不少；格式串里的参数照样展开")
    void consoleGetsPlainText() {
        Text note = Text.translatableWithFallback("heavyseas.replies_test.took", "%s §ltook§r %s",
                Text.literal("Al"), Text.literal("Bo"));
        assertTrue(note.getString().contains("§l"), "正向对照：样本里真有记号");
        CommandOutput console = CommandOutput.DUMMY;
        assertFalse(Replies.toPlayer(console));
        Text out = Replies.forOutput(console, note);
        assertEquals("Al took Bo", out.getString());
    }
}
