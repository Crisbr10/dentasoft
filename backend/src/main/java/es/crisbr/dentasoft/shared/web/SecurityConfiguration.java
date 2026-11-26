package es.crisbr.dentasoft.shared.web;

import es.crisbr.dentasoft.shared.ApiSecurity;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * The application's security chain, checked after the chains of the modules (platform, clinic).
 *
 * <ul>
 *   <li>Every request needs a valid Keycloak access token ({@link ApiSecurity}), except the health checks the load
 *       balancer and Kubernetes call: {@link #HEALTH_PATHS}. Webhooks will get their own public paths, with a
 *       signature check, when the first one exists (M07-T04).
 *   <li>The OpenAPI document and Swagger UI ({@link #API_DOCS_PATHS}) are public only while springdoc serves them
 *       ({@value #API_DOCS_ENABLED}, which only the {@code local} profile turns on), so {@code pnpm api:gen} works
 *       without a token. The rule follows the property, not the profile's name: wherever the documentation is off,
 *       its paths need a token like everything else.
 *   <li>Method security is on: services declare who may call them with {@code @PreAuthorize}.
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class SecurityConfiguration {

    static final String[] HEALTH_PATHS = {"/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness"
    };
    static final String[] API_DOCS_PATHS = {"/v3/api-docs/**", "/swagger-ui/**"};
    static final String API_DOCS_ENABLED = "springdoc.api-docs.enabled";

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    SecurityFilterChain applicationSecurityFilterChain(
            HttpSecurity http,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver,
            Environment environment)
            throws Exception {
        // springdoc serves the document unless told otherwise, so a missing property means on.
        boolean apiDocs = environment.getProperty(API_DOCS_ENABLED, Boolean.class, true);
        return ApiSecurity.statelessApi(http, exceptionResolver)
                .authorizeHttpRequests(requests -> {
                    requests.requestMatchers(HEALTH_PATHS).permitAll();
                    if (apiDocs) {
                        requests.requestMatchers(API_DOCS_PATHS).permitAll();
                    }
                    requests.anyRequest().authenticated();
                })
                .build();
    }

    /**
     * No username and password users: identities come from Keycloak. Declaring it keeps Spring Boot from creating a
     * default user with a generated password.
     */
    @Bean
    UserDetailsService noLocalUsers() {
        return new InMemoryUserDetailsManager();
    }
}
