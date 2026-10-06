package io.github.heavyseasmc.mod.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.heavyseasmc.mod.HeavySeasMod;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 装修标注（2026-10-07 用户定的装修工作方式「游戏里标注」）：走船时看着哪儿不对，敲 {@code /hsnote <一句话>}，
 * 记下站的位置、朝向、准星指着的那一格（{@value #REACH} 格内的方块与它的状态）和这句话，再截一张不带界面的图 ——
 * 改的人照着坐标改，不用猜「前半有的窗户」是哪几扇。
 *
 * <p>只在测试客户端里开：JVM 参数 {@code -DhsDesignNotes=true}；玩家的客户端里没有这条指令。
 * 一条一行写进游戏目录 {@code heavyseas-notes/notes.jsonl}；截图在 {@code screenshots/hsnote-<序号>-<时刻>.png}。
 */
public final class DesignNotes {

    static final String PROPERTY = "hsDesignNotes";
    static final double REACH = 64.0;
    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** 等着截的那一张：聊天框收起之后先藏界面、隔两个 tick（中间至少画过一帧不带界面的）再截，截完把界面还原。 */
    private static String pendingShot;
    private static int pendingTicks = -1;
    private static boolean hudWasHidden;

    private DesignNotes() {
    }

    public static void register() {
        if (!Boolean.getBoolean(PROPERTY)) {
            return;
        }
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
                ClientCommandManager.literal("hsnote").then(ClientCommandManager.argument("text", StringArgumentType.greedyString())
                        .executes(ctx -> note(ctx.getSource(), StringArgumentType.getString(ctx, "text"))))));
        ClientTickEvents.END_CLIENT_TICK.register(DesignNotes::tick);
        LOGGER.info("装修标注：/hsnote 已开（-D{}=true）", PROPERTY);
    }

    private static int note(FabricClientCommandSource source, String text) {
        MinecraftClient client = source.getClient();
        Entity eye = client.getCameraEntity() != null ? client.getCameraEntity() : source.getPlayer();
        Path dir = client.runDirectory.toPath().resolve("heavyseas-notes");
        Path file = dir.resolve("notes.jsonl");
        try {
            Files.createDirectories(dir);
            int n = 1;
            if (Files.exists(file)) {
                try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
                    n += (int) lines.count();
                }
            }
            String shot = "hsnote-" + n + "-" + LocalDateTime.now().format(STAMP) + ".png";

            JsonObject o = new JsonObject();
            o.addProperty("n", n);
            o.addProperty("time", LocalDateTime.now().toString());
            o.addProperty("text", text);
            o.addProperty("dimension", source.getWorld().getRegistryKey().getValue().toString());
            Vec3d p = eye.getPos();
            o.add("pos", vec(p.x, p.y, p.z));
            o.addProperty("yaw", eye.getYaw());
            o.addProperty("pitch", eye.getPitch());
            HitResult hit = eye.raycast(REACH, 1.0f, false);
            String looked = "nothing within " + (int) REACH;
            if (hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK) {
                BlockPos q = b.getBlockPos();
                BlockState state = source.getWorld().getBlockState(q);
                JsonObject t = new JsonObject();
                t.add("pos", vec(q.getX(), q.getY(), q.getZ()));
                t.addProperty("face", b.getSide().asString());
                t.addProperty("block", BlockArgumentParser.stringifyBlockState(state));
                t.addProperty("distance", Math.round(hit.getPos().distanceTo(eye.getEyePos()) * 10) / 10.0);
                o.add("target", t);
                looked = q.toShortString() + " " + BlockArgumentParser.stringifyBlockState(state);
            }
            o.addProperty("screenshot", "screenshots/" + shot);
            Files.writeString(file, o + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

            pendingShot = shot;
            pendingTicks = -1;
            LOGGER.info("装修标注 #{}：{} · 站在 ({}, {}, {}) · 看着 {}", n, text,
                    String.format("%.1f", p.x), String.format("%.1f", p.y), String.format("%.1f", p.z), looked);
            source.sendFeedback(Text.literal("hsnote #" + n + " saved: " + text));
            return n;
        } catch (IOException e) {
            LOGGER.error("装修标注写不进 {}", file, e);
            source.sendError(Text.literal("hsnote failed: " + e.getMessage()));
            return 0;
        }
    }

    private static void tick(MinecraftClient client) {
        if (pendingShot == null || client.currentScreen != null) {
            return;                                         // 聊天框还开着：等它收起，不然截到的是聊天框
        }
        if (pendingTicks < 0) {
            hudWasHidden = client.options.hudHidden;
            client.options.hudHidden = true;
            pendingTicks = 2;
            return;
        }
        if (--pendingTicks > 0) {
            return;
        }
        ScreenshotRecorder.saveScreenshot(client.runDirectory, pendingShot, client.getFramebuffer(), msg -> { });
        LOGGER.info("装修标注：截图 screenshots/{}", pendingShot);
        client.options.hudHidden = hudWasHidden;
        pendingShot = null;
        pendingTicks = -1;
    }

    private static JsonArray vec(double x, double y, double z) {
        JsonArray a = new JsonArray();
        a.add(Math.round(x * 100) / 100.0);
        a.add(Math.round(y * 100) / 100.0);
        a.add(Math.round(z * 100) / 100.0);
        return a;
    }
}
