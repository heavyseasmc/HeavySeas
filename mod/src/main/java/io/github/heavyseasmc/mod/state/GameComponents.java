package io.github.heavyseasmc.mod.state;

import io.github.heavyseasmc.mod.HeavySeasMod;
import io.github.heavyseasmc.mod.world.Nameplates;
import io.github.heavyseasmc.mod.world.Seats;
import io.github.heavyseasmc.mod.world.Gulls;

import net.minecraft.server.world.ServerWorld;
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
        registry.register(GAME, GameComponent::new);
        // 正向对照：CCA 没能加载 / 入口点名字写错时，这一行不会出现，而模组照样启动成功。
        // 组件用不上与组件没注册，在别处表现完全相同 —— 只有这一行分得开。
        LOGGER.info("对局组件已注册：{}（World 级，决策 ⑫）", GAME.getId());
    }

    /**
     * 取某个世界的对局组件。
     *
     * <p>❗走 {@code world.getComponent(KEY)} 而不是 {@code KEY.get(world)}：后者要求对象本身
     * 就是 {@code ComponentProvider}，而 {@code World} 不是 —— 生产环境实测
     * {@code NullPointerException: ... because "provider" is null}，**开发环境同样会**，
     * 只是那次是起服跑一整局才撞上的。世界这一级的入口是 CCA 注入进 {@code World} 的
     * {@code ComponentAccess} 接口（Loom 按 CCA jar 里的 {@code loom:injected_interfaces} 注入）。
     */
    public static GameComponent of(World world) {
        return world.getComponent(GAME);
    }

    /**
     * 把组件推给该收到的玩家。同上，走注入进 World 的那个接口。
     *
     * <p>顺带把世界里的位次也摆对（ADR-0024）：**座位上的位置和 HUD 一样是投影**，
     * 事实源永远是引擎的 {@code bySeat()}。放在这里是因为这里正是「投影该更新了」那一刻 ——
     * 换座位、被海水带走、重连，全都只是「名单变了，再摆一次」，不必各自记得去挪人。
     * {@code Seats#refresh} 对已经坐对的人什么都不做，所以每帧走一遍是便宜的。
     */
    public static void sync(World world) {
        world.syncComponent(GAME);
        if (world instanceof ServerWorld server) {
            Seats.refresh(server, of(world));
            Gulls.refresh(server, of(world));
            Nameplates.refresh(server, of(world));   // 头顶信息条同理：投影，不是状态（决策 ⑥）
        }
    }
}
