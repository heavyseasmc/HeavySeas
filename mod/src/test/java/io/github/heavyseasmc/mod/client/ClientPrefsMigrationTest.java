package io.github.heavyseasmc.mod.client;

import com.electronwill.nightconfig.core.CommentedConfig;
import net.neoforged.fml.config.IConfigSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 旧的 {@code heavyseas-client.properties} 迁到 FCAP 那一份（ADR-0099 D1）：四个键的读法与改之前一字不差，
 * 迁完之后读口（{@link ClientPrefs}）读到的与旧文件里写的一样 —— 尤其是 {@code legend=hide} 的人，图例照旧收着。
 */
final class ClientPrefsMigrationTest {

    @TempDir
    Path dir;

    /** 一份内存里的「已加载的配置」，代替 FCAP 起来时读进来的那份文件。 */
    private static final class Loaded implements IConfigSpec.ILoadedConfig {
        final CommentedConfig config = CommentedConfig.inMemory();
        int saves;

        @Override
        public CommentedConfig config() {
            return config;
        }

        @Override
        public void save() {
            saves++;
        }
    }

    private Loaded loaded;

    @AfterEach
    void unload() {
        if (loaded != null) {
            ClientPrefs.SPEC.acceptConfig(null);
        }
    }

    private static Properties props(String... keyValues) {
        Properties p = new Properties();
        for (int i = 0; i < keyValues.length; i += 2) {
            p.setProperty(keyValues[i], keyValues[i + 1]);
        }
        return p;
    }

    @Test
    @DisplayName("读法与改之前的 getter 一样：dark 才深 · show 才露 · hide 才收 · motionSlow 夹在 1–50")
    void legacyValuesReadExactlyLikeTheOldGetters() {
        assertEquals(ClientPrefsMigration.Prefs.DEFAULTS, ClientPrefsMigration.fromProperties(props()), "空文件 = 默认值");
        ClientPrefsMigration.Prefs all = ClientPrefsMigration.fromProperties(
                props("theme", "dark", "vanillaHud", "show", "legend", "hide", "motionSlow", "15"));
        assertEquals(new ClientPrefsMigration.Prefs(GuiLanguage.Theme.DARK, true, false, 15), all);
        assertEquals(GuiLanguage.Theme.LIGHT, ClientPrefsMigration.fromProperties(props("theme", "light")).theme());
        assertFalse(ClientPrefsMigration.fromProperties(props("vanillaHud", "hide")).vanillaHud(), "只认 show");
        assertTrue(ClientPrefsMigration.fromProperties(props("legend", "show")).legend());
        assertEquals(50, ClientPrefsMigration.fromProperties(props("motionSlow", "99")).motionSlow());
        assertEquals(1, ClientPrefsMigration.fromProperties(props("motionSlow", "x")).motionSlow());
    }

    @Test
    @DisplayName("legend=hide 的人迁完之后图例照旧收着（读口读到的是迁过去的值，不是新 spec 的默认值）")
    void hiddenLegendStaysHiddenAfterMigration() throws IOException {
        Files.writeString(dir.resolve(ClientPrefsMigration.LEGACY_FILE),
                "#Heavy Seas client preferences\nlegend=hide\ntheme=dark\n", StandardCharsets.UTF_8);
        Optional<ClientPrefsMigration.Prefs> legacy = ClientPrefsMigration.readLegacy(dir, ClientPrefs.FILE);
        assertTrue(legacy.isPresent(), "新文件不在、旧文件在：要迁");

        // 「登记」：新 spec 补齐默认值（图例默认展开）后装上 —— 与 FCAP 新建文件同一个顺序
        loaded = new Loaded();
        ClientPrefs.SPEC.correct(loaded.config);
        ClientPrefs.SPEC.acceptConfig(loaded);
        assertTrue(ClientPrefs.legendShown(), "前提：新 spec 的默认是展开");

        assertTrue(ClientPrefs.write(legacy.get()));
        assertFalse(ClientPrefs.legendShown(), "迁完之后图例该是收着的");
        assertEquals(GuiLanguage.Theme.DARK, ClientPrefs.theme());
        assertFalse(ClientPrefs.vanillaHudInVoyage());
        assertEquals(false, loaded.config.get(List.of("show_legend")), "写进了配置本身，不只是缓存");
        assertEquals(1, loaded.saves, "经 FCAP 的对象存了一次盘");

        Path backup = ClientPrefsMigration.retire(dir);
        assertFalse(Files.exists(dir.resolve(ClientPrefsMigration.LEGACY_FILE)), "旧文件改名了");
        assertTrue(Files.readString(backup, StandardCharsets.UTF_8).contains("legend=hide"), "备份里原样留着");
    }

