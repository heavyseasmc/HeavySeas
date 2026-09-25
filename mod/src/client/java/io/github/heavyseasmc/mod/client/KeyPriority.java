package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.state.GameComponents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 对局进行中，本模组的按键先接（用户 2026-09-25；ADR-0042）。
 *
 * <p>1.21.1 的 {@code KeyBinding.KEY_TO_BINDINGS} 一个键只存<b>一个</b>绑定：两个绑定撞在同一个键上时，
 * 谁接到取决于 HashMap 的遍历顺序，另一个永远收不到，而且不报错。实测（2026-09-25）：航海日志的 L 与 Minecraft 自带的
 * 「进度」撞，后者赢 —— 世界视图里按 L 只会打开进度界面，右栏一次都钉不住；行动的 G 与 Simple Voice Chat
 * 的「语音群组」撞，本模组赢。与其一个个换默认键（玩家改键、别的模组加键之后照样会撞），不如在对局进行中
 * 让本模组先接：对局外一切照旧，对局里本模组的几个键一定到本模组手上。
 *
 * <p>只管世界视图：界面开着时按键不走按键绑定，本模组的界面各自用 {@code matchesKey} 接（{@link GameScreen}）。
 * 「对局进行中」= 这个世界的投影是活的（{@code HudView.active}，旁观者也算；终局到收起会话为止都算）。
 */
public final class KeyPriority {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private KeyPriority() {
    }

    /** 这个键此刻该不该由本模组接：对局进行中，且它是本模组某个绑定的键。是就返回那个绑定，否则 null。 */
    public static KeyBinding claim(InputUtil.Key key) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.world == null || !GameComponents.of(client.world).hudView().active()) {
            return null;
        }
        return ours(key);
    }

    /** 本模组绑在这个键上的那个绑定（不看对局）；没有就是 null。 */
    public static KeyBinding ours(InputUtil.Key key) {
        for (KeyBinding binding : HeavySeasClient.ownKeys()) {
            if (!binding.isUnbound() && key.equals(KeyBindingHelper.getBoundKeyOf(binding))) {
                return binding;
            }
        }
        return null;
    }

    /**
     * 本模组从别的绑定手里接走了一次按键 —— 只在真的撞键时记（不撞时本来就归本模组，记了是噪音）。
     * 回归脚本（keys_test.py）读这一行与「航海日志：钉住」两件事：前者说「接走了」，后者说「接走之后真的生效了」。
     */
    public static void noteTakeover(InputUtil.Key key, KeyBinding ours, KeyBinding wouldHaveGone) {
        if (wouldHaveGone != null && wouldHaveGone != ours) {
            LOGGER.info("按键：对局中 {} 归本模组 {}（原本会给 {}）",
                    key.getTranslationKey(), ours.getTranslationKey(), wouldHaveGone.getTranslationKey());
        }
    }
}
