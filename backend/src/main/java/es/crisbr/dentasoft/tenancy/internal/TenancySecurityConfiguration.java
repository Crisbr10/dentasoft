package es.crisbr.dentasoft.tenancy.internal;

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
 * The clinic configuration API is closed to every request until authentication exists (M02-T02): without a signed-in
 * user there is no active clinic to configure. This chain, checked before the application's, denies everything under
 * {@link #PATHS}, whoever asks ({@link ClosedApiChain}). The services behind it also require {@code ADMIN_CLINICA}
 * ({@link ClinicConfiguration}, {@link ClinicOpeningHours}, {@link Locations}), so opening the chain will not open
 * them.
 *
 * <p>M02-T02 replaces it with an authenticated request whose clinic becomes the active one.
 */
@Configuration(proxyBeanMethods = false)
class TenancySecurityConfiguration {

    static final String PATHS = "/api/v1/clinic/**";

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    SecurityFilterChain clinicConfigurationSecurityFilterChain(
            HttpSecurity http, @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver)
            throws Exception {
        return ClosedApiChain.denyAll(http, PATHS, exceptionResolver);
    }
}
