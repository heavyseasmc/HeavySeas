package io.github.heavyseasmc.mod.config;

import java.util.List;

/**
 * 设置菜单「本机」那一组：只属于这台客户端的偏好（ADR-0099 §2.4），存在 {@code heavyseas-client.toml}（客户端的 {@code ClientPrefs}）。
 *
 * <p>与服务端那张表同一种写法（{@link SettingDef}），界面按同一套控件画 —— 区别只在<b>改了当场生效、当场存</b>
 * （与 F8 · H 一样），不攒着按 S。{@code motion_slow} 只给开发评审用，不进这张表、不进菜单。
 */
public final class LocalSettings {

    public static final String THEME = "client.theme";
    public static final String VANILLA_HUD = "client.show_vanilla_hud";
    public static final String LEGEND = "client.show_legend";

    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";

    public static final List<SettingDef> ROWS = List.of(
            SettingDef.choice(SettingsCategory.LOCAL, THEME, THEME_LIGHT, List.of(THEME_LIGHT, THEME_DARK),
                    SettingDef.When.IMMEDIATE, "Interface theme (F8)."),
            SettingDef.flag(SettingsCategory.LOCAL, VANILLA_HUD, false, SettingDef.When.IMMEDIATE,
                    "Minecraft's hotbar style when visible; does not override dimension visibility."),
            SettingDef.flag(SettingsCategory.LOCAL, LEGEND, true, SettingDef.When.IMMEDIATE,
                    "Icon legend under the status plate (H)."));

    private LocalSettings() {
    }
}
