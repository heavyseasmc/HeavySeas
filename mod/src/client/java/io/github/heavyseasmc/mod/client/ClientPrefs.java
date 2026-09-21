package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 只属于这台客户端的偏好：现在只有界面主题（ADR-0037：浅色海图桌 / 深色船舱木作）。
 *
 * <p>存在 {@code config/heavyseas-client.properties}。读不到、写不了都不是错 —— 退回默认的浅色，日志里说一句；
 * 偏好丢了只是下次又是浅色，不值得为它让客户端起不来。
 */
final class ClientPrefs {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);
    private static final String THEME = "theme";

    private ClientPrefs() {
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("heavyseas-client.properties");
    }

    static void load() {
        Path file = file();
        if (!Files.isRegularFile(file)) {
            return;
        }
        Properties props = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            props.load(in);
        } catch (IOException e) {
            LOGGER.warn("客户端偏好读不出来，用默认值：{}", e.toString());
            return;
        }
        GuiLanguage.setTheme("dark".equals(props.getProperty(THEME)) ? GuiLanguage.Theme.DARK : GuiLanguage.Theme.LIGHT);
    }

    /** 换到另一个主题并存盘。 */
    static void toggleTheme() {
        GuiLanguage.Theme next = GuiLanguage.theme() == GuiLanguage.Theme.DARK ? GuiLanguage.Theme.LIGHT : GuiLanguage.Theme.DARK;
        GuiLanguage.setTheme(next);
        LOGGER.info("界面主题：{}", next);
        Properties props = new Properties();
        props.setProperty(THEME, next == GuiLanguage.Theme.DARK ? "dark" : "light");
        try (Writer out = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
            props.store(out, "Heavy Seas client preferences");
        } catch (IOException e) {
            LOGGER.warn("客户端偏好没存上（这次的主题只到退出为止）：{}", e.toString());
        }
    }
}
