package es.crisbr.dentasoft.shared.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import es.crisbr.dentasoft.TestJwks;
import es.crisbr.dentasoft.TestTokens;
import es.crisbr.dentasoft.WithTestEnvironment;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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

    /** A public path, so anonymous requests get a 200 under the limit. Not one of the probes, never limited. */
    private static final String ANONYMOUS_PATH = "/actuator/health";

    /** Needs a signed-in user. */
    private static final String PING = "/api/v1/test/ping";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private StringRedisTemplate redis;

    @MockitoSpyBean
    private JwtDecoder decoder;

    /**
     * The client IP limit runs before Spring Security and counts the requests it answers with 401: a flood of forged
     * tokens from one address ends in 429 without their signatures being checked any more.
     */
    @Test
    void forgedTokensFromOneIpAreRefusedBeforeTheirSignatureIsChecked() {
        String forged = "Bearer " + TestTokens.token().signWith(TestJwks.otherKey("forged"));
        clearInvocations(decoder);

        for (int i = 0; i < ANONYMOUS_LIMIT; i++) {
            assertThat(withToken(forged, "203.0.113.20")).hasStatus(HttpStatus.UNAUTHORIZED);
        }
        MvcTestResult refused = withToken(forged, "203.0.113.20");

        assertThat(refused)
                .hasStatus(HttpStatus.TOO_MANY_REQUESTS)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .containsHeader(HttpHeaders.RETRY_AFTER);
        verify(decoder, times(ANONYMOUS_LIMIT)).decode(anyString());
    }

    /**
     * Signed-in users do not count against the limit of their address, so the users of a clinic behind one address
     * are only limited each by their own.
     */
    @Test
    void signedInUsersDoNotUseTheLimitOfTheirAddress() {
        for (int i = 0; i < USER_LIMIT; i++) {
            assertThat(asUser("behind-a-shared-address", "203.0.113.21")).hasStatusOk();
        }

        for (int i = 0; i < ANONYMOUS_LIMIT; i++) {
            assertThat(get(from("203.0.113.21"))).hasStatusOk();
        }
        assertThat(get(from("203.0.113.21"))).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
    }

    /** Once an address is over its limit, nothing tells its users apart before the token is checked. */
    @Test
    void anAddressOverItsLimitIsRefusedEvenWithAValidToken() {
        for (int i = 0; i < ANONYMOUS_LIMIT; i++) {
            get(from("203.0.113.22"));
        }

        assertThat(asUser("unlucky-neighbour", "203.0.113.22")).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
    }

    private MvcTestResult withToken(String authorization, String ip) {
        return mvc.get()
                .uri(PING)
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .with(from(ip)::apply)
                .exchange();
    }

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
                .uri(ANONYMOUS_PATH)
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
                        .doesNotContain(userId("user-with-visible-id").toString())
                        .matches(RateLimitFilter.KEY_PREFIX + "(ip|user):[A-Za-z0-9_-]{43}"));
    }

    @Test
    void redisKeysExpire() {
        get(from("203.0.113.10"));

        Set<String> keys = redis.keys(RateLimitFilter.KEY_PREFIX + "ip:*");

        assertThat(keys).allSatisfy(key -> assertThat(redis.getExpire(key)).isBetween(1L, 75L));
    }

    private MvcTestResult get(UnaryOperator<MockHttpServletRequest> request) {
        return mvc.get().uri(ANONYMOUS_PATH).with(request::apply).exchange();
    }

    /**
     * Authenticated with an access token through Spring Security: its chain runs before the rate limiter and is what
     * the request's user principal (the token's {@code sub}) comes from, so a principal set on the raw request is not
     * seen.
     */
    private MvcTestResult asUser(String user, String ip) {
        return mvc.get()
                .uri(PING)
                .header(
                        HttpHeaders.AUTHORIZATION,
                        TestTokens.token(userId(user)).bearer())
                .with(from(ip)::apply)
                .exchange();
    }

    private static UUID userId(String user) {
        return UUID.nameUUIDFromBytes(user.getBytes(StandardCharsets.UTF_8));
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
