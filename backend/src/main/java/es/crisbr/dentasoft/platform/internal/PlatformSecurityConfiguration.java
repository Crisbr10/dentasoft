package es.crisbr.dentasoft.platform.internal;

import es.crisbr.dentasoft.shared.ApiSecurity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * The platform API is only for {@code PLATFORM_ADMIN}, the Keycloak realm role: this chain, checked before the
 * application's, answers 401 without a valid token and 403 to any other user ({@link ApiSecurity}). The services
 * behind it also require the role ({@link ClinicAdministration}), so a mistake in one of the two checks does not open
 * them.
 */
@Configuration(proxyBeanMethods = false)
class PlatformSecurityConfiguration {

    static final String PATHS = "/api/v1/platform/**";

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain platformSecurityFilterChain(
            HttpSecurity http, @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver)
            throws Exception {
        return ApiSecurity.statelessApi(http.securityMatcher(PATHS), exceptionResolver)
                .authorizeHttpRequests(requests -> requests.anyRequest().hasRole("PLATFORM_ADMIN"))
                .build();
    }
}
