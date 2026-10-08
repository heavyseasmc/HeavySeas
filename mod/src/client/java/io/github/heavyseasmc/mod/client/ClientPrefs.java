package io.github.heavyseasmc.mod.client;

import fuzs.forgeconfigapiport.fabric.api.neoforge.v4.NeoForgeConfigRegistry;
import fuzs.forgeconfigapiport.fabric.api.neoforge.v4.NeoForgeModConfigEvents;
import io.github.heavyseasmc.mod.HeavySeasMod;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 只属于这台客户端的偏好：界面主题（ADR-0037：浅色海图桌 / 深色船舱木作），
 * 以及可见热栏要不要采用 Minecraft 自带样式（{@link #vanillaHudInVoyage}）。
 *
 * <p>存在 {@code config/heavyseas-client.toml}，交给 Forge Config API Port 的 CLIENT 类型管（ADR-0099 D1）：
 * 改值一律经它自己的对象（{@code set} + {@code save}），不手写文件。<b>只在物理客户端上登记</b>（TLM 教训 A3：
 * 专用服务端的 config 目录里不该出现玩家偏好文件；判物理端，不判「是不是专用服务端」—— 开了局域网的客户端仍要这一份）。
 *
 * <p>老版本存在 {@code heavyseas-client.properties}：登记之前读进来，登记之后写进新文件，旧文件改名留作备份（{@link ClientPrefsMigration}）。
 *
 * <p>读不到、写不了都不是错 —— 退回默认值，日志里说一句；偏好丢了只是下次又是浅色，不值得为它让客户端起不来。
 */
public final class ClientPrefs {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);
    static final String FILE = "heavyseas-client.toml";

    static final ModConfigSpec SPEC;
    private static final ModConfigSpec.EnumValue<GuiLanguage.Theme> THEME;
    /**
     * 可见热栏的样式偏好。保留旧配置键，但不再覆盖雾海维度的隐藏规则（用户 2026-10-09）。
     */
    private static final ModConfigSpec.BooleanValue VANILLA_HUD;
    /**
     * 主画面状态牌下面那块图例展开着没有（用户 2026-10-07，样张 A；H 收起 / 展开）。
     * 第一次默认展开，之后记着上一次 —— 看熟了的人收起来就不再每局弹出来。
     */
    private static final ModConfigSpec.BooleanValue LEGEND;
    /**
     * 纸板开合放慢几倍（ADR-0048 · ADR-0045 §5.2 B6「动效一律用逐帧序列评审」）：200 ms 的开合 F2 截不到中间，
     * 评审时写 {@code motion_slow = 15} 放慢 15 倍逐帧看。默认 1，夹在 1–50。
     * ❗<b>只给开发评审用，只在文件里改，不进设置菜单</b>（ADR-0099 §2.4「不进菜单」那一列）。
     */
    private static final ModConfigSpec.IntValue MOTION_SLOW;

    // ❗toml 里的注释只写英文：这个目录里源码字面量中的非 ASCII 字都要在 GUI 字体子集里（GuiGlyphCoverageTest），
    //   而注释只落进文件、不上屏幕 —— 为它扩字体子集不值得。
    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        THEME = builder.comment("Interface theme: LIGHT (chart table) or DARK (cabin wood). F8 switches it.")
                .defineEnum("theme", GuiLanguage.Theme.LIGHT);
        VANILLA_HUD = builder.comment("Use Minecraft's hotbar style when visible. Mist sea hides bars except in creative and spectator modes.")
                .define("show_vanilla_hud", false);
        LEGEND = builder.comment("Show the icon legend under the status plate. H switches it.")
                .define("show_legend", true);
        MOTION_SLOW = builder.comment("Development only, not in the settings menu: slow panel motion down N times to review it frame by frame.")
                .defineInRange("motion_slow", 1, 1, 50);
        SPEC = builder.build();
    }

    private ClientPrefs() {
    }

    static void load() {
        // 物理端才登记（TLM A3）。客户端入口本来只在物理客户端上跑，这一道把前提写成代码。
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT) {
            LOGGER.warn("客户端偏好：不是物理客户端，不登记 {}", FILE);
            return;
        }
        Path dir = FabricLoader.getInstance().getConfigDir();
        // ❗旧文件要在登记之前读进来（TLM A7）
        Optional<ClientPrefsMigration.Prefs> legacy = Optional.empty();
        try {
            legacy = ClientPrefsMigration.readLegacy(dir, FILE);
        } catch (IOException | RuntimeException e) {          // 读坏了的旧偏好不值得让客户端起不来（审查 2026-10-07 C3）
            LOGGER.warn("客户端偏好：旧文件 {} 读不出来，不迁了，用默认值：{}", ClientPrefsMigration.LEGACY_FILE, e.toString());
        }
        try {
            NeoForgeConfigRegistry.INSTANCE.register(HeavySeasMod.MOD_ID, ModConfig.Type.CLIENT, SPEC, FILE);
        } catch (RuntimeException e) {
            LOGGER.warn("客户端偏好：{} 开不了，这次按默认值、改了也存不上：{}", FILE, e.toString());
            return;
        }
        legacy.ifPresent(prefs -> migrate(dir, prefs));
        GuiLanguage.setTheme(THEME.get());
        // 有人手改了文件（FCAP 的文件监视在自己的线程上回调）：主题回到渲染线程上再换
        NeoForgeModConfigEvents.reloading(HeavySeasMod.MOD_ID).register(config -> {
            if (config.getSpec() == SPEC) {
                MinecraftClient.getInstance().execute(() -> GuiLanguage.setTheme(theme()));
            }
        });
        LOGGER.info("可见热栏样式：{}", vanillaHudInVoyage() ? "Minecraft" : "对局样式");
        if (motionSlow() > 1) {
            LOGGER.info("动效放慢 {} 倍（motion_slow，评审用）", motionSlow());
        }
    }

    private static void migrate(Path dir, ClientPrefsMigration.Prefs prefs) {
        if (!write(prefs)) {
            return;                           // 没写进去：旧文件留着不动，下次起来新文件已在，不会再迁 —— 说过一句了
        }
        try {
            Path backup = ClientPrefsMigration.retire(dir);
            LOGGER.info("客户端偏好：从 {} 迁到 {}（{}），旧文件改名为 {} 留作备份", ClientPrefsMigration.LEGACY_FILE, FILE, prefs, backup.getFileName());
        } catch (IOException e) {
            LOGGER.warn("客户端偏好：已迁到 {}，旧文件 {} 改名失败（留在原处，不会再迁）：{}", FILE, ClientPrefsMigration.LEGACY_FILE, e.toString());
        }
    }

    /** 四项一起写进新文件（迁移用）。经 FCAP 自己的对象写，存一次盘。 */
    static boolean write(ClientPrefsMigration.Prefs prefs) {
        THEME.set(prefs.theme());
        VANILLA_HUD.set(prefs.vanillaHud());
        LEGEND.set(prefs.legend());
        MOTION_SLOW.set(prefs.motionSlow());
        RuntimeException failed = trySave();
        if (failed != null) {
            LOGGER.warn("客户端偏好没存上（旧文件的偏好没迁进去，这次按迁过来的值、下次起来是默认值）：{}", failed.toString());
        }
        return failed == null;
    }

    private static boolean loaded() {
        return SPEC.isLoaded();
    }

    /** 文件里存着的主题（没加载时是浅色）。 */
    static GuiLanguage.Theme theme() {
        return loaded() ? THEME.get() : GuiLanguage.Theme.LIGHT;
    }

    /** 纸板开合放慢几倍（评审用，默认 1）。 */
    static int motionSlow() {
        return loaded() ? MOTION_SLOW.get() : 1;
    }

    /** 可见热栏是否采用 Minecraft 自带样式；不控制雾海中的可见性。 */
    static boolean vanillaHudInVoyage() {
        return loaded() ? VANILLA_HUD.get() : vanillaHudFallback;
    }

    /** 换到另一个主题并存盘。 */
    static void toggleTheme() {
        setTheme(GuiLanguage.theme() == GuiLanguage.Theme.DARK ? GuiLanguage.Theme.LIGHT : GuiLanguage.Theme.DARK);
    }

    /** 换到这个主题并存盘（F8 与设置菜单「本机」那一组都走这里）。 */
    public static void setTheme(GuiLanguage.Theme next) {
        GuiLanguage.setTheme(next);
        LOGGER.info("界面主题：{}", next);
        if (loaded()) {
            THEME.set(next);
            RuntimeException failed = trySave();
            if (failed != null) {
                LOGGER.warn("客户端偏好没存上（这次的主题只到退出为止）：{}", failed.toString());
            }
        }
    }

    /** 偏好文件没开起来时这两项只记在这里，到退出为止。 */
    private static boolean legendFallback = true;
    private static boolean vanillaHudFallback = false;

    static boolean legendShown() {
        return loaded() ? LEGEND.get() : legendFallback;
    }

    static void toggleLegend() {
        setLegend(!legendShown());
    }

    /** 图例展开 / 收起并存盘（H 与设置菜单都走这里）。 */
    public static void setLegend(boolean show) {
        LOGGER.info("图例：{}", show ? "展开" : "收起");
        if (loaded()) {
            LEGEND.set(show);
            RuntimeException failed = trySave();
            if (failed != null) {
                LOGGER.warn("客户端偏好没存上（这次的展开 / 收起只到退出为止）：{}", failed.toString());
            }
        } else {
            legendFallback = show;
        }
    }

    /** 可见热栏的样式偏好，并存盘（设置菜单「本机」那一组）。 */
    public static void setVanillaHud(boolean show) {
        LOGGER.info("可见热栏样式：{}", show ? "Minecraft" : "对局样式");
        if (loaded()) {
            VANILLA_HUD.set(show);
            RuntimeException failed = trySave();
            if (failed != null) {
                LOGGER.warn("客户端偏好没存上（这次的 HUD 开关只到退出为止）：{}", failed.toString());
            }
        } else {
            vanillaHudFallback = show;
        }
    }

    /**
     * 设置菜单「本机」那一组的读写口（{@link io.github.heavyseasmc.mod.config.LocalSettings} 的三项）：读的是此刻生效的值，
     * 写的时候当场生效、当场存盘 —— 与 F8 · H 同一条路。
     */
    static io.github.heavyseasmc.mod.ui.SettingsMenuState.LocalPrefs menuAccess() {
        return new io.github.heavyseasmc.mod.ui.SettingsMenuState.LocalPrefs() {
            @Override
            public String get(String key) {
                return switch (key) {
                    case io.github.heavyseasmc.mod.config.LocalSettings.THEME -> GuiLanguage.theme() == GuiLanguage.Theme.DARK
                            ? io.github.heavyseasmc.mod.config.LocalSettings.THEME_DARK
                            : io.github.heavyseasmc.mod.config.LocalSettings.THEME_LIGHT;
                    case io.github.heavyseasmc.mod.config.LocalSettings.VANILLA_HUD -> String.valueOf(vanillaHudInVoyage());
                    case io.github.heavyseasmc.mod.config.LocalSettings.LEGEND -> String.valueOf(legendShown());
                    default -> throw new IllegalArgumentException("本机设置里没有 " + key);
                };
            }

            @Override
            public void set(String key, String value) {
                switch (key) {
                    case io.github.heavyseasmc.mod.config.LocalSettings.THEME -> setTheme(
                            io.github.heavyseasmc.mod.config.LocalSettings.THEME_DARK.equals(value)
                                    ? GuiLanguage.Theme.DARK : GuiLanguage.Theme.LIGHT);
                    case io.github.heavyseasmc.mod.config.LocalSettings.VANILLA_HUD -> setVanillaHud(Boolean.parseBoolean(value));
                    case io.github.heavyseasmc.mod.config.LocalSettings.LEGEND -> setLegend(Boolean.parseBoolean(value));
                    default -> throw new IllegalArgumentException("本机设置里没有 " + key);
                }
            }
        };
    }

    /**
     * 存盘（FCAP 写文件，原子替换）。写不了不是错：调用方说一句，这次的改动只到退出为止。
     *
     * @return 失败的那个异常；存上了是 {@code null}
     */
    private static RuntimeException trySave() {
        try {
            SPEC.save();
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }
}
