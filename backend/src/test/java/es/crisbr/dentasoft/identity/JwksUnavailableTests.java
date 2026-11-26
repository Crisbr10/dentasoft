package es.crisbr.dentasoft.identity;

import static es.crisbr.dentasoft.SecurityResponses.assertUnauthenticated;
import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.TestTokens;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Keycloak down or hung (M02-T02): its JWK set address accepts connections and never answers. The application still
 * starts, because the keys are only asked on the first token, and requests get a 401 within the JWK set timeouts (2 s
 * to connect, 3 s to read) instead of hanging. Keycloak is asked again at most twice every 30 seconds, so the following
 * requests do not wait at all.
 */
@IntegrationTest
@AutoConfigureMockMvc
class JwksUnavailableTests {

    /** Accepted by the operating system's backlog, never read or answered. */
    private static final ServerSocket SILENT_KEYCLOAK = open();

    /** Connection and read timeouts, plus margin for a slow machine. */
    private static final Duration ATTEMPT_LIMIT = Duration.ofSeconds(5);

    @Autowired
    private MockMvcTester mvc;

    @DynamicPropertySource
    static void silentJwkSet(DynamicPropertyRegistry registry) {
        registry.add(
                "dentasoft.auth.jwk-set-uri",
                () -> "http://127.0.0.1:" + SILENT_KEYCLOAK.getLocalPort()
                        + "/realms/dentasoft/protocol/openid-connect/certs");
    }

    @AfterAll
    static void closeSilentKeycloak() throws IOException {
        SILENT_KEYCLOAK.close();
    }

    @Test
    void theApplicationStartsWithoutKeycloak() {
        assertThat(mvc.get().uri("/actuator/health/readiness")).hasStatusOk();
    }

    @Test
    void requestsAreRejectedQuicklyInsteadOfWaitingForKeycloak() {
        String token = TestTokens.platformAdmin(UUID.randomUUID()).bearer();

        // Nimbus allows two attempts in a row, so a key rotation is picked up at once; then none for 30 seconds.
        for (int attempt = 1; attempt <= 2; attempt++) {
            Timed asked = timed(token);
            assertUnauthenticated(asked.result());
            assertThat(asked.took()).as("attempt %d", attempt).isLessThan(ATTEMPT_LIMIT);
        }
        Timed notAsked = timed(token);
        assertUnauthenticated(notAsked.result());
        assertThat(notAsked.took()).as("not asked again within 30 s").isLessThan(Duration.ofSeconds(1));
    }

    private Timed timed(String token) {
        long start = System.nanoTime();
        MvcTestResult result = mvc.get()
                .uri("/api/v1/platform/clinics")
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange();
        return new Timed(result, Duration.ofNanos(System.nanoTime() - start));
    }

    private record Timed(MvcTestResult result, Duration took) {}

    private static ServerSocket open() {
        try {
            return new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
