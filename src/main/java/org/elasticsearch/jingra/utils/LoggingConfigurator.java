package org.elasticsearch.jingra.utils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import org.elasticsearch.jingra.config.JingraConfig;
import org.slf4j.LoggerFactory;

/**
 * Configures logging levels at runtime based on configuration.
 */
public class LoggingConfigurator {

    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(LoggingConfigurator.class);

    private LoggingConfigurator() {
    }

    /**
     * Apply logging configuration from JingraConfig.
     */
    public static void configure(JingraConfig config) {
        if (config.getLogging() == null) {
            return;
        }

        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();

        // Set root level if specified
        if (config.getLogging().getLevel() != null) {
            Level level = Level.toLevel(config.getLogging().getLevel(), Level.INFO);
            Logger rootLogger = loggerContext.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            rootLogger.setLevel(level);
            logger.info("Set root log level to: {}", level);
        }

        // Set individual logger levels
        if (config.getLogging().getLoggers() != null) {
            config.getLogging().getLoggers().forEach((loggerName, levelStr) -> {
                Level level = Level.toLevel(levelStr, Level.INFO);
                Logger targetLogger = loggerContext.getLogger(loggerName);
                targetLogger.setLevel(level);
                logger.info("Set logger '{}' to level: {}", loggerName, level);
            });
        }
    }
}
