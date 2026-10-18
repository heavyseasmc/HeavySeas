package io.github.heavyseasmc.mod.game;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.state.GameComponent;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 调试指令的留痕（ADR-0060 D3）：<b>用过就看得见</b>。
 *
 * <p>改了局面的那一条留三处：① 全船右栏一行「（调试）谁：做了什么」—— 与天候播报同一条通道，谁都看得见；
 * ② 记进这一局的调试记录（计分面板那枚章的数、{@code /seas debug log} 列的就是它）；
 * ③ 服务器日志一行 {@code 调试：<谁> <指令原文>}，与语言无关，脚本按它认。
 * 只读的（查看 · 导出）只记 ② 与 ③：不改局面，就不往全船的右栏里塞话。
 *
 * <p>❗所有调试指令的留痕都从这里走，不各写一份：漏写一份的表现是「那一条用了没人知道」，而那正是要防的事。
 */
public final class DebugTrace {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private DebugTrace() {
    }

    /** 改了这一局的局面（或这一局里世界上的东西）。 */
    public static void changed(ServerWorld world, GameComponent component, String who, String command, Text what) {
        int turn = component.session().map(s -> s.state().turn()).orElse(0);
        component.recordDebug(new GameComponent.DebugEntry(who, turn, command, true));
        LOGGER.info("调试：{} {}", who, command);
        GameFlow.broadcast(world, Text.translatable("heavyseas.debug.trace", Text.literal(who), what)
                .formatted(Formatting.LIGHT_PURPLE));
    }

    /** 开局那一刻生效的设定（下一局的种子、第一天的天候、还没还原的天色）：记成这一局的改动，日志注明是开局时生效。 */
    static void atStart(ServerWorld world, GameComponent component, String who, String command, Text what) {
        component.recordDebug(new GameComponent.DebugEntry(who, 1, command, true));
        LOGGER.info("调试：{} {}（开局时生效）", who, command);
        GameFlow.broadcast(world, Text.translatable("heavyseas.debug.trace", Text.literal(who), what)
                .formatted(Formatting.LIGHT_PURPLE));
    }

    /** 只读：查看 · 导出。有对局时记进这一局（不算改动），日志照打。 */
    public static void readOnly(GameComponent component, String who, String command) {
        component.session().ifPresent(s ->
                component.recordDebug(new GameComponent.DebugEntry(who, s.state().turn(), command, false)));
        LOGGER.info("调试（只读）：{} {}", who, command);
    }

    /** 没有对局可记（给下一局定东西、局外改天色）：只打日志。 */
    public static void outsideGame(String who, String command) {
        LOGGER.info("调试：{} {}", who, command);
    }
}
