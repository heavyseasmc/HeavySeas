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

import java.util.Collection;
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
    /** 被拒的包按人限频（审查 2026-10-07 L5）。 */
    private static final RejectLimiter REJECTS = new RejectLimiter();
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
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            WATCH.forget(handler.player.getUuid());
            REJECTS.forget(handler.player.getGameProfile().getName());
        });
        ServerTickEvents.END_SERVER_TICK.register(SettingsSync::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            WATCH.clear();
            REJECTS.clear();
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
     * 不能改设置的人收到的那一份里，{@link ServerSettingsTable#EDITORS_ONLY} 那几项（接口地址）换成 {@link ServerSettingsTable#HIDDEN}
     * （审查 2026-10-07 L1）。
     *
     * @param current   读一项此刻的值
     * @param secretSet 一项密钥设了没有
     */
    static ServerSettingsS2C snapshot(ServerSettingsTable table, Function<SettingDef, Object> current,
                                      Predicate<SettingDef> secretSet, boolean canEdit) {
        return snapshot(table, current, secretSet, canEdit, "");
    }

    /** @param rejection 这一份是回给发包的人的、他那一包被拒了：理由（一行、截短过）；不是回话是空串 */
    static ServerSettingsS2C snapshot(ServerSettingsTable table, Function<SettingDef, Object> current,
                                      Predicate<SettingDef> secretSet, boolean canEdit, String rejection) {
        List<String> secrets = table.all().stream().filter(SettingDef::secret).filter(secretSet)
                .map(SettingDef::key).toList();
        LinkedHashMap<String, String> values = table.snapshot(current);
        if (!canEdit) {
            values.replaceAll((key, value) -> ServerSettingsTable.EDITORS_ONLY.contains(key) ? ServerSettingsTable.HIDDEN : value);
        }
        return new ServerSettingsS2C(canEdit, values, secrets, rejection);
    }

    private static ServerSettingsS2C snapshotFor(ServerPlayerEntity player, boolean canEdit, String rejection) {
        ServerSettings settings = ServerSettings.instance();
        return snapshot(settings.table(), settings::current, settings::secretSet, canEdit, rejection);
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
        Handled handled = handleSecret(ServerSettings.instance(), player.getGameProfile().getName(), canEdit(player), payload);
        if (!handled.outcome().accepted()) {
            if (handled.answer()) {
                sendTo(player, handled.outcome().rejection());
            }
            return;
        }
        // 快照里的值没变（只有「设了没有」可能变了）：发给每个人，免得别的管理员的菜单还说「未设置」
        lastBroadcast = null;
        broadcastIfChanged(player.server);
        ServerSettings.fireChanged();          // 大模型那一层当场换上新密钥（存了就生效）
    }

    /**
     * 一包的处理结果：服务端的结论，加上要不要回他一份快照（被拒得太勤的那几包不回，见 {@link RejectLimiter}）。
     */
    record Handled(ServerSettings.Outcome outcome, boolean answer) {
    }

    /**
     * 密钥包的本体（不碰网络，单测直接调）：存不存、日志写什么都在这里。日志<b>只有键、谁、设还是清</b>。
     * 被拒时键名是客户端写的：去掉控制字符、截短再进日志，同一个人一秒内被拒多次只记一行（审查 2026-10-07 L5）。
     */
    static Handled handleSecret(ServerSettings settings, String who, boolean canEdit, ServerSecretSetC2S payload) {
        boolean clearing = payload.value().isEmpty();
        ServerSettings.Outcome outcome = settings.setSecret(canEdit, payload.key(), payload.value(), payload.scope());
        if (!outcome.accepted()) {
            RejectLimiter.Admit admit = REJECTS.admit(who, System.currentTimeMillis());
            if (admit.admitted()) {
                LOGGER.warn("服务端设置：{} {}密钥 {} 被拒（{}）{}", clip(who, 32), clearing ? "清" : "设", clip(payload.key(), 48),
                        clip(outcome.rejection(), 160), admit.note());
            }
            return new Handled(outcome, admit.admitted());
        }
        LOGGER.info("服务端设置：密钥 {} 被 {} {}", payload.key(), who, clearing ? "清掉了" : "设了新值");
        return new Handled(outcome, true);
    }

    /**
     * 存盘包的本体（不碰网络，单测直接调）。被拒时日志只记条数与前几个键名（截短、去掉控制字符），
     * 同一个人一秒内被拒多次只记一行、只回一份快照（审查 2026-10-07 L5：原先任何在线玩家发一包就写一行最多 128 × 256 字的 WARN）。
     */
    static Handled handleSave(ServerSettings settings, String who, boolean canEdit, ServerSettingsSaveC2S payload) {
        ServerSettings.Outcome outcome = settings.save(canEdit, payload.changes());
        if (!outcome.accepted()) {
            RejectLimiter.Admit admit = REJECTS.admit(who, System.currentTimeMillis());
            if (admit.admitted()) {
                LOGGER.warn("服务端设置：{} 的一批改动被拒（{}）：{}{}", clip(who, 32), clip(outcome.rejection(), 160),
                        describeKeys(payload.changes().keySet()), admit.note());
            }
            return new Handled(outcome, admit.admitted());
        }
        LOGGER.info("服务端设置：{} 改了 {}", who, outcome.keys());
        return new Handled(outcome, true);
    }

    /** 发他一份快照，并记下发给他的「能不能改」。 */
    static void sendTo(ServerPlayerEntity player) {
        sendTo(player, "");
    }

    /** @param rejection 这是回给他的、他那一包被拒了：理由；不是回话是空串 */
    private static void sendTo(ServerPlayerEntity player, String rejection) {
        if (!ServerSettings.instance().loaded() || !ServerPlayNetworking.canSend(player, ServerSettingsS2C.ID)) {
            return;
        }
        boolean canEdit = canEdit(player);
        ServerPlayNetworking.send(player, snapshotFor(player, canEdit, clip(rejection, ServerSettingsS2C.MAX_REJECTION)));
        WATCH.sent(player.getUuid(), canEdit);
    }

    /**
     * 值变了就发给每个人。
     *
     * <p>❗审查 2026-10-07 R9（第二轮驳回，这里核过成立）：起服迁移在 SERVER_STARTING 里存盘，FCAP 当场同步回调「重载」，
     * {@link ServerSettings} 的 {@code server.execute(...)} 在服务端线程上、又不在任务里时<b>就地执行</b>（1.21.1
     * {@code ThreadExecutor#execute}：{@code shouldExecuteAsync} 为假就直接 {@code run}），不是排到以后 ——
     * 而专用服务端要到 {@code setupServer} 里才建 PlayerManager。所以先问它在不在：不在就什么都不发（那时也没有人在线），
     * 也不记「上次发过的值」，免得真有人进来时少发一次。
     *
     * @return 发了没有
     */
    static boolean broadcastIfChanged(MinecraftServer server) {
        ServerSettings settings = ServerSettings.instance();
        if (!settings.loaded() || server.getPlayerManager() == null) {
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

    // ------------------------------------------------------------------ 客户端写的字进日志之前（审查 2026-10-07 L5）

    /** 客户端写的字：控制字符（换行之类，能伪造日志行）换成 {@code ?}，截到 {@code max} 个字。{@code null} 当空串。 */
    static String clip(String raw, int max) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        int[] cps = raw.codePoints().toArray();
        for (int i = 0; i < cps.length && i < max; i++) {
            out.appendCodePoint(Character.isISOControl(cps[i]) ? '?' : cps[i]);
        }
        if (cps.length > max) {
            out.append('…');
        }
        return out.toString();
    }

    /** 一批键名进日志的写法：几项，加前三个（截短过）。 */
    static String describeKeys(Collection<String> keys) {
        List<String> first = keys.stream().limit(3).map(k -> clip(k, 48)).toList();
        return keys.size() + " 项 " + String.join("、", first) + (keys.size() > 3 ? "……" : "");
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
        Handled handled = handleSave(ServerSettings.instance(), player.getGameProfile().getName(), canEdit(player), payload);
        if (!handled.outcome().accepted()) {
            if (handled.answer()) {
                sendTo(player, handled.outcome().rejection());   // 理由回给菜单：被拒的存盘不能显示「已保存」（审查 2026-10-07 U2）
            }
            return;
        }
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

    /**
     * 被拒的包按人限频（审查 2026-10-07 L5）：存盘包与密钥包谁都能发（查权限是收到之后的事），Minecraft 自带的 rate-limit 默认又是关的。
     * 同一个人上一次「记了日志、回了快照」之后 {@link #WINDOW_MS} 之内再被拒：不记、不回，只数一下；下一次记的那一行里说压下了几次。
     *
     * <p>正常用菜单一次存盘最多发两包（一批改动 + 一项密钥），两包都被拒时第二包不回 —— 菜单那一侧有第一包的理由就够了。纯数据，单测直接用。
     */
    static final class RejectLimiter {

        static final long WINDOW_MS = 1_000;

        /** 这一次被拒要不要记、要不要回；{@code suppressed} 是上一次记了之后压下了几次。 */
        record Admit(boolean admitted, int suppressed) {
            /** 接在日志那一行末尾的一句（没压下过是空串）。 */
            String note() {
                return suppressed == 0 ? "" : "（之前一秒内另有 " + suppressed + " 次被拒，没记）";
            }
        }

        private final Map<String, long[]> last = new HashMap<>();   // 人 → {上次记的时刻, 之后压下了几次}

        synchronized Admit admit(String who, long nowMs) {
            long[] entry = last.get(who);
            if (entry != null && nowMs - entry[0] < WINDOW_MS) {
                entry[1]++;
                return new Admit(false, (int) entry[1]);
            }
            int suppressed = entry == null ? 0 : (int) entry[1];
            last.put(who, new long[]{nowMs, 0});
            return new Admit(true, suppressed);
        }

        synchronized void forget(String who) {
            last.remove(who);
        }

        synchronized void clear() {
            last.clear();
        }
    }
}
