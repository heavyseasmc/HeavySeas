package io.github.heavyseasmc.mod;

import io.github.heavyseasmc.engine.data.RosterLoader;
import io.github.heavyseasmc.engine.data.ProvisionLoader;
import io.github.heavyseasmc.mod.data.GameData;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StartupLogTest {
    @Test
    @DisplayName("启动日志：INFO 只有英文结果，DEBUG 才有引擎与数据详情；警告和错误不被隐藏")
    void startupDetailsRespectLogLevel() {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        var config = context.getConfiguration();
        LoggerConfig previous = config.getLoggers().get(HeavySeasMod.MOD_ID);
        LoggerConfig logger = new LoggerConfig(HeavySeasMod.MOD_ID, Level.INFO, false);
        List<String> lines = new ArrayList<>();
        AbstractAppender capture = new AbstractAppender("startup-log-test", null, null, true, Property.EMPTY_ARRAY) {
            @Override public void append(LogEvent event) {
                lines.add(event.getMessage().getFormattedMessage());
            }
        };
        capture.start();
        logger.addAppender(capture, Level.ALL, null);
        config.addLogger(HeavySeasMod.MOD_ID, logger);
        context.updateLoggers();
        try {
            var directory = java.nio.file.Path.of("../data");
            GameData data = new GameData("heavyseas:default",
                    RosterLoader.load(directory.resolve("roster/default.json")),
                    ProvisionLoader.loadCatalog(directory.resolve("provisions/default.json")), List.of(), List.of());
            StartupLog.initialized();
            StartupLog.dataLoaded(data);
            assertEquals(List.of("HeavySeas initialized; rule engine available"), lines,
                    "普通日志级别不能输出启动诊断详情");
            lines.clear();
            logger.setLevel(Level.DEBUG);
            context.updateLoggers();
            StartupLog.initialized();
            StartupLog.dataLoaded(data);
            assertEquals(3, lines.size(), "DEBUG 必须真的输出详情，而非全部删除");
            assertTrue(lines.stream().anyMatch(s -> s.startsWith("Rule engine:")));
            assertTrue(lines.stream().anyMatch(s -> s.startsWith("Game data loaded: variant=heavyseas:default")));
            assertTrue(lines.stream().allMatch(s -> s.chars().allMatch(c -> c < 128)), "启动文案应为英文");
            lines.clear();
            logger.setLevel(Level.INFO);
            context.updateLoggers();
            var slf4j = org.slf4j.LoggerFactory.getLogger(HeavySeasMod.MOD_ID);
            slf4j.warn("startup warning control");
            slf4j.error("startup error control");
            assertEquals(List.of("startup warning control", "startup error control"), lines);
        } finally {
            config.removeLogger(HeavySeasMod.MOD_ID);
            if (previous != null) config.addLogger(HeavySeasMod.MOD_ID, previous);
            context.updateLoggers();
            capture.stop();
        }
    }
}