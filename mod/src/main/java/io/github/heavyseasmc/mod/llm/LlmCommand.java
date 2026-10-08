package io.github.heavyseasmc.mod.llm;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code /seasllm status | reload | probe} —— 大模型替身接入层的开发指令（ADR-0096 §3 · A）。只给管理员：
 * 整棵树每一个节点都自己要 {@link #PERMISSION} 级（与 {@code /seas} 同一条：根节点的门哪天被改松了，下面每一层照样挡着）。
 *
 * <ul>
 *   <li>{@code status}：开没开、为什么关着、模型、地址（只到主机:端口）、密钥有没有（不显示）、这一刻在途与排队、各条路走了几次。</li>
 *   <li>{@code reload}：按此刻的设置（设置菜单的「大模型」一组）重起接入层。还没收场的决定收成 {@code SHUTDOWN}。
 *       平时用不着：设置菜单存了就会自己换。</li>
 *   <li>{@code probe}：发一次固定的假决定（{@link ProbeDecision}，五个行动选项），回报选了哪项、耗时、token。</li>
 * </ul>
 *
 * <p>文案直接写字面量，不进 lang：这是给服主看的开发工具，不是给玩家的界面。
 */
public final class LlmCommand {

    public static final int PERMISSION = 2;

    /** 探针给模型多少时间：与游戏里大多数窗口一样长。 */
    static final long PROBE_WINDOW_SECONDS = 20;

    private LlmCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("seasllm")
                .requires(source -> source.hasPermissionLevel(PERMISSION))
                .then(CommandManager.literal("status")
                        .requires(source -> source.hasPermissionLevel(PERMISSION))
                        .executes(LlmCommand::status))
                .then(CommandManager.literal("reload")
                        .requires(source -> source.hasPermissionLevel(PERMISSION))
                        .executes(LlmCommand::reload))
                .then(CommandManager.literal("probe")
                        .requires(source -> source.hasPermissionLevel(PERMISSION))
                        .executes(LlmCommand::probe)));
    }

    private static int status(CommandContext<ServerCommandSource> context) {
        statusLines(LlmHooks.service(), LlmHooks.legacyConfigFile())
                .forEach(line -> context.getSource().sendFeedback(() -> Text.literal(line), false));
        return 1;
    }

    /** {@code status} 的那几行（单测直接调：密钥只说取自哪里，值不出现）。 */
    static List<String> statusLines(LlmService s, Path legacy) {
        List<String> lines = new ArrayList<>();
        lines.add("大模型替身：" + (s.enabled() ? "开着" : "关着（" + s.disabledWhy() + "）"));
        lines.add("设置：设置菜单的「大模型」一组（heavyseas-server.toml 的 [llm] 段；密钥另存）· 密钥取自：" + LlmHooks.keySource());
        if (LlmHooks.keyWithheld() != null) {
            lines.add("密钥没带：" + LlmHooks.keyWithheld());     // 审查 2026-10-07 L1：密钥只发往设它时的那个地址
        }
        if (Files.exists(legacy)) {
            lines.add("旧的 " + legacy + " 还在：下次起服迁进设置");
        }
        if (s.enabled()) {
            s.config().describe().forEach((k, v) -> lines.add("  " + k + "：" + v
                    + (k.equals("密钥") && s.config().hasKey() ? "（来自" + LlmHooks.keySource() + "）" : "")));
            lines.add("服务商：" + s.health());
        }
        lines.add("这一刻：在途 " + s.inFlight() + " · 排队 " + s.queued() + " · 未收场 " + s.pending());
        lines.add("这个服务起来以后：" + s.tally());
        lines.add("上次读设置：" + LlmHooks.lastLoad());
        return lines;
    }

    private static int reload(CommandContext<ServerCommandSource> context) {
        String result = LlmHooks.reload();
        context.getSource().sendFeedback(() -> Text.literal("按设置重起大模型接入层：" + result), true);
        return 1;
    }

    private static int probe(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        LlmService service = LlmHooks.service();
        ChoiceRequest request = ProbeDecision.request(service.language(),
                Instant.now().plusSeconds(PROBE_WINDOW_SECONDS));
        source.sendFeedback(() -> Text.literal("大模型探针：发出一次假决定（行动 · " + request.options().size() + " 个选项 · "
                + PROBE_WINDOW_SECONDS + " 秒截止）……"), false);
        MinecraftServer server = source.getServer();
        // 结果在接入层自己的线程上回来：回主线程再说话
        service.choose(request).thenAccept(outcome -> {
            Runnable report = () -> source.sendFeedback(() -> Text.literal(describe(request, outcome)), false);
            if (server != null) {
                server.execute(report);
            } else {
                report.run();
            }
        });
        return 1;
    }

    static String describe(ChoiceRequest request, ChoiceOutcome o) {
        String path = o.chosen()
                ? "选了第 " + (o.index() + 1) + " 项「" + request.options().get(o.index()) + "」"
                : "退路 " + o.fallback() + "（" + o.detail() + "）";
        return "大模型探针：" + path + " · 经过 " + o.trailText() + " · " + o.latencyMs() + " ms · token " + o.usage()
                + (o.answer() == null ? "" : " · 回答「" + o.answer() + "」");
    }
}
