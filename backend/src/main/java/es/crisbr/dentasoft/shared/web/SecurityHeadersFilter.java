package es.crisbr.dentasoft.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Security headers for every response. The API only serves JSON, so its Content Security Policy allows nothing;
 * Swagger UI (enabled only in non-production profiles) gets the minimum it needs to render.
 *
 * <p>When Spring Security arrives (M02-T02), its own header writer must be disabled or aligned with this filter
 * so the headers are not sent twice with different values.
 */
class SecurityHeadersFilter extends OncePerRequestFilter {

    static final String API_CSP = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";
    static final String SWAGGER_CSP = "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
            + "object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Content-Security-Policy", isSwaggerUi(request) ? SWAGGER_CSP : API_CSP);
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=()");
        // Responses carry clinical data: nothing is cached unless an endpoint says otherwise.
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }

    private static boolean isSwaggerUi(HttpServletRequest request) {
        return request.getRequestURI().startsWith(request.getContextPath() + "/swagger-ui/");
    }
}
