package io.github.heavyseasmc.mod.client.mixin;

import io.github.heavyseasmc.mod.client.KeyPriority;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * 对局进行中，本模组的按键先于 Minecraft 自带的绑定与别的模组（{@link KeyPriority}；ADR-0042）。
 *
 * <p>世界视图里的一次按键由 {@code Keyboard.onKey} 交给这两个静态方法，而它们只查 {@code KEY_TO_BINDINGS}
 * —— 一个键一个绑定。这里在查表之前先问一句「对局进行中、而且是本模组的键吗」，是就交给本模组、不再往下派。
 * 界面开着时不走这里（界面自己的 {@code keyPressed} 接），所以不会动到聊天框里打字。
 */
@Mixin(KeyBinding.class)
public abstract class KeyBindingPriorityMixin {

    @Shadow
    @Final
    private static Map<InputUtil.Key, KeyBinding> KEY_TO_BINDINGS;

    @Shadow
    private int timesPressed;

    /** 按下的那一次（{@code wasPressed} 数的就是它）。 */
    @Inject(method = "onKeyPressed", at = @At("HEAD"), cancellable = true)
    private static void heavyseas$claimTap(InputUtil.Key key, CallbackInfo ci) {
        KeyBinding ours = KeyPriority.claim(key);
        if (ours == null) {
            return;
        }
        KeyPriority.noteTakeover(key, ours, KEY_TO_BINDINGS.get(key));
        ((KeyBindingPriorityMixin) (Object) ours).timesPressed++;
        ci.cancel();
    }

    /**
     * 按住的状态（{@code isPressed}）。只截「按下」。
     *
     * <p>❗「松开」一律照常往下派：{@code Keyboard.onKey} 在界面开着时也会调松开（1.21.1 字节码 660），
     * 而一个键可能在对局开始前就按着 —— 要是截下松开，另一个绑定会一直停在「按着」。
     * 本模组那一个也同时松开：它可能是对局里被本模组接走的，松开时对局已经结束。
     */
    @Inject(method = "setKeyPressed", at = @At("HEAD"), cancellable = true)
    private static void heavyseas$claimHold(InputUtil.Key key, boolean pressed, CallbackInfo ci) {
        if (!pressed) {
            KeyBinding held = KeyPriority.ours(key);
            if (held != null) {
                held.setPressed(false);
            }
            return;
        }
        KeyBinding ours = KeyPriority.claim(key);
        if (ours != null) {
            ours.setPressed(true);
            ci.cancel();
        }
    }
}
