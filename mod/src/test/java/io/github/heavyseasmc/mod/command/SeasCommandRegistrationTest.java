package io.github.heavyseasmc.mod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeasCommandRegistrationTest {

    private static ServerCommandSource source(int level) {
        return new ServerCommandSource(CommandOutput.DUMMY, Vec3d.ZERO, Vec2f.ZERO,
                null, level, "test", Text.literal("test"), null, null);
    }

    private static CommandNode<ServerCommandSource> seas() {
        CommandDispatcher<ServerCommandSource> dispatcher = new CommandDispatcher<>();
        SeasCommand.register(dispatcher);
        return dispatcher.getRoot().getChild("seas");
    }

    @Test
    void ordinaryPlayersCannotSeeOrExecuteTheCommandTree() {
        var root = seas();
        for (int level = 0; level <= 4; level++) {
            if (level < 2) {
                assertFalse(root.canUse(source(level)));
            } else {
                assertTrue(root.canUse(source(level)));
            }
        }
    }

    @Test
    @DisplayName("生产树里有 debug、没有 dev（ADR-0060 D2：/seas dev 并进了 /seas debug，生产服也注册、靠权限挡）")
    void productionTreeHasDebugAndNoDev() {
        var root = seas();
        assertNull(root.getChild("dev"), "/seas dev 已改名为 /seas debug —— 旧名还在说明有一处没改完");
        var debug = root.getChild("debug");
        assertNotNull(debug);
        for (String child : List.of("weather", "next", "timer", "inspect", "dump", "gulls", "deal", "skiff", "sky",
                "log", "ui")) {
            assertNotNull(debug.getChild(child), "debug 下少了 " + child);
        }
        assertNotNull(debug.getChild("weather").getChild("now"), "原来的 dev weather 搬到 weather now");
        assertNotNull(debug.getChild("ui").getChild("roster"), "原来的 dev roster 搬到 ui roster");
        assertNotNull(root.getChild("grant"), "grant 原样留着（D6：十几支脚本在用）");
    }

    /**
     * 判据本体：子树里<b>每一个节点自己</b>都要拒绝 0–1 级、放行 2 级。返回不合格的那几个（路径 + 哪一条）。
     *
     * <p>不只看根：Brigadier 里子节点只经父节点可达，所以根节点那一道挡着时，子节点漏写 {@code requires}
     * 在今天不会出事 —— 但哪天根的门被改松了，漏写的那几条就整个敞开，而且什么都不会报错。
     */
    static List<String> unguarded(CommandNode<ServerCommandSource> node, String path, int[] checked) {
        List<String> out = new ArrayList<>();
        checked[0]++;
        for (int level = 0; level < DebugCommand.GAME; level++) {
            if (node.canUse(source(level))) {
                out.add(path + "：" + level + " 级也能用");
            }
        }
        if (!node.canUse(source(DebugCommand.GAME))) {
            out.add(path + "：" + DebugCommand.GAME + " 级反而用不了");
        }
        for (CommandNode<ServerCommandSource> child : node.getChildren()) {
            String name = child instanceof ArgumentCommandNode<?, ?> ? "<" + child.getName() + ">" : child.getName();
            out.addAll(unguarded(child, path + " " + name, checked));
        }
        return out;
    }

    @Test
    @DisplayName("debug 子树里每一个节点（字面量与参数）都自己要 2 级")
    void everyDebugNodeGuardsItself() {
        int[] checked = {0};
        List<String> bad = unguarded(seas().getChild("debug"), "debug", checked);
        // 正向对照：一个节点都没走到时，「全都挡着」与「没在核」长得一样
        assertTrue(checked[0] >= 40, "只核了 " + checked[0] + " 个节点 —— 没在核，不是都挡着");
        System.out.println("debug 子树：核了 " + checked[0] + " 个节点");
        assertEquals(List.of(), bad, String.join("\n", bad));
    }

    @Test
    @DisplayName("红测：判据点名漏写 requires 的那一个节点，而且只点它")
    void judgeNamesTheUnguardedNode() {
        LiteralArgumentBuilder<ServerCommandSource> tree = CommandManager.<ServerCommandSource>literal("debug")
                .requires(s -> s.hasPermissionLevel(2))
                .then(CommandManager.<ServerCommandSource>literal("ok").requires(s -> s.hasPermissionLevel(2)))
                .then(CommandManager.<ServerCommandSource>literal("leak"));
        int[] checked = {0};
        List<String> bad = unguarded(tree.build(), "debug", checked);
        assertEquals(3, checked[0]);
        assertEquals(List.of("debug leak：0 级也能用", "debug leak：1 级也能用"), bad);
    }

    @Test
    @DisplayName("D4：这一局里的人不能看底牌；控制台 / RCON 与局外的人可以")
    void playersInTheGameCannotPeek() {
        UUID inside = UUID.randomUUID();
        UUID outside = UUID.randomUUID();
        assertTrue(DebugCommand.mayPeek(null, id -> true), "控制台 · RCON · 命令方块没有玩家：可以看");
        assertFalse(DebugCommand.mayPeek(inside, inside::equals), "这一局里的人：不可以");
        assertTrue(DebugCommand.mayPeek(outside, inside::equals), "不在这一局里的管理员：可以");
    }
}
