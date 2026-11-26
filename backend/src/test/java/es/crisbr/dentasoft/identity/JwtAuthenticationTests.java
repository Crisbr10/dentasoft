package es.crisbr.dentasoft.identity;

import static es.crisbr.dentasoft.SecurityResponses.assertUnauthenticated;
import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.TestJwks;
import es.crisbr.dentasoft.TestTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * The access token checks (M02-T02), with tokens signed in the test against the local JWK set of {@link TestJwks}: no
 * real Keycloak. Every rejected token gets the same 401, which never says what was wrong with it.
 */
@IntegrationTest
@AutoConfigureMockMvc
class JwtAuthenticationTests {

    /** Any path behind the application's chain: it only needs a signed-in user. */
    private static final String PROTECTED = "/api/v1/test/ping";

    @Autowired
    private MockMvcTester mvc;

    @Test
    void aValidTokenIsAccepted() {
        assertThat(mvc.get().uri(PROTECTED).header(HttpHeaders.AUTHORIZATION, TestTokens.anyUser()))
                .hasStatusOk();
    }

    /** Clocks drift: a token that expired seconds ago, within the 30 seconds of skew, still works. */
    @Test
    void aTokenJustPastItsExpiryIsAcceptedWithinTheClockSkew() {
        String token =
                TestTokens.token().expiresAt(Instant.now().minusSeconds(10)).bearer();

        assertThat(mvc.get().uri(PROTECTED).header(HttpHeaders.AUTHORIZATION, token))
                .hasStatusOk();
    }

    @Test
    void aRequestWithoutATokenIsRejected() {
        assertUnauthenticated(mvc.get().uri(PROTECTED).exchange());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedTokens")
    void rejects(String why, String authorization) {
        assertUnauthenticated(mvc.get()
                .uri(PROTECTED)
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .exchange());
    }

    static Stream<Arguments> rejectedTokens() {
        return Stream.of(
                Arguments.of(
                        "expired beyond the clock skew",
                        bearer(TestTokens.token()
                                .expiresAt(Instant.now().minus(Duration.ofMinutes(2)))
                                .sign())),
                Arguments.of(
                        "without an expiry",
                        bearer(TestTokens.token().expiresAt(null).sign())),
                Arguments.of(
                        "for another audience",
                        bearer(TestTokens.token()
                                .audience("account", "dentasoft-web")
                                .sign())),
                Arguments.of(
                        "without an audience",
                        bearer(TestTokens.token().audience().sign())),
                Arguments.of(
                        "from another issuer",
                        bearer(TestTokens.token()
                                .issuer("https://auth.dentasoft.test/realms/other")
                                .sign())),
                Arguments.of(
                        "from the right host without the realm",
                        bearer(TestTokens.token()
                                .issuer("https://auth.dentasoft.test")
                                .sign())),
                Arguments.of(
                        "signed with another key under the trusted key ID",
                        bearer(TestTokens.token().signWith(TestJwks.otherKey("another-key")))),
                Arguments.of(
                        "signed with a key the JWK set does not publish",
                        bearer(TestTokens.token().keyId("unknown-key").signWith(TestJwks.otherKey("unknown-key")))),
                Arguments.of(
                        "unsigned, with alg: none", bearer(TestTokens.token().unsigned())),
                Arguments.of(
                        "HS256 with the public key as the HMAC secret",
                        bearer(TestTokens.token().hs256WithPublicKey())),
                Arguments.of(
                        "whose sub is not a UUID",
                        bearer(TestTokens.token()
                                .subject("service-account-dentasoft")
                                .sign())),
                Arguments.of(
                        "whose sub is a non-canonical UUID",
                        bearer(TestTokens.token().subject("1-1-1-1-1").sign())),
                Arguments.of(
                        "without a sub", bearer(TestTokens.token().subject(null).sign())),
                Arguments.of("that is not a JWT", bearer("not-a-token")),
                Arguments.of("in another scheme", "Basic " + TestTokens.token().sign()));
    }

    /** The token is checked even on the platform's own chain, before its role. */
    @Test
    void aValidSignatureDoesNotRescueAnExpiredPlatformToken() {
        String token = TestTokens.platformAdmin(UUID.randomUUID())
                .expiresAt(Instant.now().minus(Duration.ofMinutes(2)))
                .bearer();

        assertUnauthenticated(mvc.get()
                .uri("/api/v1/platform/clinics")
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange());
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
