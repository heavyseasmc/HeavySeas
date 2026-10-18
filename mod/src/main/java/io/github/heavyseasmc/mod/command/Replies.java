package io.github.heavyseasmc.mod.command;

import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.function.Supplier;

/**
 * 指令的回话：发给玩家的照原样（可翻译文本，客户端按自己的语言排、{@code §l} 动词照样加粗）；
 * 发给服务端控制台、RCON、命令方块的换成剥掉格式记号的纯文本（2026-10-13 第二轮验收：RCON 回话里夹着 {@code §l}）。
 *
 * <p>❗认的是<b>回话写到哪</b>（{@link ServerCommandSource#output}，访问放宽见 {@code heavyseas.accesswidener}），
 * 不是「谁在执行」：RCON 上 {@code execute as 某玩家 run seas …} 的执行者是玩家，回话照样落在 RCON 那一头。
 * 航海日志的动词记号（{@code §l…§r}）写在 lang 的格式串里，服务端把可翻译文本转成字符串时原样带着 —— 控制台与 RCON 只认字符串。
 */
public final class Replies {

    private Replies() {
    }

    /** 回话写到的那一头是不是玩家（玩家收可翻译文本，其余收纯文本）。 */
    static boolean toPlayer(ServerCommandSource source) {
        return toPlayer(source.output);
    }

    static boolean toPlayer(CommandOutput output) {
        return output instanceof ServerPlayerEntity;
    }

    /** 给这一头的那一份：玩家原样；其余按服务端的语言转成字符串、剥掉格式记号。 */
    static Text forOutput(CommandOutput output, Text text) {
        return toPlayer(output) ? text : Text.literal(plain(text));
    }

    /** 转成字符串、剥掉 {@code §} 格式记号。 */
    static String plain(Text text) {
        String s = Formatting.strip(text.getString());
        return s == null ? "" : s;
    }

    /** 代替 {@link ServerCommandSource#sendFeedback}。 */
    public static void feedback(ServerCommandSource source, Supplier<Text> text, boolean broadcastToOps) {
        source.sendFeedback(() -> forOutput(source.output, text.get()), broadcastToOps);
    }

    /** 代替 {@link ServerCommandSource#sendError}。 */
    public static void error(ServerCommandSource source, Text text) {
        source.sendError(forOutput(source.output, text));
    }
}
