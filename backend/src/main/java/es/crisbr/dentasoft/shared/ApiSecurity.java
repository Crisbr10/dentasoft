package es.crisbr.dentasoft.shared;

import com.nimbusds.jose.jwk.source.RateLimitReachedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * What every security chain of the API shares: a stateless OAuth2 Resource Server that authenticates the Keycloak
 * access token in the {@code Authorization: Bearer} header. The decoder and the conversion of the token into a user
 * with roles are beans of the {@code identity} module. Each chain then adds its own rules: the platform API requires
 * {@code PLATFORM_ADMIN}, the clinic API stays closed until the active clinic exists (M02-T04), and the application's
 * chain requires an authenticated user except on the health checks.
 *
 * <p>Refusals are answered by the MVC exception resolvers, so the client gets the same {@code ProblemDetail} as from
 * any other error: 401 without a valid token, 403 with a token that lacks the right. Neither says why: an expired
 * token, a wrong audience or a bad signature all get the same 401, with a bare {@code WWW-Authenticate: Bearer}
 * header and no {@code error_description}.
 *
 * <p>CSRF is off because the API is stateless: no session and no cookie authentication. The Bearer token travels in a
 * header that a CSRF attack cannot send. If authentication ever uses cookies, CSRF protection must be turned on.
 * Security headers and CORS stay in the servlet filters of {@code shared.web}, which run before these chains, so
 * Spring Security does not write them a second time and a 401 still carries the CORS headers the browser needs.
 */
public final class ApiSecurity {

    private static final Logger log = LoggerFactory.getLogger(ApiSecurity.class);

    private ApiSecurity() {}

    /**
     * Configures {@code http} as a stateless Resource Server; the caller adds its authorization rules and builds it.
     *
     * @param exceptionResolver the {@code handlerExceptionResolver} bean, which writes the {@code ProblemDetail}
     */
    public static HttpSecurity statelessApi(HttpSecurity http, HandlerExceptionResolver exceptionResolver)
            throws Exception {
        AuthenticationEntryPoint unauthenticated =
                (request, response, failure) -> unauthenticated(exceptionResolver, request, response, failure);
        AccessDeniedHandler denied = (request, response, failure) -> deny(exceptionResolver, request, response);
        return http.oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(denied)
                        .withObjectPostProcessor(new ObjectPostProcessor<BearerTokenAuthenticationFilter>() {
                            // By default a token that could not be checked (keys unreachable) is rethrown and
                            // ends as a 401 without its challenge; it gets the same answer as any other failure.
                            @Override
                            public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
                                filter.setAuthenticationFailureHandler(unauthenticated::commence);
                                return filter;
                            }
                        }))
                .exceptionHandling(exceptions ->
                        exceptions.authenticationEntryPoint(unauthenticated).accessDeniedHandler(denied))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .headers(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable);
    }

    /**
     * A 401 {@code ProblemDetail} that never says what was wrong with the token; a bare 401 if no MVC resolver
     * handles it. A token that could not be checked at all (Keycloak's keys unreachable) is logged, without the
     * exception's message, so the outage is visible; the client gets the same 401.
     */
    static void unauthenticated(
            HandlerExceptionResolver exceptionResolver,
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException failure)
            throws IOException {
        if (failure instanceof AuthenticationServiceException && !keysNotAskedAgainYet(failure)) {
            log.warn("Access token could not be checked: {}", failure.getClass().getName(), Logging.redacted(failure));
        }
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        AuthenticationException generic = new InsufficientAuthenticationException("Authentication required");
        if (exceptionResolver.resolveException(request, response, null, generic) == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }

    /**
     * The keys were not asked for because they were asked too recently ({@code identity}'s JWK source asks at most
     * twice every 30 seconds). Not logged: anyone can send tokens with an unknown key ID, and each would add a line.
     * The attempts that did ask Keycloak and failed are logged.
     */
    static boolean keysNotAskedAgainYet(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof RateLimitReachedException) {
                return true;
            }
        }
        return false;
    }

    /** A 403 {@code ProblemDetail}; a bare 403 if no MVC resolver handles it, never an empty 200. */
    static void deny(
            HandlerExceptionResolver exceptionResolver, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (exceptionResolver.resolveException(request, response, null, new AccessDeniedException("Access denied"))
                == null) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
        }
    }
}
