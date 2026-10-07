package io.github.heavyseasmc.mod.config;

import io.github.heavyseasmc.mod.net.ServerSettingsS2C;
import io.github.heavyseasmc.mod.net.ServerSettingsSaveC2S;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在客户端上改服务端设置的协议（ADR-0099 D6）：快照来回一趟不变 · 存盘包来回一趟不变 · 谁能改 · 权限变了才重发 ·
 * <b>密钥永不进快照</b>（§6：红测往快照里塞密钥，判据必须红在那一条）。
 */
final class SettingsProtocolTest {

    private static final String SECRET_KEY = "llm.api_key";
    private static final String SECRET_VALUE = "sk-test-0123456789-not-a-real-key";

    /** 默认表：「大模型」一组里那一项就是密钥（cut 3b 起表里自己带着它）。 */
    private static ServerSettingsTable withSecret() {
        ServerSettingsTable table = ServerSettingsTable.DEFAULT;
        assertTrue(table.def(SECRET_KEY).map(SettingDef::secret).orElse(false), "表里的密钥那一项不在了：下面几条就什么也没测");
        return table;
    }

    /** 读一项此刻的值：密钥那一项返回一串假密钥 —— 只要有人去读它、并把它写进包里，下面就会在字节里找到它。 */
    private static Object current(SettingDef def) {
        return def.secret() ? SECRET_VALUE : def.fallback();
    }

    private static <T> T roundTrip(net.minecraft.network.codec.PacketCodec<ByteBuf, T> codec, T value, byte[][] bytesOut) {
        ByteBuf buf = Unpooled.buffer();
        try {
            codec.encode(buf, value);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), bytes);
            bytesOut[0] = bytes;
            T back = codec.decode(buf);
            assertFalse(buf.isReadable(), "读完还剩字节：两边的字段表不一致");
            return back;
        } finally {
            buf.release();
        }
    }

    private ServerSettings settings;

    @AfterEach
    void unload() {
        if (settings != null) {
            settings.spec().acceptConfig(null);
        }
    }

    @Test
    @DisplayName("密钥永不进快照：值不在包的字节里，只给键名说「设了」")
    void snapshotNeverCarriesSecretValues() {
        ServerSettingsTable table = withSecret();
        ServerSettingsS2C snapshot = SettingsSync.snapshot(table, SettingsProtocolTest::current, def -> true, true);
        byte[][] bytes = new byte[1][];
        assertEquals(snapshot, roundTrip(ServerSettingsS2C.CODEC, snapshot, bytes));
        String wire = new String(bytes[0], StandardCharsets.ISO_8859_1);
        // 判据本体在前：密钥一旦漏进来，红在这两行（红测时核对过红在哪一行）
        assertFalse(wire.contains(SECRET_VALUE), "密钥的值进了快照包的字节");
        assertFalse(snapshot.values().containsKey(SECRET_KEY), "密钥的键出现在值表里");
        assertEquals(List.of(SECRET_KEY), snapshot.secretsSet(), "密钥只给键名");
        // 正向对照：普通的项都在、字节里扫得到（不是「什么都没发」才没有密钥）
        assertEquals(ServerSettingsTable.DEFAULT.synced().size(), snapshot.values().size());
        assertEquals("60", snapshot.values().get(ServerSettingsTable.ACTION));
        assertTrue(wire.contains(ServerSettingsTable.ACTION), "正向对照：普通项的键在字节里（判据确实在扫这一包）");
    }

    @Test
    @DisplayName("密钥也不进 FCAP 的 SERVER spec（FCAP 进服时把那份文件整份发给每个客户端），存盘包也改不了它")
    void secretsStayOutOfTheSyncedSpecAndTheSavePath() {
        ServerSettingsTest.Loaded file = new ServerSettingsTest.Loaded();
        settings = ServerSettingsTest.loaded(withSecret(), file);
        assertNull(file.config.get(List.of("llm", "api_key")), "密钥进了会同步给客户端的那份配置");
        assertTrue(file.config.contains(List.of("windows", "action_seconds")), "正向对照：普通项在");
        assertFalse(settings.save(true, Map.of(SECRET_KEY, "true")).accepted(), "存盘包不该能写密钥");
        assertEquals(0, file.saves);
    }

    @Test
    @DisplayName("值来回一趟不变：快照里的字符串经存盘包核对之后，就是原来那个值")
    void valuesRoundTrip() {
        ServerSettingsTest.Loaded file = new ServerSettingsTest.Loaded();
        settings = ServerSettingsTest.loaded(ServerSettingsTable.DEFAULT, file);
        Map<String, String> changes = new LinkedHashMap<>();
        changes.put(ServerSettingsTable.HELM, "37");
        changes.put(ServerSettingsTable.UNTIMED_DEMO, "false");
        byte[][] bytes = new byte[1][];
        ServerSettingsSaveC2S save = roundTrip(ServerSettingsSaveC2S.CODEC, new ServerSettingsSaveC2S(changes), bytes);
        assertEquals(changes, save.changes());
        assertEquals(List.copyOf(changes.keySet()), List.copyOf(save.changes().keySet()), "键的先后也要保住");
        assertTrue(settings.save(true, save.changes()).accepted());

        ServerSettingsS2C snapshot = SettingsSync.snapshot(settings.table(), settings::current, def -> false, false);
        ServerSettingsS2C back = roundTrip(ServerSettingsS2C.CODEC, snapshot, bytes);
        assertEquals("37", back.values().get(ServerSettingsTable.HELM));
        assertEquals("false", back.values().get(ServerSettingsTable.UNTIMED_DEMO));
        assertFalse(back.canEdit());
        // 快照里的每一项原样发回去，核对得过、值不变
        ServerSettingsTable.Batch again = settings.table().validate(back.values());
        assertTrue(again.ok(), again.rejection());
        for (SettingDef def : settings.table().synced()) {
            assertEquals(settings.current(def), again.values().get(def.key()), def.key());
        }
    }

    @Test
    @DisplayName("谁能改：单人 / 局域网的主人能；专用服务端与局域网客人要 2 级")
    void whoCanEdit() {
        assertTrue(SettingsSync.canEdit(false, true, false), "集成服务端的主人（单人存档 · 局域网房主）");
        assertFalse(SettingsSync.canEdit(false, false, false), "局域网客人，没有 2 级");
        assertTrue(SettingsSync.canEdit(false, false, true), "局域网客人，有 2 级");
        assertFalse(SettingsSync.canEdit(true, false, false), "专用服务端，没有 2 级");
        assertTrue(SettingsSync.canEdit(true, false, true), "专用服务端，有 2 级");
        assertFalse(SettingsSync.canEdit(true, true, false), "专用服务端上没有「主人」一说");
    }

    @Test
    @DisplayName("权限变了才重发：没发过的不算、没变的不发、op / deop 各发一次")
    void permissionChangesTriggerOneResend() {
        SettingsSync.PermissionWatch watch = new SettingsSync.PermissionWatch();
        UUID player = UUID.randomUUID();
        assertFalse(watch.changed(player, true), "进服那一包还没发：不抢在它前面发");
        watch.sent(player, false);
        assertFalse(watch.changed(player, false), "没变就不发（不是每秒发一包）");
        assertTrue(watch.changed(player, true), "op 之后");
        watch.sent(player, true);
        assertFalse(watch.changed(player, true));
        assertTrue(watch.changed(player, false), "deop 之后");
        watch.forget(player);
        assertFalse(watch.changed(player, false), "掉线之后忘掉");
    }
}
