package es.crisbr.dentasoft.platform.web;

import static es.crisbr.dentasoft.TestPostgresConfiguration.connectAsPlatform;
import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.IntegrationTest;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
 * Until authentication exists (M02-T02), the platform API answers 403 to every request, even one from a platform
 * administrator: the chain denies it before any controller or service runs. Never open.
 */
@IntegrationTest
@AutoConfigureMockMvc
class PlatformApiClosedTests {

    private static final String CLINIC = "/api/v1/platform/clinics/0190a6e8-0000-7000-8000-0000000000aa";

    private static final String VALID_CLINIC =
            "{\"name\":\"Clínica Cerrada\",\"legalName\":\"Clínica Cerrada S.L.\",\"taxId\":\"B12345678\"}";

    @Autowired
    private MockMvcTester mvc;

    @ParameterizedTest
    @ValueSource(
            strings = {
                "GET /api/v1/platform/clinics",
                "POST /api/v1/platform/clinics",
                "POST " + CLINIC + "/suspension",
                "POST " + CLINIC + "/reactivation",
                "GET /api/v1/platform",
                "GET /api/v1/platform/",
                "GET /api/v1/platform/clinics/",
                "GET /api/v1/platform/does-not-exist",
                "OPTIONS /api/v1/platform/clinics"
            })
    void deniesAnonymousRequests(String request) throws SQLException {
        assertDenied(request);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "GET /api/v1/platform/clinics",
                "POST /api/v1/platform/clinics",
                "POST " + CLINIC + "/suspension",
                "POST " + CLINIC + "/reactivation"
            })
    @WithMockUser(roles = "PLATFORM_ADMIN")
    void deniesEvenThePlatformAdministrator(String request) throws SQLException {
        assertDenied(request);
    }

    /** The rest of the application is not affected by the platform chain. */
    @Test
    void leavesOtherPathsAsTheyWere() {
        assertThat(mvc.get().uri("/actuator/health")).hasStatusOk();
    }

    private void assertDenied(String request) throws SQLException {
        String[] parts = request.split(" ", 2);
        long clinicsBefore = clinicCount();

        assertThat(mvc.method(HttpMethod.valueOf(parts[0]))
                        .uri(parts[1])
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_CLINIC))
                .hasStatus(HttpStatus.FORBIDDEN)
                // Written by the MVC exception resolvers, not by a forward to Spring Boot's error page.
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.title")
                .isEqualTo("Acceso denegado");

        assertThat(clinicCount()).isEqualTo(clinicsBefore);
    }

    private static long clinicCount() throws SQLException {
        try (Connection connection = connectAsPlatform();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT count(*) FROM clinic")) {
            result.next();
            return result.getLong(1);
        }
    }
}
