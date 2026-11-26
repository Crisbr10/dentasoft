package es.crisbr.dentasoft.tenancy.web;

import static es.crisbr.dentasoft.SecurityResponses.assertForbidden;
import static es.crisbr.dentasoft.SecurityResponses.assertUnauthenticated;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.TestData;
import es.crisbr.dentasoft.TestTokens;
import es.crisbr.dentasoft.identity.ClinicRole;
import es.crisbr.dentasoft.identity.IdentityTestDatabase;
import es.crisbr.dentasoft.tenancy.TenancyTestDatabase;
import es.crisbr.dentasoft.tenancy.TenantContext;
import es.crisbr.dentasoft.tenancy.persistence.ClinicSettings;
import es.crisbr.dentasoft.tenancy.persistence.ClinicSettingsRepository;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The clinic configuration API (M01-T06) end to end, with real tokens and the {@code X-Clinic-Id} header: 401 without
 * a valid token, open to the clinic administrator of the active clinic, and 403 for any other role and for a platform
 * administrator without a membership.
 */
@IntegrationTest
@AutoConfigureMockMvc
class ClinicApiSecurityTests {

    private static final UUID CLINIC_A = TestData.CLINIC_A.id();
    private static final UUID ADMIN = UUID.fromString("5f0c2b1e-0000-4000-8000-0000000004d1");
    private static final UUID RECEPTIONIST = UUID.fromString("5f0c2b1e-0000-4000-8000-0000000004d2");
    private static final UUID PLATFORM_ADMIN = UUID.fromString("5f0c2b1e-0000-4000-8000-0000000004d3");

    private static final String LOCATION = "/api/v1/clinic/locations/0190a6e8-0000-7000-8000-0000000000aa";
    private static final String CLINIC_BODY =
            "{\"name\":\"Clínica Prueba Norte\",\"timezone\":\"Europe/Madrid\",\"version\":0}";
    private static final String SETTINGS_BODY =
            "{\"defaultAppointmentMinutes\":45,\"contactEmail\":\"\",\"contactPhone\":\"\",\"version\":0}";
    private static final String HOURS_BODY =
            "{\"slots\":[{\"dayOfWeek\":\"MONDAY\",\"openingTime\":\"09:00\",\"closingTime\":\"14:00\"}]}";
    private static final String LOCATION_BODY = "{\"name\":\"Gabinete 1\",\"color\":\"#1E88E5\",\"version\":0}";
    private static final String VERSION_BODY = "{\"version\":0}";

    /** Every endpoint of M01-T06, with a valid body: a refusal comes from the role, never from validation. */
    private static final List<String> ENDPOINTS = List.of(
            "GET /api/v1/clinic",
            "PUT /api/v1/clinic " + CLINIC_BODY,
            "GET /api/v1/clinic/settings",
            "PUT /api/v1/clinic/settings " + SETTINGS_BODY,
            "GET /api/v1/clinic/hours-of-operation",
            "PUT /api/v1/clinic/hours-of-operation " + HOURS_BODY,
            "GET /api/v1/clinic/locations",
            "POST /api/v1/clinic/locations " + LOCATION_BODY,
            "GET " + LOCATION,
            "PUT " + LOCATION + " " + LOCATION_BODY,
            "POST " + LOCATION + "/deactivation " + VERSION_BODY,
            "POST " + LOCATION + "/activation " + VERSION_BODY);

    /** Children first: they refer to the clinic. */
    private static final List<String> TABLES =
            List.of("clinic_settings", "clinic_hours_of_operation", "location", "clinic_membership");

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private ClinicSettingsRepository settings;

    @Autowired
    private TransactionTemplate transaction;

    @BeforeEach
    void seed() throws SQLException {
        TenancyTestDatabase.insertClinics();
        TenantContext.runAs(
                CLINIC_A,
                () -> transaction.executeWithoutResult(
                        status -> settings.save(new ClinicSettings(ClinicSettings.DEFAULT_APPOINTMENT_MINUTES))));
        IdentityTestDatabase.insertUser(ADMIN);
        IdentityTestDatabase.insertUser(RECEPTIONIST);
        IdentityTestDatabase.insertMembership(CLINIC_A, ADMIN, ClinicRole.ADMIN_CLINICA, true);
        IdentityTestDatabase.insertMembership(CLINIC_A, RECEPTIONIST, ClinicRole.RECEPCION, true);
    }

