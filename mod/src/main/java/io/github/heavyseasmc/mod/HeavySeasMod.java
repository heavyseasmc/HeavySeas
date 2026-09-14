package io.github.heavyseasmc.mod;

import io.github.heavyseasmc.engine.state.Phase;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模组入口。
 *
 * <p>此刻只做一件事：在 Minecraft 运行时里真的调用一次规则引擎。
 *
 * <h2>这行日志是正向对照，不是装饰</h2>
 * 引擎以普通 jar 的形式嵌套在本模组的 jar 里。嵌套装配错了，表现是服务端启动时
 * {@code NoClassDefFoundError}；而<b>开发环境测不出这一点</b> —— 那里引擎直接在类路径上，
 * 装没装进 jar 都能跑。所以只有生产环境启动时出现这一行，才证明嵌套装配是对的。
 */
public final class HeavySeasMod implements ModInitializer {

    public static final String MOD_ID = "heavyseas";

    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("规则引擎已接入：一回合 {} 个阶段", Phase.values().length);
    }
}
