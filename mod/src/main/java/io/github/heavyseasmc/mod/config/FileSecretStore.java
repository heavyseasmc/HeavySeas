package io.github.heavyseasmc.mod.config;

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
import java.util.Optional;
import java.util.Properties;

/**
 * 密钥存在服务端自己的一个文件里：{@code config/heavyseas-secrets.properties}（ADR-0099 D6）。
 *
 * <p>这个文件<b>只有本模组一个主人</b>：FCAP 不管它（它的 SERVER 那份会整份同步给客户端，密钥不能进去），
 * 也没有第二处写它的代码。写盘是先写临时文件再原子替换，不留半份；能设权限的文件系统上只给属主读写。
 *
 * <p>文件不在就是「一项都没设」；读不出来照样当一项都没设，并在日志里说一句（调用方那一侧）—— 不让服务端起不来。
 */
public final class FileSecretStore implements SecretStore {

    public static final String FILE = "heavyseas-secrets.properties";

    private final Path file;
    private Properties cache;

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
                } catch (IOException e) {
                    throw new UncheckedIOException("密钥文件读不出来：" + file, e);
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
        Properties next = new Properties();
        next.putAll(load());
        if (value == null || value.isEmpty()) {
            next.remove(key);
        } else {
            next.setProperty(key, value);
        }
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

    /** 只给属主读写（POSIX 文件系统；Windows 上靠用户目录本身的权限）。 */
    private static void restrict(Path path) {
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
