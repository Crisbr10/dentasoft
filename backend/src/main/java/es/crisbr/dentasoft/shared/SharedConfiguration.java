package es.crisbr.dentasoft.shared;

import es.crisbr.dentasoft.shared.internal.RedactedException;
import java.lang.reflect.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * {@code @EnableAsync} makes {@code @Async} methods and {@code @ApplicationModuleListener} listeners run on their own
 * (virtual) thread, as intended. Without it they would run on the caller's thread, inside its active clinic and its
 * request.
 *
 * <p>An exception that escapes one of them, when it returns nothing, is logged without its messages ({@link
 * RedactedException}): no request handles it, and its messages may carry personal data (#123). They still run on
 * Spring Boot's {@code applicationTaskExecutor}, which it keeps for an {@link AsyncConfigurer} with no executor.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DentasoftUrls.class)
@EnableAsync
class SharedConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SharedConfiguration.class);

    @Bean
    AsyncConfigurer redactingAsyncConfigurer() {
        return new AsyncConfigurer() {
            @Override
            public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
                return SharedConfiguration::logUncaught;
            }
        };
    }

    /** The method's class and name only: its arguments are the values that the messages would have carried. */
    static void logUncaught(Throwable exception, Method method, Object... arguments) {
        log.error(
                "Unexpected exception in async method {}.{}",
                method.getDeclaringClass().getName(),
                method.getName(),
                RedactedException.of(exception));
    }
}
