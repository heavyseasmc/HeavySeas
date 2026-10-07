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
        assertFalse(s.setSecret(false, KEY, VALUE).accepted(), "没权限");
        assertFalse(s.setSecret(true, ServerSettingsTable.ACTION, "30").accepted(), "普通设置不走密钥那条路");
        assertFalse(s.setSecret(true, "llm.nope", VALUE).accepted(), "没登记的键");
        assertFalse(s.setSecret(true, KEY, "a\nb").accepted(), "控制字符");
        assertFalse(store.isSet(KEY));
        assertFalse(s.secretSet(secretDef(s)));
    }

    @Test
    @DisplayName("设了只说「设了」：快照里有键名、没有值（字节里也找不到），清掉之后又是「没设」")
    void snapshotOnlySaysWhetherItIsSet() {
        ServerSettings s = loaded(SecretStore.inMemory());
        assertTrue(s.setSecret(true, KEY, VALUE).accepted());
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
        assertTrue(s.setSecret(true, KEY, "").accepted(), "空串 = 清掉");
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
            assertTrue(SettingsSync.handleSecret(ok, "Alice", true, new ServerSecretSetC2S(KEY, VALUE)).accepted());
            assertTrue(SettingsSync.handleSecret(ok, "Alice", true, new ServerSecretSetC2S(KEY, "")).accepted());
            assertFalse(SettingsSync.handleSecret(ok, "Bob", false, new ServerSecretSetC2S(KEY, VALUE)).accepted());
            ok.spec().acceptConfig(null);
            ServerSettings failing = loaded(broken);
            assertFalse(SettingsSync.handleSecret(failing, "Carol", true, new ServerSecretSetC2S(KEY, VALUE)).accepted());
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
        assertFalse(new ServerSecretSetC2S(KEY, VALUE).toString().contains(VALUE), "包的 toString 带了值");
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
