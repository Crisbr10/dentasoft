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
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * Rejects with 429 the requests that exceed the limit of their client IP or of their user. Two instances, one on each
 * side of Spring Security's filter chain (order -100):
 *
 * <ul>
 *   <li>{@link Stage#CLIENT_IP}, before it ({@value #CLIENT_IP_ORDER}): the anonymous limit of the client IP. Every
 *       request is counted up front, so a flood of forged tokens, sequential or concurrent, is refused before their
 *       signatures are checked; also those Spring Security answers with 401 stay counted. A request whose user signed
 *       in is given back once done, so the users of a clinic behind one address keep their own limits, using at most
 *       the address's limit while in flight. Once that address is over its limit, they are refused too until it
 *       refills, because nothing tells them apart before the token is checked.
 *   <li>{@link Stage#USER}, after it ({@value #USER_ORDER}), once the user is known: the limit of each user.
 * </ul>
 *
 * <p>Redis keys are HMAC-SHA256 of the IP or the user ID with {@code DENTASOFT_RATE_LIMIT_KEY}: they never contain the
 * raw value. IPv6 clients are grouped by their /64 prefix, which is what a single client usually controls.
 */
class RateLimitFilter extends OncePerRequestFilter {

    enum Stage {
        CLIENT_IP,
        USER
    }

    static final int CLIENT_IP_ORDER = -110;
    static final int USER_ORDER = -90;
    static final String KEY_PREFIX = "dentasoft:rate-limit:";

    /** Set by the user stage on requests of a signed-in user, which the client IP stage then gives back. */
    static final String SIGNED_IN_ATTRIBUTE = RateLimitFilter.class.getName() + ".signedIn";

    /** Kubernetes probes must never be throttled. */
    private static final Set<String> EXEMPT_PATHS = Set.of("/actuator/health/liveness", "/actuator/health/readiness");

    private static final String HMAC = "HmacSHA256";

    private final Stage stage;
    private final RateLimiter limiter;
    private final ClientIpResolver clientIpResolver;
    private final HandlerExceptionResolver exceptionResolver;
    private final SecretKeySpec hmacKey;

    RateLimitFilter(
            Stage stage,
            RateLimiter limiter,
            ClientIpResolver clientIpResolver,
            HandlerExceptionResolver exceptionResolver,
            RateLimitProperties properties) {
        this.stage = stage;
        this.limiter = limiter;
        this.clientIpResolver = clientIpResolver;
        this.exceptionResolver = exceptionResolver;
        String key = Objects.requireNonNull(properties.key(), "dentasoft.rate-limit.key");
        this.hmacKey = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return EXEMPT_PATHS.contains(
                request.getRequestURI().substring(request.getContextPath().length()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        switch (stage) {
            case CLIENT_IP -> limitClientIp(request, response, chain);
            case USER -> limitUser(request, response, chain);
        }
    }

    /**
     * Counted before anything else runs, so concurrent requests cannot all pass before any is counted; given back
     * once done if a user signed in.
     */
    private void limitClientIp(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = KEY_PREFIX + "ip:" + hmac(clientBytes(clientIpResolver.resolve(request)));
        ConsumptionProbe probe = limiter.tryConsume(key, RateLimiter.Scope.ANONYMOUS);
        if (!probe.isConsumed()) {
            reject(request, response, probe.getNanosToWaitForRefill());
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            if (request.getAttribute(SIGNED_IN_ATTRIBUTE) != null) {
                limiter.refund(key, RateLimiter.Scope.ANONYMOUS);
            }
        }
    }

    private void limitUser(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Principal user = request.getUserPrincipal();
        if (user == null) {
            chain.doFilter(request, response);
            return;
        }
        request.setAttribute(SIGNED_IN_ATTRIBUTE, Boolean.TRUE);
        ConsumptionProbe probe = limiter.tryConsume(
                KEY_PREFIX + "user:" + hmac(user.getName().getBytes(StandardCharsets.UTF_8)), RateLimiter.Scope.USER);
        if (probe.isConsumed()) {
            chain.doFilter(request, response);
            return;
        }
        reject(request, response, probe.getNanosToWaitForRefill());
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, long nanosToWait) throws IOException {
        long retryAfterSeconds = retryAfterSeconds(nanosToWait);
        ModelAndView handled = exceptionResolver.resolveException(
                request, response, null, new RateLimitExceededException(retryAfterSeconds));
        if (handled == null) {
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
            response.sendError(HttpStatus.TOO_MANY_REQUESTS.value());
        }
    }

    /** Whole seconds, rounded up so the client never retries too early, and at least 1. */
    static long retryAfterSeconds(long nanosToWait) {
        long nanosPerSecond = TimeUnit.SECONDS.toNanos(1);
        return Math.max(1, Math.ceilDiv(nanosToWait, nanosPerSecond));
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
