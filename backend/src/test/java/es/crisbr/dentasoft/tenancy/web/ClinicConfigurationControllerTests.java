package es.crisbr.dentasoft.tenancy.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import es.crisbr.dentasoft.TestData;
import es.crisbr.dentasoft.tenancy.internal.ClinicConfigurationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The clinic configuration API's own behaviour (status codes, validation, body), ready for when M02-T02 opens it. The
 * servlet filters, the security chain among them, are left out on purpose: {@link ClinicApiClosedTests} proves the
 * chain denies every request today. The services still check the role, and the active clinic is set as M02 will set
 * it: around the request.
 */
@AutoConfigureMockMvc(addFilters = false)
class ClinicConfigurationControllerTests extends ClinicConfigurationTestBase {

    private static final String CLINIC = "/api/v1/clinic";
    private static final String HOURS = CLINIC + "/hours-of-operation";
    private static final String LOCATIONS = CLINIC + "/locations";

    @Autowired
    private MockMvcTester mvc;

    @Test
    void readsTheClinicWithEuropeMadridByDefault() {
        MvcTestResult result = asClinicA(() -> mvc.get().uri(CLINIC).exchange());

        assertThat(result).hasStatusOk().bodyJson().extractingPath("$.timezone").isEqualTo("Europe/Madrid");
        assertThat(result).bodyJson().extractingPath("$.id").isEqualTo(CLINIC_A.toString());
    }

