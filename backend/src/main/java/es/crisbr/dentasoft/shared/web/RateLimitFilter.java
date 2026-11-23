package es.crisbr.dentasoft.shared.web;

import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * Rejects with 429 the requests that exceed the limit of their user, or of their client IP when anonymous.
 *
 * <p>Runs after Spring Security's filter chain (order {@value #ORDER}, the chain is -100) so the user is known.
 * Redis keys are HMAC-SHA256 of the IP or the user ID with {@code DENTASOFT_RATE_LIMIT_KEY}: they never contain the
 * raw value. IPv6 clients are grouped by their /64 prefix, which is what a single client usually controls.
 */
class RateLimitFilter extends OncePerRequestFilter {

    static final int ORDER = -90;
    static final String KEY_PREFIX = "dentasoft:rate-limit:";

    /** Kubernetes probes must never be throttled. */
    private static final Set<String> EXEMPT_PATHS = Set.of("/actuator/health/liveness", "/actuator/health/readiness");

    private static final String HMAC = "HmacSHA256";

    private final RateLimiter limiter;
    private final ClientIpResolver clientIpResolver;
    private final HandlerExceptionResolver exceptionResolver;
    private final SecretKeySpec hmacKey;

    RateLimitFilter(
            RateLimiter limiter,
            ClientIpResolver clientIpResolver,
            HandlerExceptionResolver exceptionResolver,
            RateLimitProperties properties) {
        this.limiter = limiter;
        this.clientIpResolver = clientIpResolver;
        this.exceptionResolver = exceptionResolver;
        this.hmacKey = new SecretKeySpec(properties.key().getBytes(StandardCharsets.UTF_8), HMAC);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return EXEMPT_PATHS.contains(request.getRequestURI().substring(request.getContextPath().length()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Principal user = request.getUserPrincipal();
        ConsumptionProbe probe = user != null
                ? limiter.tryConsume(KEY_PREFIX + "user:" + hmac(user.getName().getBytes(StandardCharsets.UTF_8)),
                        RateLimiter.Scope.USER)
                : limiter.tryConsume(KEY_PREFIX + "ip:" + hmac(clientBytes(clientIpResolver.resolve(request))),
                        RateLimiter.Scope.ANONYMOUS);
        if (probe.isConsumed()) {
            chain.doFilter(request, response);
            return;
        }
        long retryAfterSeconds = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill() + 999_999_999L));
        ModelAndView handled = exceptionResolver.resolveException(
                request, response, null, new RateLimitExceededException(retryAfterSeconds));
        if (handled == null) {
            response.setHeader("Retry-After", Long.toString(retryAfterSeconds));
            response.sendError(HttpStatus.TOO_MANY_REQUESTS.value());
        }
    }

    private static byte[] clientBytes(InetAddress address) {
        byte[] bytes = address.getAddress();
        return address instanceof Inet6Address ? Arrays.copyOf(bytes, 8) : bytes;
    }

    private String hmac(byte[] value) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(hmacKey);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value));
        } catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException("HMAC-SHA256 is not available", ex);
        }
    }
}
