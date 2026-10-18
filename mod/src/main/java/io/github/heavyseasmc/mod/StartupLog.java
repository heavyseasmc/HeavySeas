package io.github.heavyseasmc.mod;

import io.github.heavyseasmc.engine.state.Phase;
import io.github.heavyseasmc.mod.data.GameData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 启动日志使用英文；普通级别只报初始化结果，诊断数量只在 DEBUG 输出。 */
public final class StartupLog {
    private static final Logger LOGGER = LoggerFactory.getLogger(HeavySeasMod.MOD_ID);

    private StartupLog() {
    }

    public static void initialized() {
        // 无论日志级别如何都访问引擎，保留生产环境的嵌套依赖加载验证。
        int phases = Phase.values().length;
        LOGGER.info("HeavySeas initialized; rule engine available");
        LOGGER.debug("Rule engine: {} phases per turn", phases);
    }

    public static void dataLoaded(GameData data) {
        LOGGER.debug("Game data loaded: variant={} characters={} provisionTypes={} provisions={} navigation={} weather={}",
                data.variant(), data.roster().characters().size(), data.provisions().ids().size(),
                data.provisions().total(), data.navigation().size(), data.weather().size());
    }
}