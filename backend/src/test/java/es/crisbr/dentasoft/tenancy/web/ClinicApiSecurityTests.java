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
import es.crisbr.dentasoft.tenancy.persistence.Location;
import es.crisbr.dentasoft.tenancy.persistence.LocationRepository;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The clinic configuration API (M01-T06) end to end, with real tokens and the {@code X-Clinic-Id} header: 401 without
 * a valid token; every role of the active clinic reads everything and only the clinic administrator writes (M01-T10);
 * 403 for a platform administrator without a membership.
 *
 * <p>{@link #coversEveryEndpointOfTheModule()} compares the lists below with the endpoints the application really
 * maps, so a new endpoint fails this test until it is listed as a read or a write.
 */
@IntegrationTest
@AutoConfigureMockMvc
class ClinicApiSecurityTests {

    private static final UUID CLINIC_A = TestData.CLINIC_A.id();
    private static final UUID PLATFORM_ADMIN = UUID.fromString("5f0c2b1e-0000-4000-8000-0000000004d3");

    /** One member of the clinic with each role. */
    private static final Map<ClinicRole, UUID> MEMBERS = members();

    private static final String CLINIC_BODY =
            "{\"name\":\"Clínica Prueba Norte\",\"timezone\":\"Europe/Madrid\",\"version\":0}";
    private static final String SETTINGS_BODY =
            "{\"defaultAppointmentMinutes\":45,\"contactEmail\":\"\",\"contactPhone\":\"\",\"version\":0}";
    private static final String HOURS_BODY =
            "{\"slots\":[{\"dayOfWeek\":\"MONDAY\",\"openingTime\":\"09:00\",\"closingTime\":\"14:00\"}]}";
    private static final String LOCATION_BODY = "{\"name\":\"Gabinete 1\",\"color\":\"#1E88E5\",\"version\":0}";
    private static final String VERSION_BODY = "{\"version\":0}";

    /** Every read of the module, as mapped; {@code {id}} is the seeded room. */
    private static final List<String> READS = List.of(
            "GET /api/v1/clinic",
            "GET /api/v1/clinic/settings",
            "GET /api/v1/clinic/hours-of-operation",
            "GET /api/v1/clinic/locations",
            "GET /api/v1/clinic/locations/{id}");

    /** Every write of the module, as mapped, with a valid body: a refusal comes from the role, never from validation. */
    private static final Map<String, String> WRITES = Map.of(
            "PUT /api/v1/clinic", CLINIC_BODY,
            "PUT /api/v1/clinic/settings", SETTINGS_BODY,
            "PUT /api/v1/clinic/hours-of-operation", HOURS_BODY,
            "POST /api/v1/clinic/locations", LOCATION_BODY,
            "PUT /api/v1/clinic/locations/{id}", LOCATION_BODY,
            "POST /api/v1/clinic/locations/{id}/deactivation", VERSION_BODY,
            "POST /api/v1/clinic/locations/{id}/activation", VERSION_BODY);

    private static final String LOCATION = "/api/v1/clinic/locations/0190a6e8-0000-7000-8000-0000000000aa";

    /** Children first: they refer to the clinic. */
    private static final List<String> TABLES =
            List.of("clinic_settings", "clinic_hours_of_operation", "location", "clinic_membership");

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private ClinicSettingsRepository settings;

    @Autowired
    private LocationRepository locations;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    private UUID settingsId;
    private UUID room;

    @BeforeEach
    void seed() throws SQLException {
        TenancyTestDatabase.insertClinics();
        TenantContext.runAs(
                CLINIC_A,
                () -> transaction.executeWithoutResult(status -> {
                    settingsId = settings.save(new ClinicSettings(ClinicSettings.DEFAULT_APPOINTMENT_MINUTES))
                            .getId();
                    room = locations.save(new Location("Gabinete 1", "#1E88E5")).getId();
                }));
        for (Map.Entry<ClinicRole, UUID> member : MEMBERS.entrySet()) {
            IdentityTestDatabase.insertUser(member.getValue());
            IdentityTestDatabase.insertMembership(CLINIC_A, member.getValue(), member.getKey(), true);
        }
    }

    @AfterEach
    void deleteData() throws SQLException {
        TenancyTestDatabase.deleteClinicsAnd(TABLES);
        List<UUID> users = new ArrayList<>(MEMBERS.values());
        users.add(PLATFORM_ADMIN);
        IdentityTestDatabase.deleteUsers(users);
    }

    @Test
    void coversEveryEndpointOfTheModule() {
        Set<String> mapped = mappings.getHandlerMethods().entrySet().stream()
                .filter(entry -> entry.getValue()
                        .getBeanType()
                        .getPackageName()
                        .equals(ClinicApiSecurityTests.class.getPackageName()))
                .flatMap(entry -> entry.getKey().getMethodsCondition().getMethods().stream()
                        .flatMap(method ->
                                entry.getKey().getPatternValues().stream().map(pattern -> method + " " + pattern)))
                .collect(Collectors.toSet());

        assertThat(mapped)
                .containsExactlyInAnyOrderElementsOf(
                        Stream.concat(READS.stream(), WRITES.keySet().stream()).toList());
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

    @ParameterizedTest
    @EnumSource(ClinicRole.class)
    void everyRoleOfTheClinicReadsEverything(ClinicRole role) {
        for (String read : READS) {
            assertThat(as(MEMBERS.get(role), withRoom(read)))
                    .as("%s as %s", read, role)
                    .hasStatus2xxSuccessful();
        }
    }

    @ParameterizedTest
    @EnumSource(value = ClinicRole.class, names = "ADMIN_CLINICA", mode = EnumSource.Mode.EXCLUDE)
    void onlyTheClinicAdministratorWrites(ClinicRole role) throws SQLException {
        String before = stored();

        for (Map.Entry<String, String> write : WRITES.entrySet()) {
            MvcTestResult result = as(MEMBERS.get(role), withRoom(write.getKey()) + " " + write.getValue());
            assertThat(result).as("%s as %s", write.getKey(), role).hasStatus(HttpStatus.FORBIDDEN);
            assertForbidden(result);
        }

        assertThat(stored()).isEqualTo(before);
    }

    /** The clinic administrator reaches every endpoint, on a location created on the way. */
    @Test
    void theClinicAdministratorUsesEveryEndpoint() {
        UUID admin = MEMBERS.get(ClinicRole.ADMIN_CLINICA);
        assertThat(as(admin, "GET /api/v1/clinic")).hasStatusOk();
        assertThat(as(admin, "PUT /api/v1/clinic " + CLINIC_BODY)).hasStatusOk();
        assertThat(as(admin, "GET /api/v1/clinic/settings")).hasStatusOk();
        assertThat(as(admin, "PUT /api/v1/clinic/settings " + SETTINGS_BODY)).hasStatusOk();
        assertThat(as(admin, "GET /api/v1/clinic/hours-of-operation")).hasStatusOk();
        assertThat(as(admin, "PUT /api/v1/clinic/hours-of-operation " + HOURS_BODY))
                .hasStatusOk();
        assertThat(as(admin, "GET /api/v1/clinic/locations")).hasStatusOk();

        MvcTestResult created =
                as(admin, "POST /api/v1/clinic/locations " + LOCATION_BODY.replace("Gabinete 1", "Gabinete 2"));
        assertThat(created).hasStatus(HttpStatus.CREATED);
        String location = "/api/v1/clinic/locations/" + JsonPath.read(body(created), "$.id");
        assertThat(as(admin, "GET " + location)).hasStatusOk();
        MvcTestResult updated = as(admin, "PUT " + location + " " + LOCATION_BODY.replace("Gabinete 1", "Gabinete 3"));
        assertThat(updated).hasStatusOk();
        MvcTestResult deactivated = as(admin, "POST " + location + "/deactivation " + versionOf(updated));
        assertThat(deactivated).hasStatusOk();
        assertThat(as(admin, "POST " + location + "/activation " + versionOf(deactivated)))
                .hasStatusOk();
    }

    /** Not even the platform administrator: the realm role says nothing about a clinic. */
    @Test
    void deniesThePlatformAdministratorWithoutAMembership() {
        for (String endpoint : Stream.concat(
                        READS.stream(),
                        WRITES.entrySet().stream().map(write -> write.getKey() + " " + write.getValue()))
                .toList()) {
            assertForbidden(request(withRoom(endpoint))
                    .header(
                            HttpHeaders.AUTHORIZATION,
                            TestTokens.platformAdmin(PLATFORM_ADMIN).bearer())
                    .header("X-Clinic-Id", CLINIC_A.toString())
                    .exchange());
        }
    }

    /** The chain covers {@code /api/v1/clinic/**} only: the rest of the application is not affected. */
    @Test
    void leavesOtherPathsAsTheyWere() {
        assertThat(mvc.get().uri("/actuator/health")).hasStatusOk();
        assertThat(mvc.get().uri("/api/v1/clinics").header(HttpHeaders.AUTHORIZATION, TestTokens.anyUser()))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    private static Map<ClinicRole, UUID> members() {
        Map<ClinicRole, UUID> members = new EnumMap<>(ClinicRole.class);
        for (ClinicRole role : ClinicRole.values()) {
            members.put(role, UUID.fromString("5f0c2b1e-0000-4000-8000-0000000005%02d".formatted(role.ordinal())));
        }
        return members;
    }

    private String withRoom(String request) {
        return request.replace("{id}", room.toString());
    }

    /** What the writes could change: the clinic, its settings, its week and the seeded room. */
    private String stored() throws SQLException {
        return String.join(
                "\n",
                TenancyTestDatabase.storedRow("clinic", CLINIC_A, CLINIC_A).orElseThrow(),
                TenancyTestDatabase.storedRow("clinic_settings", CLINIC_A, settingsId)
                        .orElseThrow(),
                TenancyTestDatabase.storedRow("location", CLINIC_A, room).orElseThrow(),
                "hours=" + TenancyTestDatabase.countRows("clinic_hours_of_operation", CLINIC_A),
                "locations=" + TenancyTestDatabase.countRows("location", CLINIC_A));
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
