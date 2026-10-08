package io.github.heavyseasmc.mod.config;

import io.github.heavyseasmc.mod.net.ServerSecretSetC2S;
import io.github.heavyseasmc.mod.net.ServerSettingsS2C;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 密钥那条只写的路（ADR-0099 D6 · ADR-0096 §8）：要权限 · 只认登记过的密钥 · 存在服务端自己的文件里 ·
 * 快照里只有「设了没有」· <b>日志里只有键、谁、设还是清，没有值</b>。
 */
final class SecretPathTest {

    private static final String KEY = "llm.api_key";
    private static final String VALUE = "sk-live-0123456789abcdef-not-real";
    /** 密钥绑的接口地址（审查 2026-10-07 L1：设密钥之前要先有一个地址）。 */
    private static final String URL = "http://127.0.0.1:8317/v1";

    /** 默认表：「大模型」一组里的 {@code llm.api_key} 就是那一项密钥。 */
    private static ServerSettingsTable table() {
        assertTrue(ServerSettingsTable.DEFAULT.def(KEY).map(SettingDef::secret).orElse(false), "表里没有这一项密钥");
        return ServerSettingsTable.DEFAULT;
    }

    private ServerSettings settings;

    @AfterEach
    void unload() {
        if (settings != null) {
            settings.spec().acceptConfig(null);
        }
    }

    private ServerSettings loaded(SecretStore store) {
        ServerSettingsTest.Loaded file = new ServerSettingsTest.Loaded();
        ServerSettings s = new ServerSettings(table(), store);
        s.spec().correct(file.config);
        s.spec().acceptConfig(file);
        settings = s;
        assertTrue(s.save(true, java.util.Map.of(ServerSettingsTable.LLM_BASE_URL, URL)).accepted(), "前提：先有一个接口地址");
        return s;
    }

    private static SettingDef secretDef(ServerSettings s) {
        return s.table().def(KEY).orElseThrow();
    }

    @Test
    @DisplayName("没权限 · 不是密钥 · 不认识的键：一律拒，什么都没存")
    void onlyEditorsAndOnlyRegisteredSecrets() {
        SecretStore store = SecretStore.inMemory();
        ServerSettings s = loaded(store);
        assertFalse(s.setSecret(false, KEY, VALUE, URL).accepted(), "没权限");
        assertFalse(s.setSecret(true, ServerSettingsTable.ACTION, "30", URL).accepted(), "普通设置不走密钥那条路");
        assertFalse(s.setSecret(true, "llm.nope", VALUE, URL).accepted(), "没登记的键");
        assertFalse(s.setSecret(true, KEY, "a\nb", URL).accepted(), "控制字符");
        assertFalse(store.isSet(KEY));
        assertFalse(s.secretSet(secretDef(s)));
    }

