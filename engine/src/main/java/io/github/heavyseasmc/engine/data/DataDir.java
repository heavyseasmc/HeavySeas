package io.github.heavyseasmc.engine.data;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * {@code data/} 目录的定址。
 *
 * <h2>找不到就抛，不退回默认值</h2>
 * 这条是踩出来的：管线里另一个解析器找不到仓库时会<b>静默退回</b>到一个内部默认目录，
 * 结果四个脚本全写进了一份已作废的副本，无一报错。静默退回让「配错了」与
 * 「这台机器上没有」输出完全相同，而这两件事要做的处置正相反。
 * 所以本类只有一个逃生口：显式的系统属性或环境变量。
 *
 * <h2>没有「允许缺席」这一档</h2>
 * 曾经有过一个返回 {@code Optional} 的取文件口，给的是「这台机器上确实没有这份数据」
 * 这唯一一种合法缺席（航海牌数据当时不入库）。那份数据落库之后它再也不会被触发，
 * 于是删掉了：<b>一条永远走不到的分支与一道只见过绿灯的闸门是同一种东西。</b>
 * 真需要「可有可无的数据」时再加回来，并且同时加一个能让它返回空的测试。
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
