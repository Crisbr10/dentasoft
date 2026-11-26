package es.crisbr.dentasoft.identity;

import static es.crisbr.dentasoft.SecurityResponses.assertForbidden;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.TestData;
import es.crisbr.dentasoft.TestTokens;
import es.crisbr.dentasoft.shared.ApiSecurity;
import es.crisbr.dentasoft.tenancy.TenancyTestDatabase;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * {@code /api/v1/clinic/practitioner-roles} with real tokens and the active clinic checked on every request (M02-T06):
 * what the administrator types is validated before anything is stored, conflicts are ProblemDetails, and every member
 * reads the records while only the administrator changes them.
 */
@IntegrationTest
@AutoConfigureMockMvc
class PractitionerRolesApiTests {

    private static final UUID CLINIC_A = TestData.CLINIC_A.id();
    private static final UUID ADMIN = UUID.fromString("5f0c2b1e-0000-4000-8000-0000000008a1");
    private static final UUID RECEPTIONIST = UUID.fromString("5f0c2b1e-0000-4000-8000-0000000008a2");
    private static final UUID DENTIST = UUID.fromString("5f0c2b1e-0000-4000-8000-0000000008a3");
    private static final List<UUID> USERS = List.of(ADMIN, RECEPTIONIST, DENTIST);
    private static final String PATH = "/api/v1/clinic/practitioner-roles";

    private static final String VALID = "{\"displayName\":\"Dra. Ana Pruebas\",\"specialty\":\"ORTHODONTICS\","
            + "\"licenseProvince\":\"28\",\"licenseNumber\":\"28-1234\",\"agendaColor\":\"#1E88E5\"}";

    @Autowired
    private MockMvcTester mvc;

    @BeforeEach
    void seed() throws SQLException {
        TenancyTestDatabase.insertClinic(TestData.CLINIC_A, "ACTIVE");
        TenancyTestDatabase.insertClinic(TestData.CLINIC_B, "ACTIVE");
        for (UUID user : USERS) {
            IdentityTestDatabase.insertUser(user);
        }
        IdentityTestDatabase.insertMembership(CLINIC_A, ADMIN, ClinicRole.ADMIN_CLINICA, true);
        IdentityTestDatabase.insertMembership(CLINIC_A, RECEPTIONIST, ClinicRole.RECEPCION, true);
        IdentityTestDatabase.insertMembership(CLINIC_A, DENTIST, ClinicRole.ODONTOLOGO, true);
    }

    @AfterEach
    void deleteData() throws SQLException {
        TenancyTestDatabase.deleteClinicsAnd(List.of("practitioner_role", "clinic_membership"));
        IdentityTestDatabase.deleteUsers(USERS);
    }

