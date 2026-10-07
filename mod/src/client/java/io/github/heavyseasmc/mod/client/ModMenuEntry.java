package io.github.heavyseasmc.mod.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Mod Menu 的设置按钮（ADR-0099 D5）：返回我们自己的设置菜单，关的时候回到 Mod Menu 的模组列表。
 *
 * <p>❗Mod Menu 是<b>可选的</b>（{@code suggests}，编译期 {@code modCompileOnly}）：没装它时 Fabric 不会去碰
 * {@code modmenu} 这个入口，这个类就不会被加载。所以 Mod Menu 的类型<b>只许出现在这一个类里</b> ——
 * 写到别处，玩家没装 Mod Menu 时就是 {@code NoClassDefFoundError}（{@code ModMenuIsolationTest} 守着）。
 *
 * <p>❗入口必须返回非空的工厂：Mod Menu 的默认实现返回一个里面是 {@code null} 的工厂，声明了入口却不覆写，设置按钮就出不来
 * （ADR-0099 §2.2）。也不向 FCAP 登记它自带的界面（D3）。
 */
public final class ModMenuEntry implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return SettingsScreen::new;
    }
}
