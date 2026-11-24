package es.crisbr.dentasoft.tenancy.web;

import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Until authentication exists (M02-T02), the clinic configuration API answers 403 to every request, even one from a
 * clinic administrator: without a signed-in user there is no active clinic, and the chain denies the request before
 * any controller or service runs. Never open.
 */
@IntegrationTest
@AutoConfigureMockMvc
class ClinicApiClosedTests {

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
    void deniesAnonymousRequests(String request) {
        assertDenied(request);
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
    @WithMockUser(roles = "ADMIN_CLINICA")
    void deniesEvenTheClinicAdministrator(String request) {
        assertDenied(request);
    }

    /** The chain covers {@code /api/v1/clinic/**} only: the rest of the application is not affected. */
    @Test
    void leavesOtherPathsAsTheyWere() {
        assertThat(mvc.get().uri("/actuator/health")).hasStatusOk();
        assertThat(mvc.get().uri("/api/v1/clinics")).hasStatus(HttpStatus.NOT_FOUND);
    }

    private void assertDenied(String request) {
        String[] parts = request.split(" ", 2);

        assertThat(mvc.method(HttpMethod.valueOf(parts[0]))
                        .uri(parts[1])
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Clínica Cerrada\",\"timezone\":\"Europe/Madrid\",\"version\":0}"))
                .hasStatus(HttpStatus.FORBIDDEN)
                // Written by the MVC exception resolvers, not by a forward to Spring Boot's error page.
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.title")
                .isEqualTo("Acceso denegado");
    }
}
