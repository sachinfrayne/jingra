package org.elasticsearch.jingra.utils;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import org.elasticsearch.jingra.config.JingraConfig;
import org.elasticsearch.jingra.config.LoggingConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link LoggingConfigurator}.
 */
class LoggingConfiguratorTest {

    private static final String ROOT = org.slf4j.Logger.ROOT_LOGGER_NAME;
    private static final String PROBE_LOGGER = "jingra.test.logging.config.probe";
    private static final String OTHER_LOGGER = "jingra.test.logging.config.other";

    private Level savedRootLevel;

    private static LoggerContext context() {
        return (LoggerContext) LoggerFactory.getILoggerFactory();
    }

    @BeforeEach
    void saveRoot() {
        savedRootLevel = context().getLogger(ROOT).getLevel();
    }

    @AfterEach
    void restore() {
        context().getLogger(ROOT).setLevel(savedRootLevel);
        context().getLogger(PROBE_LOGGER).setLevel(null);
        context().getLogger(OTHER_LOGGER).setLevel(null);
    }

    @Test
    void constructor_isPrivateAndCallableViaReflection() throws Exception {
        Constructor<LoggingConfigurator> ctor = LoggingConfigurator.class.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(ctor.getModifiers()));
        ctor.setAccessible(true);
        assertNotNull(ctor.newInstance());
    }

    @Test
    void configure_loggingNullReturnsImmediately() {
        Level before = context().getLogger(ROOT).getLevel();
        JingraConfig cfg = new JingraConfig();
        cfg.setLogging(null);
        LoggingConfigurator.configure(cfg);
        assertEquals(before, context().getLogger(ROOT).getLevel());
    }

    @Test
    void configure_setsRootWhenLevelProvided() {
        LoggingConfig logging = new LoggingConfig();
        logging.setLevel("WARN");
        logging.setLoggers(null);
        JingraConfig cfg = new JingraConfig();
        cfg.setLogging(logging);
        LoggingConfigurator.configure(cfg);
        assertEquals(Level.WARN, context().getLogger(ROOT).getLevel());
    }

    @Test
    void configure_skipsRootWhenLevelNull() {
        LoggingConfig logging = new LoggingConfig();
        logging.setLevel(null);
        logging.setLoggers(null);
        JingraConfig cfg = new JingraConfig();
        cfg.setLogging(logging);
        LoggingConfigurator.configure(cfg);
        assertEquals(savedRootLevel, context().getLogger(ROOT).getLevel());
    }

    @Test
    void configure_namedLoggersAndInvalidLevelUsesInfoFallback() {
        LoggingConfig logging = new LoggingConfig();
        logging.setLevel(null);
        Map<String, String> loggers = new HashMap<>();
        loggers.put(PROBE_LOGGER, "DEBUG");
        loggers.put(OTHER_LOGGER, "NOT_A_REAL_LEVEL");
        logging.setLoggers(loggers);
        JingraConfig cfg = new JingraConfig();
        cfg.setLogging(logging);
        LoggingConfigurator.configure(cfg);
        assertEquals(Level.DEBUG, context().getLogger(PROBE_LOGGER).getLevel());
        assertEquals(Level.INFO, context().getLogger(OTHER_LOGGER).getLevel());
    }

    @Test
    void configure_nullLoggerLevelStringUsesInfoFallback() {
        LoggingConfig logging = new LoggingConfig();
        logging.setLevel(null);
        Map<String, String> loggers = new HashMap<>();
        loggers.put(PROBE_LOGGER, null);
        logging.setLoggers(loggers);
        JingraConfig cfg = new JingraConfig();
        cfg.setLogging(logging);
        LoggingConfigurator.configure(cfg);
        assertEquals(Level.INFO, context().getLogger(PROBE_LOGGER).getLevel());
    }

    @Test
    void configure_loggersMapNullSkipsPerLoggerLoop() {
        LoggingConfig logging = new LoggingConfig();
        logging.setLevel("ERROR");
        logging.setLoggers(null);
        JingraConfig cfg = new JingraConfig();
        cfg.setLogging(logging);
        LoggingConfigurator.configure(cfg);
        assertEquals(Level.ERROR, context().getLogger(ROOT).getLevel());
        assertNull(context().getLogger(PROBE_LOGGER).getLevel());
    }
}
