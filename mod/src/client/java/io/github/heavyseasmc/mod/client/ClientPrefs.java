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
 * 只属于这台客户端的偏好：界面主题（ADR-0037：浅色海图桌 / 深色船舱木作），
 * 以及对局中 Minecraft 自带 HUD 的那几条要不要露出来（{@link #vanillaHudInVoyage}，ADR-0046）。
 *
 * <p>存在 {@code config/heavyseas-client.properties}。读不到、写不了都不是错 —— 退回默认的浅色，日志里说一句；
 * 偏好丢了只是下次又是浅色，不值得为它让客户端起不来。
 */
final class ClientPrefs {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);
    private static final String THEME = "theme";
    /**
     * 对局中 Minecraft 自带的心 · 饥饿 · 护甲 · 氧气 · 经验要不要露出来、热栏要不要保持 Minecraft 自带的样子。默认不露、热栏照样张。
     * 写成 {@code vanillaHud=show} 就回到 Minecraft 自带的样子 —— 功能一个没删，只是样张那一态里没有它们（用户 2026-09-30：
     * 「不要直接删除这些功能，优先检查是否已有显示开关」）。
     */
    private static final String VANILLA_HUD = "vanillaHud";
    /**
     * 纸板开合放慢几倍（ADR-0048 · ADR-0045 §5.2 B6「动效一律用逐帧序列评审」）：200 ms 的开合 F2 截不到中间，
     * 评审时写 {@code motionSlow=15} 放慢 15 倍逐帧看。默认 1，夹在 1–50。
     */
    private static final String MOTION_SLOW = "motionSlow";
    /** 读进来的全部偏好：存盘时整份写回，换主题不会把别的项抹掉。 */
    private static final Properties PROPS = new Properties();

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
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            PROPS.load(in);
        } catch (IOException e) {
            LOGGER.warn("客户端偏好读不出来，用默认值：{}", e.toString());
            return;
        }
        GuiLanguage.setTheme("dark".equals(PROPS.getProperty(THEME)) ? GuiLanguage.Theme.DARK : GuiLanguage.Theme.LIGHT);
        LOGGER.info("对局中 Minecraft 自带 HUD：{}", vanillaHudInVoyage() ? "露出（vanillaHud=show）" : "按样张收起");
        if (motionSlow() > 1) {
            LOGGER.info("动效放慢 {} 倍（motionSlow，评审用）", motionSlow());
        }
    }

    /** 纸板开合放慢几倍（评审用，默认 1）。 */
    static int motionSlow() {
        try {
            return Math.max(1, Math.min(50, Integer.parseInt(PROPS.getProperty(MOTION_SLOW, "1").trim())));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** 对局中要不要照 Minecraft 自带的样子画心 · 饥饿 · 经验与热栏（默认不要，照样张 b-1）。 */
    static boolean vanillaHudInVoyage() {
        return "show".equals(PROPS.getProperty(VANILLA_HUD));
    }

    /** 换到另一个主题并存盘。 */
    static void toggleTheme() {
        GuiLanguage.Theme next = GuiLanguage.theme() == GuiLanguage.Theme.DARK ? GuiLanguage.Theme.LIGHT : GuiLanguage.Theme.DARK;
        GuiLanguage.setTheme(next);
        LOGGER.info("界面主题：{}", next);
        PROPS.setProperty(THEME, next == GuiLanguage.Theme.DARK ? "dark" : "light");
        try (Writer out = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
            PROPS.store(out, "Heavy Seas client preferences");
        } catch (IOException e) {
            LOGGER.warn("客户端偏好没存上（这次的主题只到退出为止）：{}", e.toString());
        }
    }
}
