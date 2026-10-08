package io.github.heavyseasmc.mod.client;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Properties;

/**
 * 旧的 {@code config/heavyseas-client.properties} → 新的 {@code heavyseas-client.toml}（ADR-0099 D1）。
 *
 * <p>纯读写，不碰 Forge Config API Port：<b>要在登记新 spec 之前</b>把旧值读进来（TLM 教训 A7：先登记的话，FCAP 会按新 spec
 * 补齐默认值，旧值从此没人认领），登记之后再经 FCAP 自己的对象写进新文件（{@link ClientPrefs#write}）。
 *
 * <p>四个键的读法与改之前的 {@code ClientPrefs} 一字不差：
 * {@code theme=dark} 才是深色 · {@code vanillaHud=show} 才露出 · {@code legend=hide} 才收起（没写 = 展开）·
 * {@code motionSlow} 夹在 1–50，读不出来是 1。
 */
final class ClientPrefsMigration {

    static final String LEGACY_FILE = "heavyseas-client.properties";
    /** 迁完之后旧文件改成这个名字留作备份 —— 不删玩家的东西。 */
    static final String BACKUP_FILE = "heavyseas-client.properties.migrated";

    private ClientPrefsMigration() {
    }

    /** 一台客户端的四项偏好。 */
    record Prefs(GuiLanguage.Theme theme, boolean vanillaHud, boolean legend, int motionSlow) {

        static final Prefs DEFAULTS = new Prefs(GuiLanguage.Theme.LIGHT, false, true, 1);
    }

    /** 旧文件里的值 → 四项。读法照改之前的那几个 getter。 */
    static Prefs fromProperties(Properties props) {
        GuiLanguage.Theme theme = "dark".equals(props.getProperty("theme")) ? GuiLanguage.Theme.DARK : GuiLanguage.Theme.LIGHT;
        boolean vanillaHud = "show".equals(props.getProperty("vanillaHud"));
        boolean legend = !"hide".equals(props.getProperty("legend"));
        int motionSlow;
        try {
            motionSlow = Math.max(1, Math.min(50, Integer.parseInt(props.getProperty("motionSlow", "1").trim())));
        } catch (NumberFormatException e) {
            motionSlow = 1;
        }
        return new Prefs(theme, vanillaHud, legend, motionSlow);
    }

    /**
     * 要不要迁、迁什么：新文件还不存在、旧文件在，才读旧文件。新文件已经在了就不碰旧的 ——
     * 再迁一次会把玩家在新文件里改过的值盖回旧值。
     *
     * @param newFile 新文件名（在 {@code configDir} 下）
     * @return 旧文件里的值；不用迁时为空
     * @throws IOException 旧文件在却读不出来（调用方说一句、按默认值走，不让客户端起不来）。
     *                     残缺的 {@code \\u} 转义（{@code Properties.load} 抛的是 {@code IllegalArgumentException}）也包成这个 ——
     *                     原先它一路冒到客户端入口，客户端起不来（审查 2026-10-07 C3，同一个毛病在服务端的密钥文件上）
     */
    static Optional<Prefs> readLegacy(Path configDir, String newFile) throws IOException {
        Path legacy = configDir.resolve(LEGACY_FILE);
        if (Files.exists(configDir.resolve(newFile)) || !Files.isRegularFile(legacy)) {
            return Optional.empty();
        }
        Properties props = new Properties();
        try (Reader in = Files.newBufferedReader(legacy, StandardCharsets.UTF_8)) {
            props.load(in);
        } catch (IllegalArgumentException malformed) {
            throw new IOException(legacy + " 里有残缺的转义：" + malformed.getMessage(), malformed);
        }
        return Optional.of(fromProperties(props));
    }

    /**
     * 迁完了：旧文件改名留作备份。备份名已被占用（迁过一次又把旧文件拷回来了）就在后面加序号，不覆盖。
     *
     * @return 改成了哪个文件
     */
    static Path retire(Path configDir) throws IOException {
        Path legacy = configDir.resolve(LEGACY_FILE);
        Path target = configDir.resolve(BACKUP_FILE);
        for (int i = 2; Files.exists(target); i++) {
            target = configDir.resolve(BACKUP_FILE + "-" + i);
        }
        return Files.move(legacy, target, StandardCopyOption.ATOMIC_MOVE);
    }
}