    @AfterEach
    void deleteData() throws SQLException {
        TenancyTestDatabase.deleteClinicsAnd(TABLES);
        IdentityTestDatabase.deleteUsers(List.of(ADMIN, RECEPTIONIST, PLATFORM_ADMIN));
    }

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
        assertUnauthenticated(request(request + " " + CLINIC_BODY)
                .header("X-Clinic-Id", CLINIC_A.toString())
                .exchange());
    }

    /** The clinic administrator reaches every endpoint, on a location created on the way. */
    @Test
    void theClinicAdministratorUsesEveryEndpoint() {
        assertThat(as(ADMIN, "GET /api/v1/clinic")).hasStatusOk();
        assertThat(as(ADMIN, "PUT /api/v1/clinic " + CLINIC_BODY)).hasStatusOk();
        assertThat(as(ADMIN, "GET /api/v1/clinic/settings")).hasStatusOk();
        assertThat(as(ADMIN, "PUT /api/v1/clinic/settings " + SETTINGS_BODY)).hasStatusOk();
        assertThat(as(ADMIN, "GET /api/v1/clinic/hours-of-operation")).hasStatusOk();
        assertThat(as(ADMIN, "PUT /api/v1/clinic/hours-of-operation " + HOURS_BODY))
                .hasStatusOk();
        assertThat(as(ADMIN, "GET /api/v1/clinic/locations")).hasStatusOk();

        MvcTestResult created = as(ADMIN, "POST /api/v1/clinic/locations " + LOCATION_BODY);
        assertThat(created).hasStatus(HttpStatus.CREATED);
        String location = "/api/v1/clinic/locations/" + JsonPath.read(body(created), "$.id");
        assertThat(as(ADMIN, "GET " + location)).hasStatusOk();
        MvcTestResult updated = as(ADMIN, "PUT " + location + " " + LOCATION_BODY);
        assertThat(updated).hasStatusOk();
        MvcTestResult deactivated = as(ADMIN, "POST " + location + "/deactivation " + versionOf(updated));
        assertThat(deactivated).hasStatusOk();
        assertThat(as(ADMIN, "POST " + location + "/activation " + versionOf(deactivated)))
                .hasStatusOk();
    }

    @Test
    void anyOtherRoleIsForbiddenEverywhere() {
        for (String endpoint : ENDPOINTS) {
            assertForbidden(as(RECEPTIONIST, endpoint));
        }
    }

    /** Not even the platform administrator: the realm role says nothing about a clinic. */
    @Test
    void deniesThePlatformAdministratorWithoutAMembership() {
        assertForbidden(request("GET /api/v1/clinic")
                .header(
                        HttpHeaders.AUTHORIZATION,
                        TestTokens.platformAdmin(PLATFORM_ADMIN).bearer())
                .header("X-Clinic-Id", CLINIC_A.toString())
                .exchange());
    }

    /** The chain covers {@code /api/v1/clinic/**} only: the rest of the application is not affected. */
    @Test
    void leavesOtherPathsAsTheyWere() {
        assertThat(mvc.get().uri("/actuator/health")).hasStatusOk();
        assertThat(mvc.get().uri("/api/v1/clinics").header(HttpHeaders.AUTHORIZATION, TestTokens.anyUser()))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    private MvcTestResult as(UUID user, String request) {
        return request(request)
                .header(HttpHeaders.AUTHORIZATION, TestTokens.token(user).bearer())
                .header("X-Clinic-Id", CLINIC_A.toString())
                .exchange();
    }

    /** {@code "METHOD path [body]"}. */
    private MockMvcRequestBuilder request(String request) {
        String[] parts = request.split(" ", 3);
        MockMvcRequestBuilder builder = mvc.method(HttpMethod.valueOf(parts[0])).uri(parts[1]);
        return parts.length < 3
                ? builder
                : builder.contentType(MediaType.APPLICATION_JSON).content(parts[2]);
    }

    /** The body of a status change for the location in {@code result}, at its current version. */
    private static String versionOf(MvcTestResult result) {
        int version = JsonPath.read(body(result), "$.version");
        return "{\"version\":" + version + "}";
    }

    private static String body(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }
}
