package io.github.heavyseasmc.mod.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mod Menu 是可选的（ADR-0099 D5）：玩家没装它时游戏照样起得来。
 *
 * <p>Fabric 只在有人（Mod Menu 自己）去取 {@code modmenu} 那个入口时才加载 {@link ModMenuEntry}；
 * 所以 Mod Menu 的类型<b>只许出现在那一个类里</b> —— 写进别的类，没装 Mod Menu 的玩家一碰那个类就 {@code NoClassDefFoundError}，
 * 而开发环境里 Mod Menu 在编译类路径上，照样编译得过（证伪表：编译得过证明不了运行时够用）。
 * 另核 fabric.mod.json：入口指向那个类、Mod Menu 在 {@code suggests} 而不在 {@code depends}。
 */
final class ModMenuIsolationTest {

    private static final Path SOURCES = Path.of("src");
    private static final Path FABRIC_MOD = Path.of("src", "main", "resources", "fabric.mod.json");
    private static final String ENTRY = "ModMenuEntry.java";

    /** 判据本体：哪几份源码提到了 Mod Menu 的包（除了入口那一个类）。 */
    static List<String> offenders(List<Path> files) throws IOException {
        List<String> out = new ArrayList<>();
        for (Path f : files) {
            if (f.getFileName().toString().equals(ENTRY)) {
                continue;
            }
            if (Files.readString(f, StandardCharsets.UTF_8).contains("com.terraformersmc")) {
                out.add(f.toString());
            }
        }
        return out;
    }

    private static List<Path> javaSources() throws IOException {
        try (Stream<Path> walk = Files.walk(SOURCES)) {
            return walk.filter(p -> p.toString().endsWith(".java") && !p.startsWith(SOURCES.resolve("test"))).toList();
        }
    }

    @Test
    @DisplayName("Mod Menu 的类型只在 ModMenuEntry 一个类里")
    void onlyTheEntryTouchesModMenu() throws IOException {
        List<Path> files = javaSources();
        assertTrue(files.size() > 100, "只扫到 " + files.size() + " 份源码 —— 没在扫");
        assertTrue(files.stream().anyMatch(f -> f.getFileName().toString().equals(ENTRY)), "入口那个类不在扫到的文件里");
        assertEquals(List.of(), offenders(files));
    }

    @Test
    @DisplayName("fabric.mod.json：modmenu 入口指向 ModMenuEntry；Mod Menu 是 suggests、不是 depends")
    void entrypointIsDeclaredAndOptional() throws IOException {
        JsonObject mod = JsonParser.parseString(Files.readString(FABRIC_MOD, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("io.github.heavyseasmc.mod.client.ModMenuEntry",
                mod.getAsJsonObject("entrypoints").getAsJsonArray("modmenu").get(0).getAsString());
        assertTrue(mod.getAsJsonObject("suggests").has("modmenu"));
        assertFalse(mod.getAsJsonObject("depends").has("modmenu"), "Mod Menu 进了 depends：没装它的玩家进不了游戏");
    }

    @Test
    @DisplayName("红测：别的类里提到 Mod Menu 的包，判据点名它")
    void judgeNamesAnOffender(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        Path entry = Files.writeString(dir.resolve(ENTRY), "import com.terraformersmc.modmenu.api.ModMenuApi;");
        Path bad = Files.writeString(dir.resolve("SettingsScreen.java"), "import com.terraformersmc.modmenu.api.ConfigScreenFactory;");
        Path fine = Files.writeString(dir.resolve("GameHud.java"), "class GameHud {}");
        assertEquals(List.of(bad.toString()), offenders(List.of(entry, bad, fine)));
    }
}
