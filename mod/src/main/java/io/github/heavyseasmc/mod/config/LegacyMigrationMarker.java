package io.github.heavyseasmc.mod.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** 配置值的迁移与旧文件归档分别记账；归档重试不能重新填入用户后来改回的默认值。 */
final class LegacyMigrationMarker {
    private static final String VERSION = "settings-imported-v1\n";
    private final Path pending;
    private final Path done;
    private final boolean complete;

    private LegacyMigrationMarker(Path pending, Path done, boolean complete) {
        this.pending = pending;
        this.done = done;
        this.complete = complete;
    }

    static LegacyMigrationMarker begin(Path source) throws IOException {
        Path pending = source.resolveSibling(source.getFileName() + ".values-importing");
        Path done = source.resolveSibling(source.getFileName() + ".values-imported");
        if (Files.exists(done)) {
            if (!Files.isRegularFile(done) || !VERSION.equals(Files.readString(done, StandardCharsets.UTF_8))) {
                throw new IOException("迁移完成标记读不对，未重填配置");
            }
            return new LegacyMigrationMarker(pending, done, true);
        }
        if (Files.exists(pending)) {
            throw new IOException("上次迁移在保存过程中中断；请核对设置后处理 .values-importing，未重填配置");
        }
        Files.writeString(pending, VERSION, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        FileSecretStore.restrict(pending);
        return new LegacyMigrationMarker(pending, done, false);
    }

    boolean complete() {
        return complete;
    }

    void saved() throws IOException {
        if (!complete) {
            Files.move(pending, done);
        }
    }

    void rejected() throws IOException {
        if (!complete) {
            Files.deleteIfExists(pending);
        }
    }
}
