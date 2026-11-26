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
 * The clinic API ({@link ApiSecurity#CLINIC_API}) requires an authenticated user: 401 without a valid token. Which
 * clinic the request acts on, and with which role, is decided after this chain by the {@code identity} module's
 * active clinic filter, from the {@link ApiSecurity#CLINIC_HEADER} header and the user's membership (M02-T04).
 *
 * <p>The clinic role is added after Spring Security has authorized the request, so it cannot be required here
 * ({@code requestMatchers(…).hasRole(…)} would never see it): the services require it with {@code @PreAuthorize}
 * ({@link ClinicConfiguration}, {@link ClinicOpeningHours}, {@link Locations}). Without the filter, nothing behind
 * this chain opens: there is no active clinic and no clinic role.
 */
@Configuration(proxyBeanMethods = false)
class TenancySecurityConfiguration {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE + 1)
    SecurityFilterChain clinicConfigurationSecurityFilterChain(
            HttpSecurity http, @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver)
            throws Exception {
        return ApiSecurity.statelessApi(http.securityMatcher(ApiSecurity.CLINIC_API), exceptionResolver)
                .authorizeHttpRequests(requests -> requests.anyRequest().authenticated())
                .build();
    }
}
