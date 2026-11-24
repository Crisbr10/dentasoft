package es.crisbr.dentasoft.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.WithTestEnvironment;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Rate limits against a real Redis. Every test uses its own IPs or user, so their buckets never mix.
 * Documentation-reserved addresses only (RFC 5737).
 */
@SpringBootTest(
        properties = {
            "dentasoft.rate-limit.anonymous-per-minute=" + RateLimitTests.ANONYMOUS_LIMIT,
            "dentasoft.rate-limit.user-per-minute=" + RateLimitTests.USER_LIMIT,
            "dentasoft.http.trusted-proxies=10.0.0.0/8, 192.0.2.10"
        })
@AutoConfigureMockMvc
@WithTestEnvironment
@Import(TestEndpoints.class)
class RateLimitTests {

    static final int ANONYMOUS_LIMIT = 3;
    static final int USER_LIMIT = 5;

    private static final String PING = "/api/v1/test/ping";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private StringRedisTemplate redis;

    @Test
    void exceedingTheAnonymousLimitReturns429ProblemDetailWithRetryAfter() {
        for (int i = 0; i < ANONYMOUS_LIMIT; i++) {
            assertThat(get(from("203.0.113.1"))).hasStatusOk();
        }

        MvcTestResult rejected = get(from("203.0.113.1"));

        assertThat(rejected)
                .hasStatus(HttpStatus.TOO_MANY_REQUESTS)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .containsHeader("X-Request-Id")
                .bodyJson()
                .satisfies(json -> {
                    assertThat(json).extractingPath("$.status").isEqualTo(429);
                    assertThat(json).extractingPath("$.title").isEqualTo("Demasiadas peticiones");
                    assertThat(json)
                            .extractingPath("$.detail")
                            .isEqualTo(
                                    "Has superado el límite de peticiones. Espera unos segundos y vuelve a intentarlo.");
                });
        long retryAfter = Long.parseLong(rejected.getResponse().getHeader(HttpHeaders.RETRY_AFTER));
        assertThat(retryAfter).isBetween(1L, 60L);
    }

    @Test
    void rejectionFromTheFrontendCarriesCorsHeadersSoTheBrowserCanReadIt() {
        for (int i = 0; i < ANONYMOUS_LIMIT; i++) {
            get(from("203.0.113.2"));
        }

        MvcTestResult rejected = mvc.get()
                .uri(PING)
                .header(HttpHeaders.ORIGIN, WithTestEnvironment.APP)
                .with(from("203.0.113.2")::apply)
                .exchange();

        assertThat(rejected)
                .hasStatus(HttpStatus.TOO_MANY_REQUESTS)
                .hasHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, WithTestEnvironment.APP);
    }

    @Test
    void eachClientIpHasItsOwnBucket() {
        for (int i = 0; i < ANONYMOUS_LIMIT; i++) {
            get(from("203.0.113.3"));
        }

        assertThat(get(from("203.0.113.3"))).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(get(from("203.0.113.4"))).hasStatusOk();
    }

    @Test
    void forwardedHeadersFromAnUntrustedClientDoNotChangeItsIp() {
        for (int i = 0; i <= ANONYMOUS_LIMIT; i++) {
            String spoofed = "198.51.100." + (i + 1);
            MvcTestResult result = get(from("203.0.113.5")
                    .andThen(header(ClientIpResolver.X_FORWARDED_FOR, spoofed))
                    .andThen(header(ClientIpResolver.CF_CONNECTING_IP, spoofed))::apply);
            if (i < ANONYMOUS_LIMIT) {
                assertThat(result).hasStatusOk();
            } else {
                assertThat(result).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
            }
        }
    }

    @Test
    void behindATrustedProxyTheForwardedClientIpIsLimited() {
        for (int i = 0; i < ANONYMOUS_LIMIT; i++) {
            assertThat(get(from("10.1.2.3").andThen(header(ClientIpResolver.X_FORWARDED_FOR, "198.51.100.20"))::apply))
                    .hasStatusOk();
        }

        assertThat(get(from("10.9.9.9").andThen(header(ClientIpResolver.X_FORWARDED_FOR, "198.51.100.20"))::apply))
                .hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(get(from("10.1.2.3").andThen(header(ClientIpResolver.X_FORWARDED_FOR, "198.51.100.21"))::apply))
                .hasStatusOk();
    }

    @Test
    void behindATrustedProxyCloudflareHeaderIsUsed() {
        for (int i = 0; i < ANONYMOUS_LIMIT; i++) {
            get(from("192.0.2.10").andThen(header(ClientIpResolver.CF_CONNECTING_IP, "198.51.100.30"))::apply);
        }

        assertThat(get(from("192.0.2.10").andThen(header(ClientIpResolver.CF_CONNECTING_IP, "198.51.100.30"))::apply))
                .hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(get(from("192.0.2.10").andThen(header(ClientIpResolver.CF_CONNECTING_IP, "198.51.100.31"))::apply))
                .hasStatusOk();
    }

    @Test
    void authenticatedUsersAreLimitedPerUserWithTheirOwnLimit() {
        for (int i = 0; i < USER_LIMIT; i++) {
            assertThat(asUser("user-a", "203.0.113.6")).hasStatusOk();
        }

        assertThat(asUser("user-a", "203.0.113.7")).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(asUser("user-b", "203.0.113.6")).hasStatusOk();
        assertThat(get(from("203.0.113.6"))).hasStatusOk();
    }

    @Test
    void kubernetesProbesAreNeverLimited() {
        for (int i = 0; i < ANONYMOUS_LIMIT * 3; i++) {
            assertThat(mvc.get().uri("/actuator/health/liveness").with(from("203.0.113.8")::apply))
                    .hasStatusOk();
            assertThat(mvc.get().uri("/actuator/health/readiness").with(from("203.0.113.8")::apply))
                    .hasStatusOk();
        }
    }

    @Test
    void redisKeysNeverContainTheRawIpOrUser() {
        get(from("203.0.113.9"));
        asUser("user-with-visible-id", "203.0.113.9");

        Set<String> keys = redis.keys(RateLimitFilter.KEY_PREFIX + "*");

        assertThat(keys)
                .isNotEmpty()
                .allSatisfy(key -> assertThat(key)
                        .doesNotContain("203.0.113")
                        .doesNotContain("user-with-visible-id")
                        .matches(RateLimitFilter.KEY_PREFIX + "(ip|user):[A-Za-z0-9_-]{43}"));
    }

    @Test
    void redisKeysExpire() {
        get(from("203.0.113.10"));

        Set<String> keys = redis.keys(RateLimitFilter.KEY_PREFIX + "ip:*");

        assertThat(keys).allSatisfy(key -> assertThat(redis.getExpire(key)).isBetween(1L, 75L));
    }

    private MvcTestResult get(UnaryOperator<MockHttpServletRequest> request) {
        return mvc.get().uri(PING).with(request::apply).exchange();
    }

    /**
     * Authenticated through Spring Security, as the application will be (M02): its chain runs before the rate limiter
     * and is what the request's user principal comes from, so a principal set on the raw request is not seen.
     */
    private MvcTestResult asUser(String user, String ip) {
        return mvc.get()
                .uri(PING)
                .with(SecurityMockMvcRequestPostProcessors.user(user))
                .with(from(ip)::apply)
                .exchange();
    }

    private static Processor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private static Processor header(String name, String value) {
        return request -> {
            request.addHeader(name, value);
            return request;
        };
    }

    @FunctionalInterface
    private interface Processor extends UnaryOperator<MockHttpServletRequest> {

        default Processor andThen(Processor next) {
            return request -> next.apply(apply(request));
        }
    }
}
