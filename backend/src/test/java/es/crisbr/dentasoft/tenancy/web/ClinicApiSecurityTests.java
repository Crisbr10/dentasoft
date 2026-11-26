package es.crisbr.dentasoft.tenancy.web;

import static es.crisbr.dentasoft.SecurityResponses.assertForbidden;
import static es.crisbr.dentasoft.SecurityResponses.assertUnauthenticated;
import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.TestTokens;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;

/**
 * The clinic configuration API stays closed until a request can have an active clinic (M02-T04): 401 without a valid
 * token and 403 with one, even a platform administrator's, before any controller or service runs.
 */
@IntegrationTest
@AutoConfigureMockMvc
class ClinicApiSecurityTests {

    private static final String LOCATION = "/api/v1/clinic/locations/0190a6e8-0000-7000-8000-0000000000aa";

    @Autowired
    private MockMvcTester mvc;

    @ParameterizedTest
    @ValueSource(
            strings = {
                "GET /api/v1/clinic",
                "PUT /api/v1/clinic",
                "GET /api/v1/clinic/settings",
                "PUT /api/v1/clinic/settings",
                "GET /api/v1/clinic/hours-of-operation",
                "PUT /api/v1/clinic/hours-of-operation",
                "GET /api/v1/clinic/locations",
                "POST /api/v1/clinic/locations",
                "GET " + LOCATION,
                "PUT " + LOCATION,
                "POST " + LOCATION + "/deactivation",
                "POST " + LOCATION + "/activation",
                "GET /api/v1/clinic/",
                "GET /api/v1/clinic/does-not-exist",
                "OPTIONS /api/v1/clinic/locations"
            })
    void rejectsRequestsWithoutAToken(String request) {
        assertUnauthenticated(request(request).exchange());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "GET /api/v1/clinic",
                "PUT /api/v1/clinic",
                "PUT /api/v1/clinic/hours-of-operation",
                "POST /api/v1/clinic/locations",
                "POST " + LOCATION + "/deactivation"
            })
    void deniesEveryUserUntilTheActiveClinicExists(String request) {
        assertForbidden(request(request)
                .header(HttpHeaders.AUTHORIZATION, TestTokens.anyUser())
                .exchange());
    }

    /** Not even the platform administrator: the realm role says nothing about a clinic. */
    @Test
    void deniesThePlatformAdministrator() {
        assertForbidden(request("GET /api/v1/clinic")
                .header(
                        HttpHeaders.AUTHORIZATION,
                        TestTokens.platformAdmin(UUID.randomUUID()).bearer())
                .exchange());
    }

    /** The chain covers {@code /api/v1/clinic/**} only: the rest of the application is not affected. */
    @Test
    void leavesOtherPathsAsTheyWere() {
        assertThat(mvc.get().uri("/actuator/health")).hasStatusOk();
        assertThat(mvc.get().uri("/api/v1/clinics").header(HttpHeaders.AUTHORIZATION, TestTokens.anyUser()))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    private MockMvcRequestBuilder request(String request) {
        String[] parts = request.split(" ", 2);
        return mvc.method(HttpMethod.valueOf(parts[0]))
                .uri(parts[1])
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Clínica Cerrada\",\"timezone\":\"Europe/Madrid\",\"version\":0}");
    }
}
