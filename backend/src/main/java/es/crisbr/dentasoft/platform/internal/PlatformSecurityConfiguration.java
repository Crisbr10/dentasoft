package es.crisbr.dentasoft.platform.internal;

import es.crisbr.dentasoft.shared.ClosedApiChain;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * The platform API is closed to every request until authentication exists (M02-T02): this chain, checked before the
 * application's, denies everything under {@link #PATHS}, whoever asks ({@link ClosedApiChain}). The services behind it
 * also require {@code PLATFORM_ADMIN} ({@link ClinicAdministration}), so opening the chain will not open them.
 *
 * <p>M02-T02 replaces it with an authenticated request that has the {@code PLATFORM_ADMIN} role.
 */
@Configuration(proxyBeanMethods = false)
class PlatformSecurityConfiguration {

    static final String PATHS = "/api/v1/platform/**";

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain platformSecurityFilterChain(
            HttpSecurity http, @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver)
            throws Exception {
        return ClosedApiChain.denyAll(http, PATHS, exceptionResolver);
    }
}
