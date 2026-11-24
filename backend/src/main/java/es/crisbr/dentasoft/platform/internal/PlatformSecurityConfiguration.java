package es.crisbr.dentasoft.platform.internal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * The platform API is closed to every request until authentication exists (M02-T02): this chain, checked before the
 * application's, denies everything under {@link #PATHS}, whoever asks. The services behind it also require {@code
 * PLATFORM_ADMIN} ({@link ClinicAdministration}), so opening the chain will not open them.
 *
 * <p>Denials are answered by the MVC exception resolvers, so the client gets the same 403 {@code ProblemDetail} as
 * from any other refusal, never Spring Boot's error page. Anonymous requests get 403 too: signing in would not help.
 *
 * <p>M02-T02 replaces {@code denyAll()} with an authenticated request that has the {@code PLATFORM_ADMIN} role, and
 * gives anonymous requests its 401 entry point.
 */
@Configuration(proxyBeanMethods = false)
class PlatformSecurityConfiguration {

    static final String PATHS = "/api/v1/platform/**";

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain platformSecurityFilterChain(
            HttpSecurity http, @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver)
            throws Exception {
        return http.securityMatcher(PATHS)
                .authorizeHttpRequests(requests -> requests.anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .accessDeniedHandler(
                                (request, response, denied) -> deny(exceptionResolver, request, response, denied))
                        .authenticationEntryPoint((request, response, unauthenticated) -> deny(
                                exceptionResolver,
                                request,
                                response,
                                new AccessDeniedException("Platform API closed", unauthenticated))))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .headers(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .build();
    }

    /** A 403 ProblemDetail from the MVC resolvers; a bare 403 if none of them handles it, never an empty 200. */
    static void deny(
            HandlerExceptionResolver exceptionResolver,
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException denied)
            throws IOException {
        if (exceptionResolver.resolveException(request, response, null, denied) == null) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
        }
    }
}
