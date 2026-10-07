package io.github.heavyseasmc.mod.llm;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code /seasllm}：只给管理员，每一个节点自己都要 2 级（与 {@code /seas} 同一条规矩）。
 */
class LlmCommandTest {

    private static ServerCommandSource source(int level) {
        return new ServerCommandSource(CommandOutput.DUMMY, Vec3d.ZERO, Vec2f.ZERO,
                null, level, "test", Text.literal("test"), null, null);
    }

    @Test
    @DisplayName("根与 status · reload · probe 每一个节点：1 级用不了，2 级起能用")
    void everyNodeNeedsLevelTwo() {
        CommandDispatcher<ServerCommandSource> dispatcher = new CommandDispatcher<>();
        LlmCommand.register(dispatcher);
        CommandNode<ServerCommandSource> root = dispatcher.getRoot().getChild("seasllm");
        assertNotNull(root);
        List<CommandNode<ServerCommandSource>> nodes = new java.util.ArrayList<>(List.of(root));
        for (String child : List.of("status", "reload", "probe")) {
            CommandNode<ServerCommandSource> node = root.getChild(child);
            assertNotNull(node, child);
            nodes.add(node);
        }
        assertEquals(3, root.getChildren().size(), "多了没登记进这条测试的子指令");
        for (CommandNode<ServerCommandSource> node : nodes) {
            for (int level = 0; level <= 4; level++) {
                assertEquals(level >= LlmCommand.PERMISSION, node.canUse(source(level)), node.getName() + " @ " + level);
            }
        }
    }

    @Test
    @DisplayName("探针的回报：选中时写第几项与选项名，退路时写原因 —— 都带耗时、token、尝试次数")
    void probeReport() {
        ChoiceRequest request = ProbeDecision.request("zh_cn", Instant.now().plusSeconds(20));
        assertEquals(5, request.options().size());
        assertNull(request.problem());
        String chosen = LlmCommand.describe(request, new ChoiceOutcome(2, null, "第 3 项", "3", 1234,
                new ChoiceOutcome.Usage(800, 3, 0), List.of("503", "ok")));
        assertTrue(chosen.contains("第 3 项「抢」") && chosen.contains("1234 ms") && chosen.contains("800+3")
                && chosen.contains("经过 503→ok"), chosen);
        String fallback = LlmCommand.describe(request, new ChoiceOutcome(-1, ChoiceOutcome.Fallback.TIMEOUT, "等不到",
                null, 19000, ChoiceOutcome.Usage.UNKNOWN, List.of("timeout", "cut")));
        assertTrue(fallback.contains("退路 TIMEOUT（等不到）") && fallback.contains("token -")
                && fallback.contains("经过 timeout→cut"), fallback);
        assertNull(ProbeDecision.request("en_us", Instant.now().plusSeconds(20)).problem());
    }
}
