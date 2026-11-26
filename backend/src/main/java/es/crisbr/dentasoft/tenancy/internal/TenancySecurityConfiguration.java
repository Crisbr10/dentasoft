package es.crisbr.dentasoft.tenancy.internal;

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
 * The clinic configuration API stays closed until a request can have an active clinic: M02-T04 sets it from the
 * request, after checking the user's membership. Until then this chain, checked before the application's, denies
 * everything under {@link #PATHS}: 401 without a valid token, 403 with one, whoever it belongs to ({@link
 * ApiSecurity}). The services behind it also require {@code ADMIN_CLINICA} ({@link ClinicConfiguration}, {@link
 * ClinicOpeningHours}, {@link Locations}), so opening the chain will not open them.
 */
@Configuration(proxyBeanMethods = false)
class TenancySecurityConfiguration {

    static final String PATHS = "/api/v1/clinic/**";

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    SecurityFilterChain clinicConfigurationSecurityFilterChain(
            HttpSecurity http, @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver)
            throws Exception {
        return ApiSecurity.statelessApi(http.securityMatcher(PATHS), exceptionResolver)
                .authorizeHttpRequests(requests -> requests.anyRequest().denyAll())
                .build();
    }
}
