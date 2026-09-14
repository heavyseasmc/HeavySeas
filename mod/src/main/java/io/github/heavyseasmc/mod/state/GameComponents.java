package io.github.heavyseasmc.mod.state;

import io.github.heavyseasmc.mod.HeavySeasMod;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.ladysnake.cca.api.v3.component.ComponentKey;
import org.ladysnake.cca.api.v3.component.ComponentRegistry;
import org.ladysnake.cca.api.v3.world.WorldComponentFactoryRegistry;
import org.ladysnake.cca.api.v3.world.WorldComponentInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 组件注册。由 Cardinal Components 经 {@code cardinal-components} 入口点调用，
 * <b>不是</b>模组主入口 —— CCA 要在注册表冻结之前拿到全部组件，比 {@code onInitialize} 早。
 *
 * <p>入口点名字不是猜的，是从 {@code cardinal-components-base} 的字节码里读出来的常量。
 */
public final class GameComponents implements WorldComponentInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    /** 对局状态。一个世界一局。 */
    public static final ComponentKey<GameComponent> GAME =
            ComponentRegistry.getOrCreate(
                    Identifier.of(HeavySeasMod.MOD_ID, "game"), GameComponent.class);

    @Override
    public void registerWorldComponentFactories(WorldComponentFactoryRegistry registry) {
        registry.register(GAME, world -> new GameComponent());
        // 正向对照：CCA 没能加载 / 入口点名字写错时，这一行不会出现，而模组照样启动成功。
        // 组件用不上与组件没注册，在别处表现完全相同 —— 只有这一行分得开。
        LOGGER.info("对局组件已注册：{}（World 级，决策 ⑫）", GAME.getId());
    }

    /** 取某个世界的对局组件。 */
    public static GameComponent of(World world) {
        return GAME.get(world);
    }
}
