package es.crisbr.dentasoft;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The 401 and 403 every security chain answers ({@code ApiSecurity}): a {@code ProblemDetail} written by the MVC
 * exception resolvers, with the same generic text whatever the reason, and nothing about the token.
 */
public final class SecurityResponses {

    /** What a refusal must never tell the client: these words would say why the token was rejected. */
    private static final String[] REASONS = {
        "expired", "audience", "issuer", "signature", "algorithm", "Jwt", "invalid_token", "error_description", "kid"
    };

    private SecurityResponses() {}

    public static void assertUnauthenticated(MvcTestResult result) {
        assertThat(result)
                .hasStatus(HttpStatus.UNAUTHORIZED)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .hasHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                // Written after the request filters ran, like any other answer.
                .containsHeader("X-Request-Id");
        assertThat(result.getResponse().getHeaders(HttpHeaders.WWW_AUTHENTICATE))
                .containsExactly("Bearer");
        assertThat(result).bodyJson().extractingPath("$.title").isEqualTo("Autenticación necesaria");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.detail")
                .isEqualTo("Inicia sesión para realizar esta operación.");
        assertTellsNothing(result);
    }

    public static void assertForbidden(MvcTestResult result) {
        assertThat(result)
                .hasStatus(HttpStatus.FORBIDDEN)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .containsHeader("X-Request-Id");
        assertThat(result).bodyJson().extractingPath("$.title").isEqualTo("Acceso denegado");
        assertThat(result)
                .bodyJson()
                .extractingPath("$.detail")
                .isEqualTo("No tienes permiso para realizar esta operación.");
        assertTellsNothing(result);
    }

    private static void assertTellsNothing(MvcTestResult result) {
        String body = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        for (String reason : REASONS) {
            assertThat(body).doesNotContainIgnoringCase(reason);
        }
    }
}
