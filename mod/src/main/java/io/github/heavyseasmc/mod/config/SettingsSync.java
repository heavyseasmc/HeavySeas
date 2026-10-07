package io.github.heavyseasmc.mod.config;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.net.OpenSettingsS2C;
import io.github.heavyseasmc.mod.net.ServerSecretSetC2S;
import io.github.heavyseasmc.mod.net.ServerSettingsS2C;
import io.github.heavyseasmc.mod.net.ServerSettingsSaveC2S;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 在客户端上改服务端设置的那条路（ADR-0099 D6，照 TLM：快照下发 · 只发改过的键 · 服务端再查权限 · 整批校验）。
 * 界面在 cut 2；这一刀先把协议立好、测好。
 *
 * <h2>快照什么时候发</h2>
 * <ul>
 *   <li>进服时发给他；</li>
 *   <li>他的权限变了（op / deop）时再发他一份 —— 「能不能改」只在发包那一刻算（TLM 教训 A8）；</li>
 *   <li>有人存了盘、或者文件被手改后 FCAP 读到了，发给每个人（值真的变了才发）。</li>
 * </ul>
 *
 * <h2>为什么权限变化靠「每秒比一次」而不是钩 op / deop</h2>
 * 本模组定过「服务端那一侧不注入任何 mixin」（ADR-0034 §8，{@code MixinInventoryTest} 守着）。钩 {@code PlayerManager#addToOperators}
 * 要为这一件事开第一个服务端 mixin、改那条规矩与生产 jar 的 mixin 闸门。这里改成每秒对每个在线的人重算一次「能不能改」，
 * <b>只在和上次发给他的不一样时</b>才重发 —— 不会像钩 {@code sendPlayerPermissionLevel} 那样每次进服、重生都白发一包（A8 记的坑），
 * 还顺带接住了 {@code /op} 以外改权限的路（控制台、改 ops.json 后重载）。代价是最多晚一秒。
 */
public final class SettingsSync {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    /** 重算权限的间隔（tick）。 */
    private static final int PERMISSION_POLL_TICKS = 20;

    /** 上次发给每个人的「能不能改」。 */
    private static final PermissionWatch WATCH = new PermissionWatch();
    /** 上次广播出去的值；文件变了但值没变（自己存盘之后 FCAP 的文件监视又读了一遍）时不重发。 */
    private static Map<String, String> lastBroadcast;
    private static int tickCounter;

    private SettingsSync() {
    }

    /** 包类型、收包、进服 · 掉线 · 每 tick 三个钩子。在模组入口里 {@link ServerSettings#register()} 之后调。 */
    public static void register() {
        PayloadTypeRegistry.playS2C().register(ServerSettingsS2C.ID, ServerSettingsS2C.CODEC);
        PayloadTypeRegistry.playS2C().register(OpenSettingsS2C.ID, OpenSettingsS2C.CODEC);
        PayloadTypeRegistry.playC2S().register(ServerSettingsSaveC2S.ID, ServerSettingsSaveC2S.CODEC);
        PayloadTypeRegistry.playC2S().register(ServerSecretSetC2S.ID, ServerSecretSetC2S.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ServerSettingsSaveC2S.ID,
                (payload, context) -> context.player().server.execute(() -> onSave(context.player(), payload)));
        ServerPlayNetworking.registerGlobalReceiver(ServerSecretSetC2S.ID,
                (payload, context) -> context.player().server.execute(() -> onSecret(context.player(), payload)));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                server.execute(() -> sendTo(handler.player)));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> WATCH.forget(handler.player.getUuid()));
        ServerTickEvents.END_SERVER_TICK.register(SettingsSync::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            WATCH.clear();
            lastBroadcast = null;
        });
    }

    /**
     * 能不能改服务端设置（ADR-0099 D6 · TLM {@code GameModeUtil#canEditSite}）：
     * 单人存档的主人与局域网房主（集成服务端上的「主人」）一律能；其余的人 —— 局域网的客人、专用服务端上的人 —— 要 2 级权限。
     *
     * @param dedicated 专用服务端
     * @param host      这个人是集成服务端的主人（单人存档 / 开了局域网的那一位）
     * @param opLevel2  这个人有 2 级以上权限
     */
    static boolean canEdit(boolean dedicated, boolean host, boolean opLevel2) {
        if (!dedicated && host) {
            return true;
        }
        return opLevel2;
    }

    public static boolean canEdit(ServerPlayerEntity player) {
        MinecraftServer server = player.server;
        return canEdit(server.isDedicated(), server.isHost(player.getGameProfile()), player.hasPermissionLevel(2));
    }

    /**
     * 拼一份快照。<b>密钥只给键名，不给值</b>；值只读 {@link ServerSettingsTable#synced()} 那几项。
     *
     * @param current   读一项此刻的值
     * @param secretSet 一项密钥设了没有
     */
    static ServerSettingsS2C snapshot(ServerSettingsTable table, Function<SettingDef, Object> current,
                                      Predicate<SettingDef> secretSet, boolean canEdit) {
        List<String> secrets = table.all().stream().filter(SettingDef::secret).filter(secretSet)
                .map(SettingDef::key).toList();
        return new ServerSettingsS2C(canEdit, table.snapshot(current), secrets);
    }

    private static ServerSettingsS2C snapshotFor(ServerPlayerEntity player, boolean canEdit) {
        ServerSettings settings = ServerSettings.instance();
        return snapshot(settings.table(), settings::current, settings::secretSet, canEdit);
    }

    /**
     * {@code /seas config}（ADR-0099 D4 (a)）：先发一份快照，再叫客户端开设置菜单（客户端排到下一 tick 再开）。
     *
     * @return 发出去了没有（客户端没装本模组时发不出去）
     */
    public static boolean openFor(ServerPlayerEntity player) {
        if (!ServerPlayNetworking.canSend(player, OpenSettingsS2C.ID)) {
            return false;
        }
        sendTo(player);
        ServerPlayNetworking.send(player, OpenSettingsS2C.INSTANCE);
        return true;
    }

    /**
     * 设一项密钥（只写不读）。日志<b>只写谁设了 / 清了哪一项</b>，值一个字都不进日志、不回显；回他（以及值变了时每个人）的是新快照，
     * 快照里密钥只有「设了没有」。
     */
    static void onSecret(ServerPlayerEntity player, ServerSecretSetC2S payload) {
        ServerSettings.Outcome outcome = handleSecret(ServerSettings.instance(), player.getGameProfile().getName(),
                canEdit(player), payload);
        if (!outcome.accepted()) {
            sendTo(player);
            return;
        }
        // 快照里的值没变（只有「设了没有」可能变了）：发给每个人，免得别的管理员的菜单还说「未设置」
        lastBroadcast = null;
        broadcastIfChanged(player.server);
        ServerSettings.fireChanged();          // 大模型那一层当场换上新密钥（存了就生效）
    }

    /** 密钥包的本体（不碰网络，单测直接调）：存不存、日志写什么都在这里。日志<b>只有键、谁、设还是清</b>。 */
    static ServerSettings.Outcome handleSecret(ServerSettings settings, String who, boolean canEdit, ServerSecretSetC2S payload) {
        boolean clearing = payload.value().isEmpty();
        ServerSettings.Outcome outcome = settings.setSecret(canEdit, payload.key(), payload.value());
        if (!outcome.accepted()) {
            LOGGER.warn("服务端设置：{} {}密钥 {} 被拒（{}）", who, clearing ? "清" : "设", payload.key(), outcome.rejection());
        } else {
            LOGGER.info("服务端设置：密钥 {} 被 {} {}", payload.key(), who, clearing ? "清掉了" : "设了新值");
        }
        return outcome;
    }

    /** 发他一份快照，并记下发给他的「能不能改」。 */
    static void sendTo(ServerPlayerEntity player) {
        if (!ServerSettings.instance().loaded() || !ServerPlayNetworking.canSend(player, ServerSettingsS2C.ID)) {
            return;
        }
        boolean canEdit = canEdit(player);
        ServerPlayNetworking.send(player, snapshotFor(player, canEdit));
        WATCH.sent(player.getUuid(), canEdit);
    }

    /**
     * 值变了就发给每个人。
     *
     * @return 发了没有
     */
    static boolean broadcastIfChanged(MinecraftServer server) {
        ServerSettings settings = ServerSettings.instance();
        if (!settings.loaded()) {
            return false;
        }
        Map<String, String> now = settings.snapshot();
        if (now.equals(lastBroadcast)) {
            return false;
        }
        lastBroadcast = new LinkedHashMap<>(now);
        server.getPlayerManager().getPlayerList().forEach(SettingsSync::sendTo);
        LOGGER.info("服务端设置：新值发给了 {} 个人", server.getPlayerManager().getPlayerList().size());
        return true;
    }

    /** 一个人的权限变了就重发他一份（见类注释：每秒比一次，只在变了时发）。 */
    private static void tick(MinecraftServer server) {
        if (++tickCounter < PERMISSION_POLL_TICKS) {
            return;
        }
        tickCounter = 0;
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            boolean now = canEdit(player);
            if (WATCH.changed(player.getUuid(), now)) {
                LOGGER.info("服务端设置：{} 的权限变了（{}），重发快照", player.getGameProfile().getName(), now ? "能改" : "只读");
                sendTo(player);
            }
        }
    }

    /**
     * 存盘包（ADR-0099 D6）：再查权限 → 整批核对 → 经 FCAP 改值存盘 → 新快照发给每个人。被拒时一行日志说清为什么，
     * 并把当前的快照发回给他（界面据此退回原值）。
     */
    static void onSave(ServerPlayerEntity player, ServerSettingsSaveC2S payload) {
        String who = player.getGameProfile().getName();
        ServerSettings.Outcome outcome = ServerSettings.instance().save(canEdit(player), payload.changes());
        if (!outcome.accepted()) {
            LOGGER.warn("服务端设置：{} 的一批改动被拒（{}）：{}", who, outcome.rejection(), payload.changes().keySet());
            sendTo(player);
            return;
        }
        LOGGER.info("服务端设置：{} 改了 {}", who, outcome.keys());
        if (!broadcastIfChanged(player.server)) {
            sendTo(player);                   // 值没变（或者存盘时已经广播过了）：至少回他一份，界面好收尾
        }
    }

    /**
     * 每个人上次收到的「能不能改」。纯数据，单测直接用。
     *
     * <p>❗没发过的人（进服那一包还没到）不算「变了」：进服那一包会带上，别在那之前多发一份。
     */
    static final class PermissionWatch {

        private final Map<UUID, Boolean> lastSent = new HashMap<>();

        void sent(UUID player, boolean canEdit) {
            lastSent.put(player, canEdit);
        }

        boolean changed(UUID player, boolean now) {
            Boolean last = lastSent.get(player);
            return last != null && last != now;
        }

        void forget(UUID player) {
            lastSent.remove(player);
        }

        void clear() {
            lastSent.clear();
        }
    }
}
