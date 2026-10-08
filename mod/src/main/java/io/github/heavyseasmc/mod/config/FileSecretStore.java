package io.github.heavyseasmc.mod.config;

import io.github.heavyseasmc.mod.HeavySeasMod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * 密钥存在服务端自己的一个文件里：{@code config/heavyseas-secrets.properties}（ADR-0099 D6）。
 *
 * <p>这个文件<b>只有本模组一个主人</b>：FCAP 不管它（它的 SERVER 那份会整份同步给客户端，密钥不能进去），
 * 也没有第二处写它的代码。写盘是先写临时文件再原子替换，不留半份；能设权限的文件系统上只给属主读写。
 *
 * <p>文件不在就是「一项都没设」。
 *
 * <h2>读不出来（审查 2026-10-07 C3）</h2>
 * 文件在、却读不出来（用记事本按 GBK 加了一行中文备注 · 残缺的 {@code \\u} 转义 · 换了运行账号读不到）：当一项都没设，
 * 记一行 ERROR 说清文件在哪、怎么修；<b>这一次运行里不再写它</b>（{@link #put} 抛 {@link SecretStore.Unreadable}）——
 * 写回去就把原文件整份盖掉了。原先这里直接抛，没人接：起服在 SERVER_STARTED 崩，单人存档连客户端一起崩，
 * 而这个文件是全局的，所有存档都打不开。改好（或删掉）文件之后重启服务端。
 */
public final class FileSecretStore implements SecretStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    public static final String FILE = "heavyseas-secrets.properties";

    private final Path file;
    private Properties cache;
    /** 文件在、却读不出来：这一次运行里按空处理、不写它。 */
    private boolean unreadable;

    public FileSecretStore(Path configDir) {
        this.file = configDir.resolve(FILE);
    }

    public Path file() {
        return file;
    }

    private synchronized Properties load() {
        if (cache == null) {
            Properties props = new Properties();
            if (Files.isRegularFile(file)) {
                try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    props.load(in);
                } catch (IOException | RuntimeException e) {
                    // IOException：不是 UTF-8（MalformedInputException）· 读不到（AccessDeniedException）；
                    // IllegalArgumentException：残缺的 \\u 转义。异常的消息里只有路径或一句固定的话，没有文件内容
                    props = new Properties();
                    unreadable = true;
                    LOGGER.error("密钥文件 {} 读不出来（{}）：这一次按一项密钥都没设处理，设置菜单里也设不进去（不写它，免得盖掉原文件）。"
                                    + "常见原因：用记事本加了中文备注却没存成 UTF-8、有残缺的 \\u 转义、运行服务端的账号读不到它。"
                                    + "改好或删掉这个文件后重启服务端（删掉的话要在设置菜单里重新设密钥）",
                            file, e.toString());
                }
            }
            cache = props;
        }
        return cache;
    }

    @Override
    public synchronized boolean isSet(String key) {
        String value = load().getProperty(key);
        return value != null && !value.isEmpty();
    }

    @Override
    public synchronized Optional<String> get(String key) {
        String value = load().getProperty(key);
        return value == null || value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    @Override
    public synchronized void put(String key, String value) {
        putAll(java.util.Collections.singletonMap(key, value));
    }

    /** 几项一起写：一次原子替换（密钥与它绑的地址不会只落一半）。 */
    @Override
    public synchronized void putAll(Map<String, String> entries) {
        Properties current = load();
        if (unreadable) {
            throw new SecretStore.Unreadable("密钥文件读不出来，这一次运行里不写它：" + file);   // ❗只有路径
        }
        Properties next = new Properties();
        next.putAll(current);
        entries.forEach((key, value) -> {
            if (value == null || value.isEmpty()) {
                next.remove(key);
            } else {
                next.setProperty(key, value);
            }
        });
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(FILE + ".tmp");
            try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                next.store(out, "Heavy Seas server secrets. Written by the settings menu; never sent to clients.");
            }
            restrict(tmp);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("密钥文件没写上：" + file, e);   // ❗异常里只有路径，没有值
        }
        cache = next;
    }

    /** 只给属主读写（POSIX 文件系统；Windows 上靠用户目录本身的权限）。迁移留下的旧文件也照这样收紧（{@code SettingsMigration}）。 */
    static void restrict(Path path) {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            // 不支持 POSIX 权限的文件系统：照常写，不当成失败
        }
    }

    @Override
    public String toString() {
        return "FileSecretStore[" + file + "]";                       // ❗不带值
    }
}
