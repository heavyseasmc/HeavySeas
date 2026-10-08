package io.github.heavyseasmc.mod.world;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.data.FogTable;
import io.github.heavyseasmc.mod.net.SkyS2C;
import io.github.heavyseasmc.mod.state.GameComponent;
import io.github.heavyseasmc.mod.state.GameComponents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 每个人的屏幕各自画天色（ADR-0054 §9.8 D12 第 4 条 (a)，C2 实跑）。
 *
 * <p>同一个雾海维度里分两种人：<b>这一局里的人</b>（坐着的、落海旁观的 —— {@link GameComponent#belongsToActiveVoyage}）
 * 看当日天候那一行的时刻与雨；<b>不在这一局里的人</b>（北辰号上等着的）看 1912-04-14 的夜。别的维度不接管。
 * 服务端只决定「给谁看什么」，每 20 tick 核一次、变了才发；画由客户端按 {@link SkyS2C} 改它读到的时刻与雨。
 */
public final class PlayerSky {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    /** 北辰号上永远是那一夜：午夜，晴，没有雨（那一夜海面平静，故事里写的就是这样）。 */
    public static final long LINER_NIGHT = 18_000L;

    private static final int INTERVAL = 20;

    /** 上一次发给每个人的那一包；对上了就不再发。 */
    private static final Map<UUID, SkyS2C> SENT = new HashMap<>();

    /**
     * 调试指定的天色（ADR-0060，{@code /seas debug sky time | rain}）：在雾海里的人一律改看这个时刻 / 这场雨，
     * 直到 {@code /seas debug sky reset}。只改「给谁看什么」，不碰任何世界的钟与雨。
     *
     * @param time    时刻（0–23999）；{@code null} = 不指定，照当日天候
     * @param rain    雨（0–1）；{@code null} = 不指定
     * @param who     谁定的（开局时这一条还在，就记进那一局）
     * @param command 最后一条指令原文
     */
    public record Forced(Long time, Float rain, String who, String command) {
    }

    private static Forced forced;

    private PlayerSky() {
    }

    public static synchronized Optional<Forced> forced() {
        return Optional.ofNullable(forced);
    }

    public static synchronized void forceTime(long time, String who, String command) {
        forced = new Forced(time, forced == null ? null : forced.rain(), who, command);
        LOGGER.info("天色：调试指定时刻 {}（在雾海里的人都看这一刻，直到 sky reset）", time);
    }

    public static synchronized void forceRain(float rain, String who, String command) {
        forced = new Forced(forced == null ? null : forced.time(), rain, who, command);
        LOGGER.info("天色：调试指定雨 {}（在雾海里的人都看这一场，直到 sky reset）", rain);
    }

    /** @return 原先有没有指定（没有就什么也没变） */
    public static synchronized boolean resetForced() {
        boolean had = forced != null;
        forced = null;
        if (had) {
            LOGGER.info("天色：调试指定已还原");
        }
        return had;
    }

    public static void tick(MinecraftServer server) {
        if (server.getTicks() % INTERVAL != 0) {
            return;
        }
        ServerWorld sea = MistSea.world(server);
        GameComponent component = sea == null ? null : GameComponents.of(sea);
        Forced override = forced().orElse(null);
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            boolean inSea = sea != null && player.getServerWorld() == sea;
            boolean inVoyage = inSea && component.belongsToActiveVoyage(player.getUuid());
            FogTable.Entry today = inVoyage ? today(component) : null;
            SkyS2C want = Rules.desired(inSea ? sea.getRegistryKey().getValue().toString() : null, inVoyage, today,
                    override == null ? null : override.time(), override == null ? null : override.rain());
            if (!want.equals(SENT.get(player.getUuid()))) {
                SENT.put(player.getUuid(), want);
                ServerPlayNetworking.send(player, want);
                LOGGER.info("天色：{} → {}", player.getGameProfile().getName(), describe(want, inVoyage, override != null));
            }
        }
    }

    /** 掉线就忘掉：重连时当成第一次，重新发。 */
    public static void forget(ServerPlayerEntity player) {
        SENT.remove(player.getUuid());
    }

    private static FogTable.Entry today(GameComponent component) {
        if (component.session().isEmpty() || component.layoutId().isEmpty()) {
            return null;
        }
        String weather = component.requireSession().currentWeather().map(card -> card.id()).orElse("");
        return component.fogFor(weather);   // 开局快照（审查 2026-10-07 C6）：/reload 拿掉布局，这里原先每秒抛一次
    }

    private static String describe(SkyS2C sky, boolean inVoyage, boolean forced) {
        if (!sky.active()) {
            return "不接管";
        }
        return "%s · %s · 时刻 %d · 雨 %.1f · 雷 %.1f".formatted(sky.dimension(), inVoyage ? "对局" : "北辰号之夜",
                sky.timeOfDay(), sky.rain(), sky.thunder()) + (forced ? " · 调试指定" : "");
    }

    /** 纯规则：谁看什么。单测在这里。 */
    public static final class Rules {

        private Rules() {
        }

        /**
         * @param seaDimension 这个人在雾海里时是雾海的维度 id，否则 {@code null}
         * @param inVoyage     他是不是这一局里的人
         * @param today        这一局当日天候那一行（不在局里、或局还没翻出天候时为 {@code null}）
         */
        public static SkyS2C desired(String seaDimension, boolean inVoyage, FogTable.Entry today) {
            if (seaDimension == null) {
                return SkyS2C.NONE;
            }
            if (inVoyage && today != null) {
                return new SkyS2C(seaDimension, today.time(), today.rain() ? 1f : 0f, today.thunder() ? 1f : 0f);
            }
            return new SkyS2C(seaDimension, LINER_NIGHT, 0f, 0f);
        }

        /**
         * 同上，再叠上调试指定的时刻 / 雨（ADR-0060）。只在雾海里接管；没指定的那一项照上面算。
         *
         * @param forcedTime 指定的时刻；{@code null} = 不指定
         * @param forcedRain 指定的雨；{@code null} = 不指定
         */
        public static SkyS2C desired(String seaDimension, boolean inVoyage, FogTable.Entry today,
                                     Long forcedTime, Float forcedRain) {
            SkyS2C base = desired(seaDimension, inVoyage, today);
            if (!base.active() || (forcedTime == null && forcedRain == null)) {
                return base;
            }
            return new SkyS2C(base.dimension(), forcedTime != null ? forcedTime : base.timeOfDay(),
                    forcedRain != null ? forcedRain : base.rain(), base.thunder());
        }
    }
}
