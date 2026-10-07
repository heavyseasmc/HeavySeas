package io.github.heavyseasmc.mod.llm;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 单测里把<b>真正写出去的日志</b>收下来：挂在 log4j 的根上，任何 logger 写的都收（SLF4J 在这里落到 log4j）。
 * 不靠被测代码自己报「我写了什么」—— 哪天有人绕开那一层直接写日志，这里照样看得见。
 */
final class LogCapture implements AutoCloseable {

    private final List<String> lines = Collections.synchronizedList(new ArrayList<>());
    private final LoggerContext context;
    private final LoggerConfig root;
    private final Level oldLevel;
    private final AbstractAppender appender;

    LogCapture() {
        context = (LoggerContext) LogManager.getContext(false);
        root = context.getConfiguration().getRootLogger();
        oldLevel = root.getLevel();
        appender = new AbstractAppender("llm-test-capture-" + System.nanoTime(), null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                StringBuilder sb = new StringBuilder();
                sb.append(event.getLevel()).append(' ').append(event.getLoggerName()).append(' ')
                        .append(event.getMessage().getFormattedMessage());
                if (event.getThrown() != null) {
                    StringWriter w = new StringWriter();
                    event.getThrown().printStackTrace(new PrintWriter(w));
                    sb.append('\n').append(w);
                }
                lines.add(sb.toString());
            }
        };
        appender.start();
        root.addAppender(appender, Level.ALL, null);
        root.setLevel(Level.ALL);
        context.updateLoggers();
    }

    List<String> lines() {
        synchronized (lines) {
            return List.copyOf(lines);
        }
    }

    List<String> containing(String fragment) {
        return lines().stream().filter(l -> l.contains(fragment)).toList();
    }

    @Override
    public void close() {
        root.removeAppender(appender.getName());
        root.setLevel(oldLevel);
        context.updateLoggers();
        appender.stop();
    }
}
