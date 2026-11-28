package es.crisbr.dentasoft.platform.web;

import static es.crisbr.dentasoft.SecurityResponses.assertForbidden;
import static es.crisbr.dentasoft.SecurityResponses.assertUnauthenticated;
import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.TestTokens;
import es.crisbr.dentasoft.platform.PlatformTestDatabase;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;

/**
 * The platform API (M02-T02) is only for the {@code PLATFORM_ADMIN} realm role of the access token: 401 without a
 * valid token, 403 for any other user, and nothing changes in either case.
 */
@IntegrationTest
@AutoConfigureMockMvc
class PlatformApiSecurityTests {

    private static final String CLINIC = "/api/v1/platform/clinics/0190a6e8-0000-7000-8000-0000000000aa";

    private static final String VALID_CLINIC =
            "{\"name\":\"Clínica Cerrada\",\"legalName\":\"Clínica Cerrada S.L.\",\"taxId\":\"B12345674\"}";

    @Autowired
    private MockMvcTester mvc;

    @ParameterizedTest
    @ValueSource(
            strings = {
                "GET /api/v1/platform/clinics",
                "POST /api/v1/platform/clinics",
                "POST " + CLINIC + "/suspension",
                "POST " + CLINIC + "/reactivation",
                "PUT " + CLINIC + "/fiscal-data",
                "GET " + CLINIC + "/admin-invitation",
                "POST " + CLINIC + "/admin-invitation",
                "POST " + CLINIC + "/admin-invitation/resend",
                "POST " + CLINIC + "/admin-invitation/cancellation",
                "GET /api/v1/platform",
                "GET /api/v1/platform/does-not-exist",
                "OPTIONS /api/v1/platform/clinics"
            })
    void rejectsRequestsWithoutAToken(String request) throws SQLException {
        long clinicsBefore = PlatformTestDatabase.clinicCount();

        assertUnauthenticated(request(request).exchange());

        assertThat(PlatformTestDatabase.clinicCount()).isEqualTo(clinicsBefore);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "GET /api/v1/platform/clinics",
                "POST /api/v1/platform/clinics",
                "POST " + CLINIC + "/suspension",
                "POST " + CLINIC + "/reactivation",
                "PUT " + CLINIC + "/fiscal-data",
                "GET " + CLINIC + "/admin-invitation",
                "POST " + CLINIC + "/admin-invitation",
                "POST " + CLINIC + "/admin-invitation/resend",
                "POST " + CLINIC + "/admin-invitation/cancellation",
                "GET /api/v1/platform/does-not-exist"
            })
    void deniesAUserWithoutTheRole(String request) throws SQLException {
        long clinicsBefore = PlatformTestDatabase.clinicCount();

        assertForbidden(request(request)
                .header(HttpHeaders.AUTHORIZATION, TestTokens.anyUser())
                .exchange());

        assertThat(PlatformTestDatabase.clinicCount()).isEqualTo(clinicsBefore);
    }

    /** Only the realm role counts: the same name as a client role, a scope or another claim grants nothing. */
    @Test
    void deniesThePlatformRoleOutsideTheRealmRoles() {
        String token = TestTokens.token()
                .realmRoles("offline_access", "default-roles-dentasoft", "platform_admin", "ROLE_PLATFORM_ADMIN")
                .claim("resource_access", Map.of("dentasoft-web", Map.of("roles", List.of("PLATFORM_ADMIN"))))
                .claim("scope", "openid PLATFORM_ADMIN")
                .claim("roles", List.of("PLATFORM_ADMIN"))
                .bearer();

        assertForbidden(mvc.get()
                .uri("/api/v1/platform/clinics")
                .header(HttpHeaders.AUTHORIZATION, token)
                .exchange());
    }

    @Test
    void letsThePlatformAdministratorIn() {
        String token = TestTokens.platformAdmin(UUID.randomUUID()).bearer();

        assertThat(mvc.get().uri("/api/v1/platform/clinics").header(HttpHeaders.AUTHORIZATION, token))
                .hasStatusOk();
    }

    /** The rest of the application is not affected by the platform chain. */
    @Test
    void leavesTheHealthCheckPublic() {
        assertThat(mvc.get().uri("/actuator/health")).hasStatusOk();
    }

    private MockMvcRequestBuilder request(String request) {
        String[] parts = request.split(" ", 2);
        return mvc.method(HttpMethod.valueOf(parts[0]))
                .uri(parts[1])
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_CLINIC);
    }
}
