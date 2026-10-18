package io.github.heavyseasmc.mod.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 玩家看得见的名单一律按当前语言的分隔符连（{@code heavyseas.nav.name_sep}：中文「、」，英文「, 」），不写死中文顿号。
 *
 * <p>2026-10-13 验收 L08：英文客户端上，落海一面的「Going overboard: Jeweller、Collector、……」仍是中文顿号 ——
 * 播报那一路早就走 {@code NavCardText.join}，这一面、获胜者名单、阵亡名单各自又写了一份 {@code "、"}。
 */
class NameSeparatorTest {

    private static final List<Path> ROOTS = List.of(Path.of("src", "main", "java"), Path.of("src", "client", "java"));
    /** 少于这么多份源码就是没在扫。 */
    private static final int MIN_FILES = 100;

    /** 写着「、」但不进玩家的名单的：整句本来就是中文（日志 · 管理员指令的回话），或者已经按语言分开（给大模型的问句）。 */
    private static final Map<String, String> ALLOWED = Map.of(
            "SettingsSync.java", "服务端日志里的一行中文",
            "SeatQuestions.java", "给大模型的问句，已按 en 分开写",
            "StandInMinds.java", "管理员指令回话，整句中文",
            "LlmConfigFile.java", "配置文件的报错，整句中文");

    @Test
    @DisplayName("源码里不再拿「、」字面量连名单（白名单里是整句中文或已按语言分开的）")
    void noHardcodedChineseListSeparator() throws IOException {
        Pattern sep = Pattern.compile("\"、\"");
        assertTrue(sep.matcher("names.append(\"、\");").find(), "模式认不出自己要拦的写法");
        List<String> problems = new ArrayList<>();
        int files = 0;
        for (Path root : ROOTS) {
            List<Path> all;
            try (Stream<Path> walk = Files.walk(root)) {
                all = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
            }
            for (Path file : all) {
                files++;
                if (ALLOWED.containsKey(file.getFileName().toString())) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    String t = lines.get(i).trim();
                    if (t.startsWith("//") || t.startsWith("*")) {
                        continue;
                    }
                    if (sep.matcher(lines.get(i)).find()) {
                        problems.add(file.getFileName() + ":" + (i + 1) + "  " + t);
                    }
                }
            }
        }
        assertTrue(files >= MIN_FILES, "只扫到 " + files + " 份源码 —— 没在扫，不是干净");
        assertTrue(problems.isEmpty(), "名单写死了中文顿号（英文下照样是「、」，改用 heavyseas.nav.name_sep）：\n  "
                + String.join("\n  ", problems));
    }
}