    @Test
    void updatesTheClinicAndRefusesAnOldVersion() {
        String change = "{\"name\":\" Clínica Prueba Renombrada \",\"timezone\":\"Atlantic/Canary\",\"version\":0}";

        assertThat(asClinicA(() -> send(mvc.put().uri(CLINIC), change)))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.name")
                .isEqualTo("Clínica Prueba Renombrada");
        assertThat(asClinicA(() -> send(mvc.put().uri(CLINIC), change)))
                .hasStatus(HttpStatus.CONFLICT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.title")
                .isEqualTo("Datos modificados por otra persona");
    }

    @Test
    void rejectsAnInvalidClinicNamingEveryField() {
        MvcTestResult result =
                asClinicA(() -> send(mvc.put().uri(CLINIC), "{\"name\":\"\",\"timezone\":\"Mars/Olympus\"}"));

        assertThat(result)
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .asArray()
                .containsExactlyInAnyOrder("name", "timezone", "version");
    }

    /** The legal name and tax id are not part of the request: sending them changes nothing. */
    @Test
    void ignoresTaxDataSentWithTheClinic() {
        asClinicA(() -> send(
                mvc.put().uri(CLINIC),
                "{\"name\":\"Clínica Prueba\",\"timezone\":\"Europe/Madrid\",\"version\":0,"
                        + "\"legalName\":\"Otra S.L.\",\"taxId\":\"B99999999\"}"));

        MvcTestResult result = asClinicA(() -> mvc.get().uri(CLINIC).exchange());
        assertThat(result).bodyJson().extractingPath("$.taxId").isEqualTo("B00000000");
        assertThat(result).bodyJson().extractingPath("$.legalName").isEqualTo(TestData.CLINIC_A.name() + " S.L.");
    }

    @Test
    void updatesTheSettingsAndRejectsInvalidOnes() {
        MvcTestResult updated = asClinicA(() -> send(
                mvc.put().uri(CLINIC + "/settings"),
                "{\"defaultAppointmentMinutes\":45,\"contactEmail\":\"\",\"contactPhone\":\"+34 600 000 000\","
                        + "\"version\":0}"));
        assertThat(updated)
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.defaultAppointmentMinutes")
                .isEqualTo(45);
        assertThat(updated).bodyJson().extractingPath("$.contactEmail").isNull();
        assertThat(asClinicA(() -> send(
                        mvc.put().uri(CLINIC + "/settings"),
                        "{\"defaultAppointmentMinutes\":4,\"contactEmail\":\"no-es-un-email\","
                                + "\"contactPhone\":\"llámame\",\"version\":1}")))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .asArray()
                .containsExactlyInAnyOrder("defaultAppointmentMinutes", "contactEmail", "contactPhone");
    }

    @Test
    void replacesTheWeekAndRejectsOverlaps() {
        assertThat(asClinicA(() -> send(
                        mvc.put().uri(HOURS),
                        "{\"slots\":[{\"dayOfWeek\":\"MONDAY\",\"openingTime\":\"16:00\",\"closingTime\":\"20:00\"},"
                                + "{\"dayOfWeek\":\"MONDAY\",\"openingTime\":\"09:00\",\"closingTime\":\"14:00\"}]}")))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$[*].openingTime")
                .asArray()
                .containsExactly("09:00:00", "16:00:00");
        assertThat(asClinicA(() -> send(
                        mvc.put().uri(HOURS),
                        "{\"slots\":[{\"dayOfWeek\":\"MONDAY\",\"openingTime\":\"09:00\",\"closingTime\":\"14:00\"},"
                                + "{\"dayOfWeek\":\"MONDAY\",\"openingTime\":\"13:00\",\"closingTime\":\"18:00\"}]}")))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.title")
                .isEqualTo("Horario no válido");
        assertThat(asClinicA(() -> mvc.get().uri(HOURS).exchange()))
                .bodyJson()
                .extractingPath("$.length()")
                .isEqualTo(2);
    }

    @Test
    void rejectsAnIncompleteSlotAndAnUnknownDay() {
        assertThat(asClinicA(() -> send(
                        mvc.put().uri(HOURS), "{\"slots\":[{\"dayOfWeek\":\"MONDAY\",\"openingTime\":\"09:00\"}]}")))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .asArray()
                .containsExactly("slots[0].closingTime");
        assertThat(
                        asClinicA(
                                () -> send(
                                        mvc.put().uri(HOURS),
                                        "{\"slots\":[{\"dayOfWeek\":\"FUNDAY\",\"openingTime\":\"09:00\",\"closingTime\":\"14:00\"}]}")))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void createsARoomAndReturnsItsLocation() throws Exception {
        MvcTestResult result =
                asClinicA(() -> send(mvc.post().uri(LOCATIONS), "{\"name\":\" Gabinete 1 \",\"color\":\"#1E88E5\"}"));

        assertThat(result).hasStatus(HttpStatus.CREATED);
        String body = result.getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");
        assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo(LOCATIONS + "/" + id);
        assertThat((String) JsonPath.read(body, "$.name")).isEqualTo("Gabinete 1");
        assertThat((String) JsonPath.read(body, "$.status")).isEqualTo("ACTIVE");
    }

    @Test
    void reportsARepeatedRoomNameAndAnUnknownRoom() {
        asClinicA(() -> send(mvc.post().uri(LOCATIONS), "{\"name\":\"Gabinete 1\"}"));

        assertThat(asClinicA(() -> send(mvc.post().uri(LOCATIONS), "{\"name\":\"gabinete 1\"}")))
                .hasStatus(HttpStatus.CONFLICT)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.title")
                .isEqualTo("Nombre de gabinete repetido");
        assertThat(asClinicA(() -> mvc.get()
                        .uri(LOCATIONS + "/0190a6e8-0000-7000-8000-0000000000aa")
                        .exchange()))
                .hasStatus(HttpStatus.NOT_FOUND)
                .hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson()
                .extractingPath("$.title")
                .isEqualTo("Gabinete no encontrado");
    }

    @Test
    void rejectsAnInvalidRoom() {
        assertThat(asClinicA(() -> send(mvc.post().uri(LOCATIONS), "{\"name\":\"\",\"color\":\"azul\"}")))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .extractingPath("$.errors[*].field")
                .asArray()
                .containsExactlyInAnyOrder("name", "color");
    }

    @Test
    void deactivatesAndListsRoomsByStatus() throws Exception {
        MvcTestResult created = asClinicA(() -> send(mvc.post().uri(LOCATIONS), "{\"name\":\"Gabinete 1\"}"));
        String id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        assertThat(asClinicA(() -> send(mvc.post().uri(LOCATIONS + "/" + id + "/deactivation"), "{\"version\":0}")))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.status")
                .isEqualTo("INACTIVE");
        assertThat(asClinicA(() -> send(mvc.post().uri(LOCATIONS + "/" + id + "/activation"), "{\"version\":0}")))
                .hasStatus(HttpStatus.CONFLICT);
        assertThat(asClinicA(() -> mvc.get().uri(LOCATIONS + "?status=ACTIVE").exchange()))
                .bodyJson()
                .extractingPath("$.page.totalElements")
                .isEqualTo(0);
        assertThat(asClinicA(() -> mvc.get().uri(LOCATIONS + "?status=INACTIVE").exchange()))
                .bodyJson()
                .extractingPath("$.content[0].id")
                .isEqualTo(id);
    }

    @Test
    void rejectsSortingRoomsByAnUnknownProperty() {
        assertThat(asClinicA(() -> mvc.get().uri(LOCATIONS + "?sort=tenantId").exchange()))
                .hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson()
                .extractingPath("$.title")
                .isEqualTo("Orden no válido");
    }

    private static MvcTestResult send(MockMvcRequestBuilder request, String json) {
        return request.contentType(MediaType.APPLICATION_JSON).content(json).exchange();
    }
}
