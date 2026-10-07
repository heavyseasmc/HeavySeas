package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.net.OpenSettingsS2C;
import io.github.heavyseasmc.mod.net.ServerSecretSetC2S;
import io.github.heavyseasmc.mod.net.ServerSettingsS2C;
import io.github.heavyseasmc.mod.net.ServerSettingsSaveC2S;
import io.github.heavyseasmc.mod.ui.SettingsMenuState;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;

/**
 * 客户端这一侧的服务端设置（ADR-0099 D6）：收服务端发来的快照、发存盘包与密钥包、接 {@code /seas config}。
 * 设置菜单（{@link SettingsScreen}）从这里读、往这里写。
 *
 * <p>❗界面<b>只认快照</b>，不读本机那份 FCAP 配置：连别人的服务端时，本机那份是 FCAP 进服时同步过来的副本，
 * 而「能不能改」只有快照里有（TLM 教训 A2：读口只有一个）。
 */
public final class ServerSettingsClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    /** 最近一次收到的快照；没进服或者服务端没装本模组时为空。 */
    private static volatile ServerSettingsS2C latest;
    /** 收到 {@code /seas config} 的回话、还没开出来（排到聊天框收起之后的那一 tick）。 */
    private static boolean openRequested;
    /** 第几次进服：草稿只在同一次连接里接回（换了服务端，上一台的改动不该挂到这一台上）。 */
    private static int connection;
    /** 设置菜单被别的界面顶掉时留下的草稿，与它属于哪一次连接。 */
    private static SettingsMenuState.Draft draft;
    private static int draftConnection;

    private ServerSettingsClient() {
    }

    static void register() {
        ClientPlayNetworking.registerGlobalReceiver(ServerSettingsS2C.ID, (payload, context) ->
                context.client().execute(() -> {
                    latest = payload;
                    // 与语言无关的一行：验收脚本据此判「快照到了 · 能不能改」
                    LOGGER.info("服务端设置：收到快照 {} 项 · {}", payload.values().size(), payload.canEdit() ? "能改" : "只读");
                    if (context.client().currentScreen instanceof SettingsScreen screen) {
                        screen.snapshotArrived();
                    }
                }));
        ClientPlayNetworking.registerGlobalReceiver(OpenSettingsS2C.ID, (payload, context) ->
                context.client().execute(() -> openRequested = true));
        ClientTickEvents.END_CLIENT_TICK.register(ServerSettingsClient::openWhenChatClosed);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> connection++);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            latest = null;
            openRequested = false;
            draft = null;
        });
    }

    /** 设置菜单被别的界面顶掉（对局里自动弹出的决策面）：没存的改动留着，下次开菜单接回。 */
    static void keepDraft(SettingsMenuState.Draft kept) {
        draft = kept;
        draftConnection = connection;
        LOGGER.info("设置菜单：被别的界面顶掉，留下没存的 {} 项", kept.pending().size());
    }

    /** 取走草稿（只取一次）；不是这一次连接留的就不给。 */
    static Optional<SettingsMenuState.Draft> takeDraft() {
        SettingsMenuState.Draft kept = draft;
        draft = null;
        if (kept == null || draftConnection != connection) {
            return Optional.empty();
        }
        LOGGER.info("设置菜单：接回上次没存的 {} 项", kept.pending().size());
        return Optional.of(kept);
    }

    /**
     * {@code /seas config}：聊天框回车之后一定把界面关掉（1.21.1 的 {@code ChatScreen}）—— 当场开的会被它立刻关掉，
     * 所以等到某一 tick 末尾聊天框已经不在了再开（ADR-0099 §2.3）。
     */
    private static void openWhenChatClosed(MinecraftClient client) {
        if (!openRequested || client.currentScreen instanceof ChatScreen) {
            return;
        }
        openRequested = false;
        LOGGER.info("服务端设置：/seas config 打开设置菜单");
        client.setScreen(new SettingsScreen(client.currentScreen));
    }

    public static Optional<ServerSettingsS2C> latest() {
        return Optional.ofNullable(latest);
    }

    /** 设置菜单的发包口：存盘包只含改过的键；密钥一项一包。 */
    static SettingsMenuState.Outbox outbox() {
        return new SettingsMenuState.Outbox() {
            @Override
            public void save(Map<String, String> changes) {
                requestSave(changes);
            }

            @Override
            public void secret(String key, String value) {
                ClientPlayNetworking.send(new ServerSecretSetC2S(key, value));
                // ❗只写键与「设 / 清」，不写值
                LOGGER.info("服务端设置：发出密钥包 {}（{}）", key, value.isEmpty() ? "清掉" : "设新值");
            }
        };
    }

    /**
     * 存盘：只发改过的那几项（键 → 新值）。服务端核过之后会发回新快照（被拒也发回当前的那一份）。
     *
     * @return 发出去了没有（没收到过快照、或者快照说只读时不发 —— 发了也会被拒）
     */
    public static boolean requestSave(Map<String, String> changes) {
        ServerSettingsS2C now = latest;
        if (now == null || !now.canEdit() || changes.isEmpty()) {
            LOGGER.info("服务端设置：没发存盘包（{}）", now == null ? "还没收到快照" : !now.canEdit() ? "只读" : "没有改动");
            return false;
        }
        ClientPlayNetworking.send(new ServerSettingsSaveC2S(changes));
        LOGGER.info("服务端设置：发出存盘包 {}", changes.keySet());
        return true;
    }
}