    @Test
    void anAdministratorCreatesARecord() {
        MvcTestResult created = post(PATH, ADMIN, VALID);

        assertThat(created).hasStatus(HttpStatus.CREATED);
        String id = JsonPath.read(body(created), "$.id");
        assertThat(created.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo(PATH + "/" + id);
        assertThat(created).bodyJson().extractingPath("$.displayName").isEqualTo("Dra. Ana Pruebas");
        assertThat(created).bodyJson().extractingPath("$.licenseNumber").isEqualTo("28-1234");
        assertThat(created).bodyJson().extractingPath("$.status").isEqualTo("ACTIVE");
        assertThat(created).bodyJson().extractingPath("$.version").isEqualTo(0);
    }

    /** Names are stored without the spaces around them, and licence numbers in upper case. */
    @Test
    void namesAreStrippedAndLicenseNumbersUpperCased() {
        MvcTestResult created = post(
                PATH,
                ADMIN,
                "{\"displayName\":\"  Dr. Luis Pruebas \",\"specialty\":\"ENDODONTICS\",\"licenseProvince\":\"08\","
                        + "\"licenseNumber\":\"b-77\",\"agendaColor\":\"#43A047\"}");

        assertThat(created).hasStatus(HttpStatus.CREATED);
        assertThat(created).bodyJson().extractingPath("$.displayName").isEqualTo("Dr. Luis Pruebas");
        assertThat(created).bodyJson().extractingPath("$.licenseNumber").isEqualTo("B-77");
    }

    @Test
    void aHygienistNeedsNoLicenseNumber() {
        assertThat(post(
                        PATH,
                        ADMIN,
                        "{\"displayName\":\"Marta Pruebas\",\"specialty\":\"DENTAL_HYGIENE\","
                                + "\"agendaColor\":\"#8E24AA\"}"))
                .hasStatus(HttpStatus.CREATED);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                // The colour: #RRGGBB in upper case, and nothing else.
                "{\"displayName\":\"Dra. Ana\",\"specialty\":\"ORTHODONTICS\",\"licenseProvince\":\"28\",\"licenseNumber\":\"1234\",\"agendaColor\":\"#1e88e5\"}",
                "{\"displayName\":\"Dra. Ana\",\"specialty\":\"ORTHODONTICS\",\"licenseProvince\":\"28\",\"licenseNumber\":\"1234\",\"agendaColor\":\"blue\"}",
                "{\"displayName\":\"Dra. Ana\",\"specialty\":\"ORTHODONTICS\",\"licenseProvince\":\"28\",\"licenseNumber\":\"1234\",\"agendaColor\":\"#1E88E5FF\"}",
                "{\"displayName\":\"Dra. Ana\",\"specialty\":\"ORTHODONTICS\",\"licenseProvince\":\"28\",\"licenseNumber\":\"1234\"}",
                // The clinical area: a closed list.
                "{\"displayName\":\"Dra. Ana\",\"specialty\":\"COSMETIC\",\"licenseProvince\":\"28\",\"licenseNumber\":\"1234\",\"agendaColor\":\"#1E88E5\"}",
                "{\"displayName\":\"Dra. Ana\",\"licenseProvince\":\"28\",\"licenseNumber\":\"1234\",\"agendaColor\":\"#1E88E5\"}",
                // The licence number: required except in hygiene, with its province, and in its format.
                "{\"displayName\":\"Dra. Ana\",\"specialty\":\"ORTHODONTICS\",\"agendaColor\":\"#1E88E5\"}",
                "{\"displayName\":\"Dra. Ana\",\"specialty\":\"ORTHODONTICS\",\"licenseNumber\":\"1234\",\"agendaColor\":\"#1E88E5\"}",
                "{\"displayName\":\"Dra. Ana\",\"specialty\":\"DENTAL_HYGIENE\",\"licenseProvince\":\"28\",\"agendaColor\":\"#1E88E5\"}",
                "{\"displayName\":\"Dra. Ana\",\"specialty\":\"ORTHODONTICS\",\"licenseProvince\":\"53\",\"licenseNumber\":\"1234\",\"agendaColor\":\"#1E88E5\"}",
                "{\"displayName\":\"Dra. Ana\",\"specialty\":\"ORTHODONTICS\",\"licenseProvince\":\"28\",\"licenseNumber\":\"12\",\"agendaColor\":\"#1E88E5\"}",
                "{\"displayName\":\"Dra. Ana\",\"specialty\":\"ORTHODONTICS\",\"licenseProvince\":\"28\",\"licenseNumber\":\"1234567890123\",\"agendaColor\":\"#1E88E5\"}",
                "{\"displayName\":\"Dra. Ana\",\"specialty\":\"ORTHODONTICS\",\"licenseProvince\":\"28\",\"licenseNumber\":\"28 1234\",\"agendaColor\":\"#1E88E5\"}",
                "{\"displayName\":\"Dra. Ana\",\"specialty\":\"ORTHODONTICS\",\"licenseProvince\":\"28\",\"licenseNumber\":\"28/12/34\",\"agendaColor\":\"#1E88E5\"}",
                // The name: letters, no invisible characters, not only spaces.
                "{\"displayName\":\" \",\"specialty\":\"ORTHODONTICS\",\"licenseProvince\":\"28\",\"licenseNumber\":\"1234\",\"agendaColor\":\"#1E88E5\"}",
                "{\"displayName\":\"  \",\"specialty\":\"ORTHODONTICS\",\"licenseProvince\":\"28\",\"licenseNumber\":\"1234\",\"agendaColor\":\"#1E88E5\"}",
                "{\"displayName\":\"Dra.​Ana\",\"specialty\":\"ORTHODONTICS\",\"licenseProvince\":\"28\",\"licenseNumber\":\"1234\",\"agendaColor\":\"#1E88E5\"}",
                "{\"displayName\":\"Dra. Ana\\u0000\",\"specialty\":\"ORTHODONTICS\",\"licenseProvince\":\"28\",\"licenseNumber\":\"1234\",\"agendaColor\":\"#1E88E5\"}",
                "{\"specialty\":\"ORTHODONTICS\",\"licenseProvince\":\"28\",\"licenseNumber\":\"1234\",\"agendaColor\":\"#1E88E5\"}"
            })
    void invalidRecordsAreRefused(String body) {
        assertThat(post(PATH, ADMIN, body)).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(list(ADMIN))
                .bodyJson()
                .extractingPath("$.page.totalElements")
                .isEqualTo(0);
    }

