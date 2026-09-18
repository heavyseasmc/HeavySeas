package io.github.heavyseasmc.mod.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本模组的 mixin 只许在一个包里，而且包里的每一个都要登记进 {@code heavyseas.client.mixins.json}（ADR-0034 §8）。
 *
 * <p>没登记的 mixin 不会加载，而游戏照跑 —— 雾就是安静地没有。登记了却不存在的类则会让客户端启动即崩。
 * 两个方向都在这里红；正向对照：包里至少要有一个（扫错目录时什么都扫不到）。
 * 顺带核 refmap 的名字与 build.gradle 里写的是同一个：对不上时生产客户端的注入安静地失败。
 */
final class MixinInventoryTest {

    private static final Path MIXIN_DIR = Path.of("src", "client", "java", "io", "github", "heavyseasmc", "mod", "client", "mixin");
    private static final Path CONFIG = Path.of("src", "client", "resources", "heavyseas.client.mixins.json");
    private static final Path FABRIC_MOD = Path.of("src", "main", "resources", "fabric.mod.json");
    private static final Path BUILD_GRADLE = Path.of("build.gradle");

    @Test
    void everyMixinInThePackageIsRegisteredAndNothingElseIs() throws IOException {
        Set<String> onDisk = new TreeSet<>();
        try (Stream<Path> listing = Files.list(MIXIN_DIR)) {
            listing.filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> onDisk.add(p.getFileName().toString().replace(".java", "")));
        }
        assertTrue(!onDisk.isEmpty(), "mixin 包里一个类都没扫到（" + MIXIN_DIR.toAbsolutePath() + "）—— 没在扫，不是干净");

        JsonObject config = JsonParser.parseString(Files.readString(CONFIG, StandardCharsets.UTF_8)).getAsJsonObject();
        Set<String> registered = new TreeSet<>();
        config.getAsJsonArray("client").forEach(e -> registered.add(e.getAsString()));
        assertTrue(!config.has("mixins") || config.getAsJsonArray("mixins").isEmpty(),
                "本模组只该有客户端 mixin（服务端那一侧不注入任何东西）");
        assertEquals(onDisk, registered, "mixin 包里的类与 mixins.json 登记的必须一一对应");
        assertEquals("io.github.heavyseasmc.mod.client.mixin", config.get("package").getAsString());
        assertEquals(1, config.getAsJsonObject("injectors").get("defaultRequire").getAsInt(),
                "defaultRequire 必须是 1：目标漂移要在启动时崩，不能安静地没雾");

        String fabricMod = Files.readString(FABRIC_MOD, StandardCharsets.UTF_8);
        assertTrue(fabricMod.contains("\"heavyseas.client.mixins.json\""), "fabric.mod.json 没登记 mixin 配置");

        // 这一版 Loom 在 remapJar 时直接翻注解、不生成 refmap（实测：jar 里没有那个文件，而类里已是 class_758 / method_3211）。
        // 写了 refmap 只会让生产客户端多一行「找不到 refmap」。jar 那一侧由 build.gradle 的 checkMixinRemapped 守。
        assertFalse(config.has("refmap"), "mixins.json 不该指向 refmap：这一版 Loom 不生成它");
        assertTrue(Files.readString(BUILD_GRADLE, StandardCharsets.UTF_8).contains("checkMixinRemapped"),
                "build.gradle 里没有 checkMixinRemapped 那道闸");
    }
}
