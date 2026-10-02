package io.github.heavyseasmc.mod.client;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.net.SkyS2C;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 这一个客户端此刻该把天画成什么样（ADR-0054 §9.8 D12 第 4 条 (a)）：服务端按人发 {@link SkyS2C}，这里记着；
 * 两个 mixin（{@code ClientWorldPropertiesMixin} 改时刻 · {@code WorldWeatherMixin} 改雨雷）读它。
 *
 * <p>只在包里写明的那个维度接管：人去了主世界，这里什么都不改，游戏自己的钟与雨照常 —— 服务端改口之前那一两秒也不会画错。
 * 天的角度、月相、天空颜色、光照的明暗、雨丝与雨声，游戏都从这两处取，所以 Sodium / Iris 也跟着变（2026-10-02 实跑）。
 */
public final class SkyOverride {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private static volatile SkyS2C current;

    private SkyOverride() {
    }

    public static void accept(SkyS2C sky) {
        current = sky.active() ? sky : null;
        // 与语言无关的一行：两个客户端同时进服时，判据靠它分辨「收到了什么」—— 截图只证明「画成了什么」。
        LOGGER.info("天色：收到 {}", sky.active()
                ? "%s 时刻 %d 雨 %.1f 雷 %.1f".formatted(sky.dimension(), sky.timeOfDay(), sky.rain(), sky.thunder())
                : "不接管");
    }

    public static void clear() {
        current = null;
    }

    /** 这个世界此刻由不由服务端的那一包接管；不接管返回 {@code null}。 */
    public static SkyS2C forWorld(World world) {
        SkyS2C sky = current;
        if (sky == null || world == null || !world.isClient()) {
            return null;
        }
        return world.getRegistryKey().getValue().toString().equals(sky.dimension()) ? sky : null;
    }
}