    @Test
    void aRepeatedLicenseNumberIsAConflict() {
        assertThat(post(PATH, ADMIN, VALID)).hasStatus(HttpStatus.CREATED);

        MvcTestResult repeated = post(PATH, ADMIN, VALID);

        assertThat(repeated).hasStatus(HttpStatus.CONFLICT);
        assertThat(repeated).bodyJson().extractingPath("$.title").isEqualTo("Número de colegiado repetido");
    }

    /** User-facing texts speak of «área clínica», never of «especialidad», which would suggest an official title. */
    @Test
    void aClinicalAreaThatDoesNotSuitTheRoleIsAConflictAboutTheClinicalArea() {
        MvcTestResult refused = post(
                PATH,
                ADMIN,
                "{\"userId\":\"" + DENTIST + "\",\"displayName\":\"Dr. Pruebas\",\"specialty\":\"DENTAL_HYGIENE\","
                        + "\"agendaColor\":\"#1E88E5\"}");

        assertThat(refused).hasStatus(HttpStatus.CONFLICT);
        assertThat(refused).bodyJson().extractingPath("$.title").isEqualTo("Área clínica no válida");
        assertThat(body(refused)).doesNotContainIgnoringCase("especialidad");
    }

    @Test
    void everyMemberReadsTheRecordsButOnlyTheAdministratorChangesThem() {
        String id = JsonPath.read(body(post(PATH, ADMIN, VALID)), "$.id");

        assertThat(list(RECEPTIONIST)).hasStatus(HttpStatus.OK);
        assertThat(list(RECEPTIONIST))
                .bodyJson()
                .extractingPath("$.content[0].id")
                .isEqualTo(id);
        assertThat(get(PATH + "/" + id, DENTIST)).hasStatus(HttpStatus.OK);
        assertForbidden(post(PATH, RECEPTIONIST, VALID));
        assertForbidden(post(PATH + "/" + id + "/deactivation", DENTIST, "{\"version\":0}"));
        assertThat(get(PATH + "/" + id, ADMIN))
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("ACTIVE");
    }

    @Test
    void aDeactivatedRecordIsListedAsInactive() {
        String id = JsonPath.read(body(post(PATH, ADMIN, VALID)), "$.id");

        MvcTestResult deactivated = post(PATH + "/" + id + "/deactivation", ADMIN, "{\"version\":0}");

        assertThat(deactivated).hasStatus(HttpStatus.OK);
        assertThat(deactivated).bodyJson().extractingPath("$.status").isEqualTo("INACTIVE");
        assertThat(get(PATH + "?status=ACTIVE", ADMIN))
                .bodyJson()
                .extractingPath("$.page.totalElements")
                .isEqualTo(0);
        assertThat(get(PATH + "?status=INACTIVE", ADMIN))
                .bodyJson()
                .extractingPath("$.content[0].id")
                .isEqualTo(id);
    }

    @Test
    void anUnknownRecordIsNotFound() {
        MvcTestResult missing = get(PATH + "/" + UUID.randomUUID(), ADMIN);

        assertThat(missing).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(missing).bodyJson().extractingPath("$.title").isEqualTo("Profesional no encontrado");
    }

    // --- Helpers ---

    private MvcTestResult list(UUID user) {
        return get(PATH, user);
    }

    private MvcTestResult get(String path, UUID user) {
        return mvc.get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, TestTokens.token(user).bearer())
                .header(ApiSecurity.CLINIC_HEADER, CLINIC_A.toString())
                .exchange();
    }

    private MvcTestResult post(String path, UUID user, String body) {
        return mvc.post()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, TestTokens.token(user).bearer())
                .header(ApiSecurity.CLINIC_HEADER, CLINIC_A.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private static String body(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }
}
