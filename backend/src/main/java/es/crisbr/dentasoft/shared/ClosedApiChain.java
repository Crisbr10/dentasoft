package es.crisbr.dentasoft.shared;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * A security chain that denies every request under some paths, whoever asks. Until authentication exists (M02-T02),
 * the APIs whose services require a role are closed with it, each module with its own chain ordered before the
 * application's. The services also check the role with {@code @PreAuthorize}, so opening a chain will not open them.
 *
 * <p>Denials are answered by the MVC exception resolvers, so the client gets the same 403 {@code ProblemDetail} as from
 * any other refusal, never Spring Boot's error page. Anonymous requests get 403 too: signing in would not help.
 *
 * <p>CSRF is off for the same reason as in the application's chain: the API is stateless, with no session and no
 * cookie authentication, and M02 authenticates with a Bearer token in the {@code Authorization} header, which a CSRF
 * attack cannot send. If authentication ever uses cookies, CSRF protection must be turned on.
 *
 * <p>M02-T02 replaces {@code denyAll()} with an authenticated request that has the module's role, and gives anonymous
 * requests its 401 entry point.
 */
public final class ClosedApiChain {

    private ClosedApiChain() {}

    /**
     * @param paths the Ant-style pattern the chain applies to, such as {@code /api/v1/platform/**}
     * @param exceptionResolver the {@code handlerExceptionResolver} bean, which writes the {@code ProblemDetail}
     */
    public static SecurityFilterChain denyAll(
            HttpSecurity http, String paths, HandlerExceptionResolver exceptionResolver) throws Exception {
        return http.securityMatcher(paths)
                .authorizeHttpRequests(requests -> requests.anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .accessDeniedHandler(
                                (request, response, denied) -> deny(exceptionResolver, request, response, denied))
                        .authenticationEntryPoint((request, response, unauthenticated) -> deny(
                                exceptionResolver,
                                request,
                                response,
                                new AccessDeniedException("API closed", unauthenticated))))
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
