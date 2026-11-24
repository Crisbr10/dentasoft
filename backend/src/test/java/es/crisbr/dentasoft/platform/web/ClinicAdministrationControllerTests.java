package es.crisbr.dentasoft.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.platform.PlatformTestDatabase;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The platform API's own behaviour (status codes, validation, body), ready for when M02-T02 opens it. The servlet
 * filters, the security chain among them, are left out on purpose: {@link PlatformApiClosedTests} proves the chain
 * denies every request today. The service still checks the role.
 */
@IntegrationTest
@AutoConfigureMockMvc(addFilters = false)
@WithMockUser(roles = "PLATFORM_ADMIN")
class ClinicAdministrationControllerTests {

    private static final String CLINICS = "/api/v1/platform/clinics";

    private final List<UUID> created = new ArrayList<>();

    @Autowired
    private MockMvcTester mvc;

    @AfterEach
    void deleteCreatedClinics() throws SQLException {
        PlatformTestDatabase.deleteClinics(created);
    }

    @Test
    void createsAClinicAndReturnsItsLocation() throws Exception {
        MvcTestResult result =
                create("{\"name\":\" Clínica Web \",\"legalName\":\"Clínica Web S.L.\",\"taxId\":\"B12345678\"}");

        assertThat(result).hasStatus(HttpStatus.CREATED);
        String body = result.getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
        created.add(id);
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).endsWith(CLINICS + "/" + id);
        assertThat((String) JsonPath.read(body, "$.name")).isEqualTo("Clínica Web");
        assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("ACTIVE");
        assertThat((String) JsonPath.read(body, "$.timezone")).isEqualTo("Europe/Madrid");
    }

    @Test
    void rejectsAnInvalidClinicNamingEveryField() {
        MvcTestResult result = create("{\"name\":\"\",\"legalName\":\"" + "x".repeat(201)
                + "\",\"taxId\":\"b-123\",\"timezone\":\"Mars/Olympus\",\"locale\":\"spanish\"}");

        assertThat(result)
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .asArray()
                .containsExactlyInAnyOrder("name", "legalName", "taxId", "timezone", "locale");
    }

    @Test
    void listsClinicsAsAPage() throws Exception {
        created.add(idOf(create("{\"name\":\"Clínica Lista\",\"legalName\":\"Lista S.L.\",\"taxId\":\"B1\"}")));

        MvcTestResult result =
                mvc.get().uri(CLINICS + "?size=5&sort=createdAt,desc").exchange();

        assertThat(result).hasStatusOk();
        String body = result.getResponse().getContentAsString();
        assertThat((Integer) JsonPath.read(body, "$.page.size")).isEqualTo(5);
        assertThat((Integer) JsonPath.read(body, "$.page.totalElements")).isPositive();
        assertThat((List<String>) JsonPath.read(body, "$.content[*].id"))
                .contains(created.getFirst().toString());
    }

    @Test
    void rejectsSortingByAnUnknownProperty() {
        assertThat(mvc.get().uri(CLINICS + "?sort=keycloakOrgId"))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.title")
                .isEqualTo("Orden no válido");
    }

    @Test
    void suspendsAndReactivatesAndReportsConflicts() throws Exception {
        UUID id = idOf(create("{\"name\":\"Clínica Estado\",\"legalName\":\"Estado S.L.\",\"taxId\":\"B2\"}"));
        created.add(id);

        assertThat(mvc.post().uri(CLINICS + "/" + id + "/suspension"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("SUSPENDED");
        assertThat(mvc.post().uri(CLINICS + "/" + id + "/suspension"))
                .hasStatus(HttpStatus.CONFLICT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.detail")
                .isEqualTo("La clínica ya está suspendida.");
        assertThat(mvc.post().uri(CLINICS + "/" + id + "/reactivation"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("ACTIVE");
        assertThat(mvc.post().uri(CLINICS + "/" + id + "/reactivation"))
                .hasStatus(HttpStatus.CONFLICT)
                .bodyJson()
                .extractingPath("$.detail")
                .isEqualTo("La clínica ya está activa.");
    }

    @Test
    void anUnknownClinicIsNotFound() {
        assertThat(mvc.post().uri(CLINICS + "/" + UUID.randomUUID() + "/suspension"))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.title")
                .isEqualTo("Clínica no encontrada");
    }

    @Test
    @WithMockUser(roles = "ADMIN_CLINICA")
    void anotherRoleIsForbiddenByTheService() {
        assertThat(mvc.get().uri(CLINICS))
                .hasStatus(HttpStatus.FORBIDDEN)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
    }

    private MvcTestResult create(String json) {
        return mvc.post()
                .uri(CLINICS)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .exchange();
    }

    private static UUID idOf(MvcTestResult result) throws Exception {
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return UUID.fromString(JsonPath.read(result.getResponse().getContentAsString(), "$.id"));
    }
}
