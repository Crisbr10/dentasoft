package es.crisbr.dentasoft.identity;

import static es.crisbr.dentasoft.SecurityResponses.assertUnauthenticated;
import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.ContextLoggingExtension;
import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.TestTokens;
import es.crisbr.dentasoft.shared.RequestId;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keycloak down or hung (M02-T02): its JWK set address accepts connections and never answers. The application still
 * starts, because the keys are only asked on the first token, and requests get a 401 within the JWK set timeouts (2 s
 * to connect, 3 s to read) instead of hanging. Keycloak is asked again at most twice every 30 seconds, so the following
 * requests do not wait at all.
 */
@IntegrationTest
@AutoConfigureMockMvc
@ExtendWith({OutputCaptureExtension.class, ContextLoggingExtension.class})
class JwksUnavailableTests {

    /** Accepted by the operating system's backlog, never read or answered. */
    private static final ServerSocket SILENT_KEYCLOAK = open();

    /** Connection and read timeouts, plus margin for a slow machine. */
    private static final Duration ATTEMPT_LIMIT = Duration.ofSeconds(5);

    private static final String REQUEST_ID = "X-Request-Id";

    private final JsonMapper json = JsonMapper.builder().build();

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

    /**
     * One test, because the attempts share the JWK source's budget of two fetches per 30 seconds.
     *
     * <ul>
     *   <li>Nimbus allows two attempts in a row, so a key rotation is picked up at once: each waits at most the
     *       timeouts. Then none for 30 seconds: the next refusal does not wait at all.
     *   <li>The 401 goes through the request filters like any other answer: it carries its request ID, and the outage
     *       is logged under that ID without anything from the token. Only the attempts that asked Keycloak are logged:
     *       the refusals while the keys may not be asked again would let anyone fill the log.
     * </ul>
     */
    @Test
    void requestsAreRejectedQuicklyAndTheOutageIsLoggedWithoutTheToken(CapturedOutput output) {
        UUID user = UUID.randomUUID();
        String token = TestTokens.token(user)
                .realmRoles("PLATFORM_ADMIN")
                .claim("email", "ana.inventada@clinica.test")
                .claim("name", "Ana Inventada Prueba")
                .sign();
        int start = output.getOut().length();

        for (int attempt = 1; attempt <= 2; attempt++) {
            Timed asked = timed(token, "jwks-down-" + attempt);
            assertRejectedUnder(asked, "jwks-down-" + attempt);
            assertThat(asked.took()).as("attempt %d", attempt).isLessThan(ATTEMPT_LIMIT);
        }
        Timed notAsked = timed(token, "jwks-down-3");
        assertRejectedUnder(notAsked, "jwks-down-3");
        assertThat(notAsked.took()).as("not asked again within 30 s").isLessThan(Duration.ofSeconds(1));

        List<JsonNode> warnings = output.getOut()
                .substring(start)
                .lines()
                .filter(line -> line.contains("Access token could not be checked"))
                .map(json::readTree)
                .toList();
        assertThat(warnings)
                .map(entry -> entry.path(RequestId.MDC_KEY).asString())
                .containsExactly("jwks-down-1", "jwks-down-2");
        assertThat(output.getAll())
                .doesNotContain(token)
                .doesNotContain(token.substring(token.indexOf('.') + 1, token.lastIndexOf('.')))
                .doesNotContain(user.toString())
                .doesNotContain("ana.inventada")
                .doesNotContain("Ana Inventada");
    }

    private static void assertRejectedUnder(Timed timed, String requestId) {
        assertUnauthenticated(timed.result());
        assertThat(timed.result()).hasHeader(REQUEST_ID, requestId);
    }

    private Timed timed(String token, String requestId) {
        long start = System.nanoTime();
        MvcTestResult result = mvc.get()
                .uri("/api/v1/platform/clinics")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(REQUEST_ID, requestId)
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