    @Test
    @DisplayName("设了只说「设了」：快照里有键名、没有值（字节里也找不到），清掉之后又是「没设」")
    void snapshotOnlySaysWhetherItIsSet() {
        ServerSettings s = loaded(SecretStore.inMemory());
        assertTrue(s.setSecret(true, KEY, VALUE, URL).accepted());
        assertTrue(s.secretSet(secretDef(s)));
        assertEquals(Optional.of(VALUE), s.secret(KEY), "服务端自己读得到");
        ServerSettingsS2C snapshot = SettingsSync.snapshot(s.table(), s::current, s::secretSet, true);
        assertEquals(List.of(KEY), snapshot.secretsSet());
        assertFalse(snapshot.values().containsKey(KEY));
        ByteBuf buf = Unpooled.buffer();
        try {
            ServerSettingsS2C.CODEC.encode(buf, snapshot);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), bytes);
            String wire = new String(bytes, StandardCharsets.ISO_8859_1);
            assertFalse(wire.contains(VALUE), "密钥的值进了快照包");
            assertTrue(wire.contains(KEY), "正向对照：键名在字节里（判据确实在扫这一包）");
        } finally {
            buf.release();
        }
        assertTrue(s.setSecret(true, KEY, "", URL).accepted(), "空串 = 清掉");
        assertFalse(s.secretSet(secretDef(s)));
        assertEquals(Optional.empty(), s.secret(KEY));
    }

    @Test
    @DisplayName("日志里没有值：设、清、被拒、写盘失败四条路都只写键与谁")
    void logsNeverCarryTheValue() {
        SecretStore broken = new SecretStore() {
            @Override
            public boolean isSet(String key) {
                return false;
            }

            @Override
            public Optional<String> get(String key) {
                return Optional.empty();
            }

            @Override
            public void put(String key, String value) {
                throw new IllegalStateException("磁盘满了（测试）");
            }
        };
        try (Capture logs = new Capture()) {
            ServerSettings ok = loaded(SecretStore.inMemory());
            assertTrue(SettingsSync.handleSecret(ok, "Alice", true, new ServerSecretSetC2S(KEY, VALUE, URL)).outcome().accepted());
            assertTrue(SettingsSync.handleSecret(ok, "Alice", true, new ServerSecretSetC2S(KEY, "", URL)).outcome().accepted());
            assertFalse(SettingsSync.handleSecret(ok, "Bob", false, new ServerSecretSetC2S(KEY, VALUE, URL)).outcome().accepted());
            ok.spec().acceptConfig(null);
            ServerSettings failing = loaded(broken);
            assertFalse(SettingsSync.handleSecret(failing, "Carol", true, new ServerSecretSetC2S(KEY, VALUE, URL)).outcome().accepted());
            List<String> lines = logs.lines();
            // 判据本体：哪一行都没有值
            List<String> leaked = lines.stream().filter(l -> l.contains(VALUE)).toList();
            assertEquals(List.of(), leaked, "密钥的值进了日志");
            // 正向对照：四条路的那一行都在（收得到日志，不是什么都没写）
            assertTrue(lines.stream().anyMatch(l -> l.contains("Alice") && l.contains(KEY) && l.contains("设了新值")), lines.toString());
            assertTrue(lines.stream().anyMatch(l -> l.contains("Alice") && l.contains("清掉了")), lines.toString());
            assertTrue(lines.stream().anyMatch(l -> l.contains("Bob") && l.contains("被拒")), lines.toString());
            assertTrue(lines.stream().anyMatch(l -> l.contains("Carol") && l.contains("被拒")), lines.toString());
        }
        assertFalse(new ServerSecretSetC2S(KEY, VALUE, URL).toString().contains(VALUE), "包的 toString 带了值");
    }

    @Test
    @DisplayName("文件存储：写进服务端自己的文件、再开一次读得回来、清掉就没了；toString 不带值")
    void fileStoreRoundTrips(@TempDir Path dir) throws Exception {
        FileSecretStore store = new FileSecretStore(dir);
        assertFalse(store.isSet(KEY), "文件不在 = 一项都没设");
        store.put(KEY, VALUE);
        assertTrue(Files.isRegularFile(dir.resolve(FileSecretStore.FILE)));
        FileSecretStore again = new FileSecretStore(dir);
        assertEquals(Optional.of(VALUE), again.get(KEY), "重开读得回来");
        assertFalse(again.toString().contains(VALUE));
        again.put(KEY, "");
        assertFalse(new FileSecretStore(dir).isSet(KEY), "清掉之后重开也没了");
        assertFalse(Files.exists(dir.resolve(FileSecretStore.FILE + ".tmp")), "临时文件不留");
    }

    /** 一份读不出来的密钥文件：记事本按 GBK 加了一行中文备注 · 残缺的 \\u 转义。 */
    private static byte[] unreadable(String kind) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        if (kind.equals("gbk")) {
            out.writeBytes("# ".getBytes(StandardCharsets.US_ASCII));
            out.writeBytes("我的备注".getBytes(java.nio.charset.Charset.forName("GBK")));
            out.writeBytes(("\n" + KEY + "=" + VALUE + "\n").getBytes(StandardCharsets.US_ASCII));
        } else {
            out.writeBytes((KEY + "=" + VALUE + "\nnote=\\u12\n").getBytes(StandardCharsets.US_ASCII));
        }
        return out.toByteArray();
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"gbk", "bad-escape"})
    @DisplayName("❗密钥文件读不出来：当一项都没设、不抛（起服不崩）；读坏的期间不写回（原文件一个字节不动）；日志说清文件在哪、怎么修，不带值")
    void unreadableFileIsEmptyAndNeverOverwritten(String kind, @TempDir Path dir) throws Exception {
        // 审查 2026-10-07 C3：load() 原先把异常原样抛出，SERVER_STARTED 里的 LlmHooks.reload 接不住，起服崩（单人存档连客户端一起崩）
        Path file = dir.resolve(FileSecretStore.FILE);
        byte[] original = unreadable(kind);
        Files.write(file, original);
        try (Capture logs = new Capture()) {
            FileSecretStore store = new FileSecretStore(dir);
            ServerSettings s = loaded(store);
            try (AutoCloseable use = ServerSettings.useForTests(s)) {
                // 判据本体：起服那条路（SERVER_STARTED → LlmHooks.reload → llmConfig → 读密钥）不抛
                org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> ServerSettings.llmConfig(k -> null),
                        "密钥文件读不出来就抛：起服在 SERVER_STARTED 崩");
            }
            assertFalse(store.isSet(KEY), "读不出来 = 一项都没设");
            assertEquals(Optional.empty(), store.get(KEY));
            assertFalse(s.setSecret(true, KEY, "sk-new-value-not-real", URL).accepted(),
                    "读坏的期间设新值：该拒（写回去会把原文件整份盖掉）");
            org.junit.jupiter.api.Assertions.assertArrayEquals(original, Files.readAllBytes(file), "读坏的文件被改了");
            List<String> lines = logs.lines();
            assertTrue(lines.stream().anyMatch(l -> l.contains(FileSecretStore.FILE) && l.contains("读不出来")), lines.toString());
            assertEquals(List.of(), lines.stream().filter(l -> l.contains(VALUE)).toList(), "密钥的值进了日志");
        }
    }

    @Test
    @DisplayName("❗被拒的密钥包不刷日志：键名里的换行不进日志（伪造不了日志行），同一个人一秒内连发只记一行")
    void rejectedSecretPacketsDoNotFloodTheLog() {
        // 审查 2026-10-07 L5：任何在线玩家都能发这个包；原先被拒一次就把键名原样写一行 WARN
        String forged = "llm.api_key\n[Server thread/INFO]: 伪造的一行 " + "x".repeat(200);   // 包里的键最长 256 字
        try (Capture logs = new Capture()) {
            ServerSettings s = loaded(SecretStore.inMemory());
            for (int i = 0; i < 20; i++) {
                assertFalse(SettingsSync.handleSecret(s, "Mallory", false,
                        new ServerSecretSetC2S(forged, VALUE, URL)).outcome().accepted());
            }
            List<String> lines = logs.lines().stream().filter(l -> l.contains("Mallory")).toList();
            assertEquals(List.of(), lines.stream().filter(l -> l.contains("\n") || l.contains("伪造的一行 " + "x".repeat(100))).toList(),
                    "键名原样进了日志（带换行 / 不截短）");
            assertEquals(1, lines.size(), "一秒内被拒 20 次，该只记一行：" + lines);
            assertFalse(lines.getFirst().contains(VALUE));
            assertTrue(lines.getFirst().contains("被拒"), "正向对照：记下的那一行就是被拒那一行：" + lines.getFirst());
        }
    }

    @Test
    @DisplayName("❗被拒的存盘包不刷日志：只记条数与前几个键名（截短、没有换行），同一个人一秒内连发只记一行、只回一份快照")
    void rejectedSaveBatchesDoNotFloodTheLog() {
        // 审查 2026-10-07 L5：原先任何在线玩家发一包，服务端就把最多 128 × 256 字的键名写成一行 WARN
        java.util.Map<String, String> junk = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 128; i++) {
            junk.put("k" + i + "\n[Server thread/INFO]: 伪造的一行 " + "y".repeat(200), "1");
        }
        try (Capture logs = new Capture()) {
            ServerSettings s = loaded(SecretStore.inMemory());
            List<Boolean> answered = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                SettingsSync.Handled h = SettingsSync.handleSave(s, "Eve", false,
                        new io.github.heavyseasmc.mod.net.ServerSettingsSaveC2S(junk));
                assertFalse(h.outcome().accepted());
                answered.add(h.answer());
            }
            List<String> lines = logs.lines().stream().filter(l -> l.contains("Eve")).toList();
            assertEquals(1, lines.size(), "一秒内被拒 20 次，该只记一行：" + lines);
            assertFalse(lines.getFirst().contains("\n"), "键名里的换行进了日志：" + lines.getFirst());
            assertTrue(lines.getFirst().length() < 600, "一行日志该只有条数与前几个键名，实际 " + lines.getFirst().length() + " 字");
            assertTrue(lines.getFirst().contains("128 项"), "要说一共几项：" + lines.getFirst());
            assertEquals(1, answered.stream().filter(a -> a).count(), "只回一份快照：" + answered);
        }
    }

    @Test
    @DisplayName("限频：同一个人一秒之内只放过一次；过了一秒再放，那一行里说压下了几次；不同的人各算各的")
    void rejectLimiterAdmitsOncePerSecondPerPerson() {
        SettingsSync.RejectLimiter limiter = new SettingsSync.RejectLimiter();
        assertTrue(limiter.admit("A", 10_000).admitted());
        assertFalse(limiter.admit("A", 10_400).admitted());
        assertFalse(limiter.admit("A", 10_999).admitted());
        assertTrue(limiter.admit("B", 10_500).admitted(), "别人不受 A 牵连");
        SettingsSync.RejectLimiter.Admit later = limiter.admit("A", 11_000);
        assertTrue(later.admitted());
        assertEquals(2, later.suppressed());
        assertTrue(later.note().contains("2"), later.note());
        assertEquals("", limiter.admit("A", 12_500).note(), "上一次之后没压下过：不说");
    }

    @Test
    @DisplayName("❗密钥绑地址：设的时候记下服务端此刻地址的「协议 + 主机 + 端口」；菜单里看到的地址与服务端此刻的对不上（那一批改地址被拒了）就不设")
    void secretIsBoundToTheEndpointItWasSetFor() {
        // 审查 2026-10-07 L1
        SecretStore store = SecretStore.inMemory();
        ServerSettings s = loaded(store);
        assertTrue(s.setSecret(true, KEY, VALUE, URL).accepted());
        assertEquals(Optional.of("http://127.0.0.1:8317"), s.secretOrigin(KEY), "没记下密钥是给哪个地址的");

        // 同一次存盘：先发「把地址改成别处」的那一批 —— 被拒了 —— 再发密钥。服务端此刻还是旧地址，密钥不能绑上去
        ServerSettings.Outcome mismatch = s.setSecret(true, KEY, "sk-for-the-other-endpoint", "https://other.example/v1");
        assertFalse(mismatch.accepted(), "给新地址的密钥绑到了旧地址上");
        assertTrue(mismatch.rejection().contains("http://127.0.0.1:8317"), mismatch.rejection());
        assertEquals(Optional.of(VALUE), s.secret(KEY), "被拒的那一下动了存着的密钥");

        assertTrue(s.setSecret(true, KEY, "", "").accepted(), "清掉不看地址");
        assertEquals(Optional.empty(), s.secretOrigin(KEY), "清掉密钥时绑的地址没一起清");

        assertTrue(s.save(true, java.util.Map.of(ServerSettingsTable.LLM_BASE_URL, "")).accepted());
        assertFalse(s.setSecret(true, KEY, VALUE, "").accepted(), "没有地址时设密钥：绑不上任何地方，该拒");
        assertFalse(store.isSet(KEY));
    }

    /** 把真正写出去的日志收下来（挂在 log4j 的根上；SLF4J 在这里落到 log4j）。同一个包里的迁移单测也用它。 */
    static final class Capture implements AutoCloseable {

        private final List<String> lines = Collections.synchronizedList(new ArrayList<>());
        private final LoggerContext context;
        private final LoggerConfig root;
        private final Level oldLevel;
        private final AbstractAppender appender;

        Capture() {
            context = (LoggerContext) LogManager.getContext(false);
            root = context.getConfiguration().getRootLogger();
            oldLevel = root.getLevel();
            appender = new AbstractAppender("settings-secret-capture-" + System.nanoTime(), null, null, true,
                    Property.EMPTY_ARRAY) {
                @Override
                public void append(LogEvent event) {
                    StringBuilder sb = new StringBuilder(event.getMessage().getFormattedMessage());
                    if (event.getThrown() != null) {
                        StringWriter w = new StringWriter();
                        event.getThrown().printStackTrace(new PrintWriter(w));
                        sb.append('\n').append(w);
                    }
                    lines.add(sb.toString());
                }
            };
            appender.start();
            root.addAppender(appender, Level.ALL, null);
            root.setLevel(Level.ALL);
            context.updateLoggers();
        }

        List<String> lines() {
            synchronized (lines) {
                return List.copyOf(lines);
            }
        }

        @Override
        public void close() {
            root.removeAppender(appender.getName());
            root.setLevel(oldLevel);
            context.updateLoggers();
            appender.stop();
        }
    }
}
