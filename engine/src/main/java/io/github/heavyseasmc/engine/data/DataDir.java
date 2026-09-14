package io.github.heavyseasmc.engine.data;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code data/} 目录的定址。
 *
 * <h2>找不到就抛，不退回默认值</h2>
 * 这条是踩出来的：管线里另一个解析器找不到仓库时会<b>静默退回</b>到一个内部默认目录，
 * 结果四个脚本全写进了一份已作废的副本，无一报错。静默退回让「配错了」与
 * 「这台机器上没有」输出完全相同，而这两件事要做的处置正相反。
 * 所以本类只有一个逃生口：显式的系统属性或环境变量。
 *
 * <h2>唯一合法的「文件不在」</h2>
 * {@link #optionalFile} 只在<b>目录已经解析成功</b>时才可能返回空，
 * 即「这台机器上确实没有这个文件」。目前只有一处这样用：
 * {@code navigation/default.json} 在 O1 定案前不入库，CI 上必然缺席。
 * 依赖它的测试必须跳过而不是失败，而<b>跳过必须打印出来</b> ——
 * 实测 Gradle 只打印 SKIPPED，不打印 assume 的理由（详见那些测试里的说明）。
 */
public record DataDir(Path root) {

    /** 系统属性。Gradle 的 test 任务已经在设它，见 {@code engine/build.gradle}。 */
    public static final String PROPERTY = "heavyseas.data.dir";

    /** 环境变量。换机器 / 在 IDE 里直接跑单个测试时用它。 */
    public static final String ENV = "HEAVYSEAS_DATA_DIR";

    public DataDir {
        Objects.requireNonNull(root, "root");
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException(
                    "数值数据目录不存在或不是目录: " + root.toAbsolutePath());
        }
    }

    /**
     * 从系统属性或环境变量定址。
     *
     * @throws IllegalStateException 两者都没有设。<b>不猜</b>，也不试探常见相对路径 ——
     *                               猜错的代价是读到另一份数据，而那种错不会报错
     */
    public static DataDir fromEnvironment() {
        String configured = System.getProperty(PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(ENV);
        }
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException(
                    "没有配置数值数据目录：请设系统属性 -D%s=<路径> 或环境变量 %s=<路径>"
                            .formatted(PROPERTY, ENV));
        }
        return new DataDir(Path.of(configured));
    }

    /**
     * 取一个必须存在的数据文件。
     *
     * @param relative 相对本目录的路径，如 {@code roster/default.json}
     * @throws IllegalArgumentException 文件不存在
     */
    public Path file(String relative) {
        Path path = resolve(relative);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("数值数据文件不存在: " + path.toAbsolutePath());
        }
        return path;
    }

    /**
     * 取一个<b>允许缺席</b>的数据文件。
     *
     * <p>❗只给真正允许缺席的文件用。返回空的含义是「这台机器上没有」，
     * 不是「路径可能写错了」—— 后者由构造函数在解析目录时就抛掉了。
     */
    public Optional<Path> optionalFile(String relative) {
        Path path = resolve(relative);
        return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
    }

    /**
     * 相对路径拼接。
     *
     * <p>❗拒绝绝对路径与 {@code ..}：{@link Path#resolve} 遇到绝对路径会<b>原样返回它</b>，
     * 于是「相对 data/ 的路径」被悄悄换成了别处的文件，且没有任何报错。
     */
    private Path resolve(String relative) {
        Objects.requireNonNull(relative, "relative");
        Path rel = Path.of(relative);
        if (rel.isAbsolute()) {
            throw new IllegalArgumentException("要的是相对 data/ 的路径，给的是绝对路径: " + relative);
        }
        for (Path segment : rel) {
            if (segment.toString().equals("..")) {
                throw new IllegalArgumentException("路径不得越出 data/ 目录: " + relative);
            }
        }
        return root.resolve(rel);
    }
}
