package es.crisbr.dentasoft.shared;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * {@code @EnableAsync} makes {@code @Async} methods and {@code @ApplicationModuleListener} listeners run on their own
 * (virtual) thread, as intended. Without it they would run on the caller's thread, inside its active clinic and its
 * request.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DentasoftUrls.class)
@EnableAsync
class SharedConfiguration {}
