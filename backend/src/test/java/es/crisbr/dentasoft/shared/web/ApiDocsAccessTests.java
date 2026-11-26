package es.crisbr.dentasoft.shared.web;

import static es.crisbr.dentasoft.SecurityResponses.assertUnauthenticated;
import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.TestTokens;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * The OpenAPI document and Swagger UI are public only while springdoc serves them ({@code springdoc.api-docs.enabled},
 * which only the {@code local} profile turns on). Everywhere else, as in these tests without a profile, their paths
 * need a token like the rest of the API, and even with one there is no documentation to get.
 */
@IntegrationTest
@AutoConfigureMockMvc
class ApiDocsAccessTests {

    @Autowired
    private MockMvcTester mvc;

    @ParameterizedTest
    @ValueSource(strings = {"/v3/api-docs", "/v3/api-docs/swagger-config", "/swagger-ui/index.html"})
    void withoutSpringdocTheDocumentationPathsNeedAToken(String path) {
        assertUnauthenticated(mvc.get().uri(path).exchange());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v3/api-docs", "/v3/api-docs/swagger-config", "/swagger-ui/index.html"})
    void withoutSpringdocThereIsNoDocumentationEvenWithAToken(String path) {
        assertThat(mvc.get().uri(path).header(HttpHeaders.AUTHORIZATION, TestTokens.anyUser()))
                .hasStatus(HttpStatus.NOT_FOUND)
                .bodyText()
                .doesNotContain("\"openapi\"", "<html");
    }

    /** As in the {@code local} profile, but through the property alone: the rule follows it, not a profile name. */
    @Nested
    @TestPropertySource(properties = {"springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true"})
    class WhileSpringdocServesTheDocumentation {

        @Test
        void theOpenApiDocumentIsPublic() {
            assertThat(mvc.get().uri("/v3/api-docs")).hasStatusOk().bodyText().contains("\"openapi\"");
        }

        @Test
        void swaggerUiIsPublic() {
            assertThat(mvc.get().uri("/swagger-ui/index.html")).hasStatusOk();
        }

        @Test
        void theRestOfTheApiStillNeedsAToken() {
            assertUnauthenticated(mvc.get().uri("/api/v1/test/ping").exchange());
            assertUnauthenticated(mvc.get().uri("/v3/other").exchange());
        }
    }
}