    @Test
    @DisplayName("设置菜单「本机」那一组：经 ClientPrefs 改值、当场存盘，读口读到的就是改后的值")
    void menuWritesPersistThroughClientPrefs() {
        loaded = new Loaded();
        ClientPrefs.SPEC.correct(loaded.config);
        ClientPrefs.SPEC.acceptConfig(loaded);
        GuiLanguage.Theme before = GuiLanguage.theme();
        try {
            var prefs = ClientPrefs.menuAccess();
            prefs.set(io.github.heavyseasmc.mod.config.LocalSettings.LEGEND, "false");
            prefs.set(io.github.heavyseasmc.mod.config.LocalSettings.VANILLA_HUD, "true");
            prefs.set(io.github.heavyseasmc.mod.config.LocalSettings.THEME, io.github.heavyseasmc.mod.config.LocalSettings.THEME_DARK);
            assertFalse(ClientPrefs.legendShown());
            assertTrue(ClientPrefs.vanillaHudInVoyage());
            assertEquals(GuiLanguage.Theme.DARK, ClientPrefs.theme());
            assertEquals("false", prefs.get(io.github.heavyseasmc.mod.config.LocalSettings.LEGEND));
            assertEquals(io.github.heavyseasmc.mod.config.LocalSettings.THEME_DARK,
                    prefs.get(io.github.heavyseasmc.mod.config.LocalSettings.THEME));
            assertEquals(false, loaded.config.get(List.of("show_legend")), "写进了配置本身");
            assertEquals(true, loaded.config.get(List.of("show_vanilla_hud")));
            assertEquals(3, loaded.saves, "每改一项当场存一次盘（与 F8 · H 一样）");
        } finally {
            GuiLanguage.setTheme(before);
        }
    }

    @Test
    @DisplayName("新文件已经在了就不迁（再迁会把新文件里改过的值盖回旧值）；没有旧文件也不迁")
    void migratesOnlyOnce() throws IOException {
        assertTrue(ClientPrefsMigration.readLegacy(dir, ClientPrefs.FILE).isEmpty(), "两个都没有");
        Files.writeString(dir.resolve(ClientPrefsMigration.LEGACY_FILE), "legend=hide\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(ClientPrefs.FILE), "show_legend = true\n", StandardCharsets.UTF_8);
        assertTrue(ClientPrefsMigration.readLegacy(dir, ClientPrefs.FILE).isEmpty(), "新文件在：不碰旧的");
    }

    @Test
    @DisplayName("备份名被占用了就往后加序号，不覆盖")
    void retireNeverOverwritesAnOldBackup() throws IOException {
        Files.writeString(dir.resolve(ClientPrefsMigration.BACKUP_FILE), "上一次的备份", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(ClientPrefsMigration.LEGACY_FILE), "legend=hide\n", StandardCharsets.UTF_8);
        Path second = ClientPrefsMigration.retire(dir);
        assertEquals(ClientPrefsMigration.BACKUP_FILE + "-2", second.getFileName().toString());
        assertEquals("上一次的备份", Files.readString(dir.resolve(ClientPrefsMigration.BACKUP_FILE), StandardCharsets.UTF_8));
    }
}
