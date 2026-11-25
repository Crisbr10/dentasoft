package es.crisbr.dentasoft;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Logs each test with the logging configuration of its own Spring context, and leaves the levels of every logger as it
 * found them (#130). For tests that check what the production configuration writes, such as its JSON format.
 *
 * <p>Logback is configured once per JVM, by the first test context, which may use another format (the local profile
 * writes plain text), so the extension re-initialises it for each test. Re-initialising resets every level, and Spring
 * Boot applies {@code logging.level} only when a context starts: the levels are saved before each test and restored
 * after the logging system is re-initialised, both before and after the test. Otherwise the cached contexts that later
 * test classes reuse would lose them, and {@code org.hibernate.orm.jdbc.error}, for one, would log what it must not.
 * {@code LoggingStateAcrossTestClassesTests} checks it.
 *
 * <p>After each test, the format is Spring Boot's default, without a context's configuration.
 */
public final class ContextLoggingExtension implements BeforeEachCallback, AfterEachCallback {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(ContextLoggingExtension.class);

    private static final String LEVELS = "levels";

    @Override
    public void beforeEach(ExtensionContext context) {
        Map<String, Level> levels = levels();
        context.getStore(NAMESPACE).put(LEVELS, levels);
        reinitialize((ConfigurableEnvironment)
                SpringExtension.getApplicationContext(context).getEnvironment());
        restore(levels);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        reinitialize(new StandardEnvironment());
        @SuppressWarnings("unchecked")
        Map<String, Level> levels = context.getStore(NAMESPACE).remove(LEVELS, Map.class);
        restore(levels);
    }

    private static void reinitialize(ConfigurableEnvironment environment) {
        LoggingSystem logging = LoggingSystem.get(ContextLoggingExtension.class.getClassLoader());
        logging.cleanUp();
        logging.beforeInitialize();
        logging.initialize(new LoggingInitializationContext(environment), null, null);
    }

    /** The level set on each logger, {@code null} for those that inherit theirs. */
    private static Map<String, Level> levels() {
        Map<String, Level> levels = new HashMap<>();
        for (Logger logger : loggerContext().getLoggerList()) {
            levels.put(logger.getName(), logger.getLevel());
        }
        return levels;
    }

    /** Loggers created since the levels were saved had none of their own then, so they inherit again. */
    private static void restore(Map<String, Level> levels) {
        for (Logger logger : loggerContext().getLoggerList()) {
            logger.setLevel(levels.get(logger.getName()));
        }
    }

    private static LoggerContext loggerContext() {
        return (LoggerContext) LoggerFactory.getILoggerFactory();
    }
}
