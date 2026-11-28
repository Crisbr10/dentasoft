package es.crisbr.dentasoft;

import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.shared.DentasoftUrls;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import({TestRedisConfiguration.class, TestPostgresConfiguration.class})
// The local profile reads the Keycloak admin secret from the environment, like every secret; this test never calls
// Keycloak.
@TestPropertySource(properties = "dentasoft.keycloak-admin.client-secret=" + FakeKeycloak.CLIENT_SECRET)
class ApiDocsLocalProfileTests {

    @Autowired
    private MockMvcTester mvc;

    @Test
    void exposesOpenApiDocument() {
        assertThat(mvc.get().uri("/v3/api-docs"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.openapi")
                .asString()
                .startsWith("3.");
    }

    @Test
    void serverUrlComesFromDentasoftUrlsApi() {
        assertThat(mvc.get().uri("/v3/api-docs"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.servers[0].url")
                .isEqualTo("http://localhost:8080");
    }

    /**
     * Responses are documented as JSON, not {@code *}{@code /*}: the frontend generator (pnpm api:gen) reads any other
     * media type as a binary blob, so the generated client would not parse the body (M02-T07).
     */
    @Test
    void responsesAreDocumentedAsJson() {
        assertThat(mvc.get().uri("/v3/api-docs"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.paths['/api/v1/me'].get.responses['200'].content")
                .asMap()
                .containsOnlyKeys("application/json");
    }

    /**
     * The platform and the clinic configuration APIs each return their own record called {@code ClinicDetails}; with
     * the same schema name, the generated client typed both with whichever came first (M02-T08).
     */
    @Test
    void clinicDetailsOfEachApiHaveTheirOwnSchema() {
        var document = assertThat(mvc.get().uri("/v3/api-docs")).hasStatusOk().bodyJson();
        document.extractingPath(
                        "$.paths['/api/v1/clinic'].get.responses['200'].content['application/json'].schema.$ref")
                .isEqualTo("#/components/schemas/ClinicConfigurationDetails");
        document.extractingPath(
                        "$.paths['/api/v1/platform/clinics'].post.responses['200'].content['application/json'].schema.$ref")
                .isEqualTo("#/components/schemas/PlatformClinicDetails");
        document.extractingPath("$.components.schemas")
                .asMap()
                .containsKeys("ClinicConfigurationDetails", "PlatformClinicDetails")
                .doesNotContainKey("ClinicDetails");
    }

    @Test
    void localProfileProvidesLocalhostUrls(@Autowired DentasoftUrls urls) {
        assertThat(urls)
                .isEqualTo(
                        new DentasoftUrls("http://localhost:4200", "http://localhost:8080", "http://localhost:8180"));
    }

    @Test
    void exposesSwaggerUi() {
        assertThat(mvc.get().uri("/swagger-ui/index.html")).hasStatusOk();
    }

    @Test
    void swaggerUiGetsAPolicyThatLetsItRenderButNotBeFramed() {
        String policy =
                mvc.get().uri("/swagger-ui/index.html").exchange().getResponse().getHeader("Content-Security-Policy");

        assertThat(policy)
                .contains("default-src 'self'")
                .contains("style-src 'self' 'unsafe-inline'")
                .contains("frame-ancestors 'none'")
                .doesNotContain("script-src");
    }

    @Test
    void theApiDocumentKeepsTheStrictPolicy() {
        assertThat(mvc.get().uri("/v3/api-docs"))
                .hasHeader(
                        "Content-Security-Policy",
                        "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
    }

    @Test
    void localProfileHasADevelopmentRateLimitKey(@Autowired Environment environment) {
        assertThat(environment.getProperty("dentasoft.rate-limit.key")).hasSizeGreaterThanOrEqualTo(32);
    }
}
