package es.crisbr.dentasoft.identity;

import static es.crisbr.dentasoft.SecurityResponses.assertForbidden;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import es.crisbr.dentasoft.FakeKeycloak;
import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.TestData;
import es.crisbr.dentasoft.TestTokens;
import es.crisbr.dentasoft.shared.ApiSecurity;
import es.crisbr.dentasoft.tenancy.TenancyTestDatabase;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * {@code /api/v1/clinic/users} with real tokens and the active clinic checked on every request (M02-T05): the same
 * answer for every invitation, validation of what the administrator types, and a deactivation that takes effect on the
 * very next request.
 */
@IntegrationTest
@AutoConfigureMockMvc
class ClinicUsersApiTests {

    private static final UUID CLINIC_A = TestData.CLINIC_A.id();
    private static final UUID CLINIC_B = TestData.CLINIC_B.id();
    private static final UUID ADMIN = UUID.fromString("5f0c2b1e-0000-4000-8000-0000000006a1");
    private static final UUID RECEPTIONIST = UUID.fromString("5f0c2b1e-0000-4000-8000-0000000006a2");
    private static final String PATH = "/api/v1/clinic/users";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private FakeKeycloak keycloak;

    @Autowired
    private StringRedisTemplate redis;

    private UUID dentist;

    @BeforeEach
    void seed() throws SQLException {
        keycloak.reset();
        deleteQuotas();
        TenancyTestDatabase.insertClinic(TestData.CLINIC_A, "ACTIVE");
        TenancyTestDatabase.insertClinic(TestData.CLINIC_B, "ACTIVE");
        dentist = keycloak.addUser("dentista@clinica-a.test", "Dentista", "Pruebas", true, List.of());
        for (UUID user : List.of(ADMIN, RECEPTIONIST, dentist)) {
            IdentityTestDatabase.insertUser(user);
        }
        IdentityTestDatabase.insertMembership(CLINIC_A, ADMIN, ClinicRole.ADMIN_CLINICA, true);
        IdentityTestDatabase.insertMembership(CLINIC_A, RECEPTIONIST, ClinicRole.RECEPCION, true);
        IdentityTestDatabase.insertMembership(CLINIC_A, dentist, ClinicRole.ODONTOLOGO, true);
        keycloak.addMember(keycloak.addOrganization(CLINIC_A.toString()), dentist);
    }

    @AfterEach
    void deleteData() throws SQLException {
        TenancyTestDatabase.deleteClinicsAnd(List.of("clinic_membership"));
        List<UUID> users = new ArrayList<>(List.of(ADMIN, RECEPTIONIST));
        keycloak.users().forEach(user -> users.add(user.id()));
        IdentityTestDatabase.deleteUsers(users);
        deleteQuotas();
    }

    private void deleteQuotas() {
        Set<String> keys = redis.keys("dentasoft:quota:*");
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    /** Someone new and someone already in Keycloak (another clinic) get exactly the same answer. */
    @Test
    void everyInvitationGetsTheSameEmptyAccepted() {
        keycloak.addUser("de-otra-clinica@clinica-b.test", "Otra", "Pruebas", true, List.of());

        MvcTestResult newPerson = invite("nueva@clinica-a.test", "Ana", "Pruebas", "RECEPCION");
        MvcTestResult knownPerson = invite("de-otra-clinica@clinica-b.test", "Otra", "Pruebas", "RECEPCION");
        keycloak.addInvisibleUser("plataforma@clinica-a.test");
        MvcTestResult platformAdmin = invite("plataforma@clinica-a.test", "Plat", "Pruebas", "RECEPCION");

        for (MvcTestResult result : List.of(newPerson, knownPerson, platformAdmin)) {
            assertThat(result).hasStatus(HttpStatus.ACCEPTED);
            assertThat(result.getResponse().getContentAsByteArray()).isEmpty();
            assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).isNull();
            assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_TYPE)).isNull();
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"email\":\"ana@localhost\",\"firstName\":\"Ana\",\"lastName\":\"Pruebas\",\"role\":\"RECEPCION\"}",
                "{\"email\":\"ana@clinica\",\"firstName\":\"Ana\",\"lastName\":\"Pruebas\",\"role\":\"RECEPCION\"}",
                "{\"email\":\"ana@clinica.test\",\"firstName\":\"Ana\\u0000\",\"lastName\":\"Pruebas\",\"role\":\"RECEPCION\"}",
                "{\"email\":\"ana@clinica.test\",\"firstName\":\"Ana\",\"lastName\":\"Pru\\nebas\",\"role\":\"RECEPCION\"}",
                "{\"email\":\"ana@clinica.test\",\"firstName\":\" \",\"lastName\":\"Pruebas\",\"role\":\"RECEPCION\"}",
                "{\"email\":\"ana@clinica.test\",\"firstName\":\"\u2003\u2003\",\"lastName\":\"Pruebas\",\"role\":\"RECEPCION\"}",
                "{\"email\":\"ana@clinica.test\",\"firstName\":\"Ana\u202Eanilc\",\"lastName\":\"Pruebas\",\"role\":\"RECEPCION\"}",
                "{\"email\":\"ana@clinica.test\",\"firstName\":\"Ana\",\"lastName\":\"Pru\u200Bebas\",\"role\":\"RECEPCION\"}",
                "{\"email\":\"ana@clinica.test\",\"firstName\":\"123\",\"lastName\":\"Pruebas\",\"role\":\"RECEPCION\"}",
                "{\"email\":\"ana@clinica.test\",\"firstName\":\"Ana\",\"lastName\":\"Pruebas\"}",
                "{\"email\":\"ana@clinica.test\",\"firstName\":\"Ana\",\"lastName\":\"Pruebas\",\"role\":\"PLATFORM_ADMIN\"}"
            })
    void invalidInvitationsAreRefusedBeforeReachingKeycloak(String body) {
        assertThat(post(PATH + "/invitations", ADMIN, body)).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(keycloak.requests()).isEmpty();
    }

    /** The administrator never sets a password: one sent anyway is ignored and never reaches Keycloak. */
    @Test
    void aPasswordInTheInvitationIsIgnored() {
        String body = "{\"email\":\"ana@clinica-a.test\",\"firstName\":\"Ana\",\"lastName\":\"Pruebas\","
                + "\"role\":\"RECEPCION\",\"password\":\"Secreta-123\",\"credentials\":[{\"value\":\"Secreta-123\"}]}";

        assertThat(post(PATH + "/invitations", ADMIN, body)).hasStatus(HttpStatus.ACCEPTED);

        assertThat(keycloak.requests())
                .filteredOn(request ->
                        request.method().equals("POST") && request.path().equals("/users"))
                .singleElement()
                .satisfies(request -> assertThat(request.body())
                        .doesNotContain("Secreta-123")
                        .doesNotContain("credentials")
                        .doesNotContain("password"));
    }

    @Test
    void namesHaveAMaximumLength() {
        assertThat(invite("ana@clinica-a.test", "A".repeat(101), "Pruebas", "RECEPCION"))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(invite("ana@clinica-a.test", "A".repeat(100), "B".repeat(100), "RECEPCION"))
                .hasStatus(HttpStatus.ACCEPTED);
    }

    @Test
    void onlyTheClinicAdministratorUsesTheUsersApi() {
        assertForbidden(mvc.get()
                .uri(PATH)
                .header(
                        HttpHeaders.AUTHORIZATION,
                        TestTokens.token(RECEPTIONIST).bearer())
                .header(ApiSecurity.CLINIC_HEADER, CLINIC_A.toString())
                .exchange());
        assertForbidden(invite(RECEPTIONIST, "nueva@clinica-a.test"));
        assertThat(keycloak.requests()).isEmpty();
    }

    /**
     * In clinic B, which no other test invites to: while Redis is slow the quota falls back to a bucket of this
     * instance, which tests cannot empty.
     */
    @Test
    void theTwentyFirstInvitationOfTheDayIsTooManyRequests() throws SQLException {
        IdentityTestDatabase.insertMembership(CLINIC_B, ADMIN, ClinicRole.ADMIN_CLINICA, true);
        for (int i = 1; i <= 20; i++) {
            assertThat(inviteToB("persona" + i + "@clinica-b.test")).hasStatus(HttpStatus.ACCEPTED);
        }

        MvcTestResult refused = inviteToB("persona21@clinica-b.test");

        assertThat(refused).hasStatus(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(Long.parseLong(refused.getResponse().getHeader(HttpHeaders.RETRY_AFTER)))
                .isPositive();
        assertThat(refused).bodyJson().extractingPath("$.title").isEqualTo("Límite de invitaciones");
    }

    /** M02-T04 checks the membership on every request, so the deactivated user is refused right away. */
    @Test
    void aDeactivatedUserIsRefusedOnTheirNextRequest() {
        assertThat(context(dentist)).hasStatusOk();
        String membership = membershipOf(dentist);

        MvcTestResult deactivated = post(PATH + "/" + membership + "/deactivation", ADMIN, "{\"version\":0}");

        assertThat(deactivated)
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.active")
                .isEqualTo(false);
        assertForbidden(context(dentist));
        assertThat(keycloak.logouts()).containsExactly(dentist);
    }

    /**
     * The invited person sees the invitation in {@code GET /api/v1/me}, cannot work in the clinic until they accept
     * it, and can from the very next request after accepting.
     */
    @Test
    void anInvitationGivesAccessOnlyOnceAccepted() {
        assertThat(invite("invitada@clinica-a.test", "Ana", "Pruebas", "HIGIENISTA"))
                .hasStatus(HttpStatus.ACCEPTED);
        UUID invited =
                keycloak.userByEmail("invitada@clinica-a.test").orElseThrow().id();

        MvcTestResult me = me(invited);
        assertThat(me)
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.clinics")
                .asArray()
                .isEmpty();
        assertThat(me).bodyJson().extractingPath("$.invitations[0].role").isEqualTo("HIGIENISTA");
        assertForbidden(context(invited));
        String invitation = JsonPath.read(body(me), "$.invitations[0].id");

        assertThat(mvc.post()
                        .uri("/api/v1/me/invitations/" + invitation + "/acceptance")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                TestTokens.token(invited).bearer())
                        .exchange())
                .hasStatus(HttpStatus.NO_CONTENT);

        assertThat(context(invited)).hasStatusOk();
        assertThat(me(invited))
                .bodyJson()
                .extractingPath("$.invitations")
                .asArray()
                .isEmpty();
        assertThat(mvc.post()
                        .uri("/api/v1/me/invitations/" + invitation + "/acceptance")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                TestTokens.token(invited).bearer())
                        .exchange())
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    private MvcTestResult me(UUID user) {
        return mvc.get()
                .uri("/api/v1/me")
                .header(HttpHeaders.AUTHORIZATION, TestTokens.token(user).bearer())
                .exchange();
    }

    private static String body(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    void theListShowsRolesAndProfiles() {
        assertThat(mvc.get()
                        .uri(PATH)
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                TestTokens.token(ADMIN).bearer())
                        .header(ApiSecurity.CLINIC_HEADER, CLINIC_A.toString())
                        .exchange())
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.content[?(@.role == 'ODONTOLOGO')].profile.email")
                .asArray()
                .containsExactly("dentista@clinica-a.test");
    }

    private MvcTestResult context(UUID user) {
        return mvc.get()
                .uri(ActiveClinicTestEndpoints.CONTEXT)
                .header(HttpHeaders.AUTHORIZATION, TestTokens.token(user).bearer())
                .header(ApiSecurity.CLINIC_HEADER, CLINIC_A.toString())
                .exchange();
    }

    private String membershipOf(UUID user) {
        MvcTestResult list = mvc.get()
                .uri(PATH + "?size=50")
                .header(HttpHeaders.AUTHORIZATION, TestTokens.token(ADMIN).bearer())
                .header(ApiSecurity.CLINIC_HEADER, CLINIC_A.toString())
                .exchange();
        String body = new String(list.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        List<String> ids = JsonPath.read(body, "$.content[?(@.userId == '" + user + "')].id");
        return ids.getFirst();
    }

    private MvcTestResult invite(String email, String firstName, String lastName, String role) {
        return post(
                PATH + "/invitations",
                ADMIN,
                "{\"email\":\"%s\",\"firstName\":\"%s\",\"lastName\":\"%s\",\"role\":\"%s\"}"
                        .formatted(email, firstName, lastName, role));
    }

    private MvcTestResult inviteToB(String email) {
        return mvc.post()
                .uri(PATH + "/invitations")
                .header(HttpHeaders.AUTHORIZATION, TestTokens.token(ADMIN).bearer())
                .header(ApiSecurity.CLINIC_HEADER, CLINIC_B.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"firstName\":\"Ana\",\"lastName\":\"Pruebas\",\"role\":\"RECEPCION\"}"
                        .formatted(email))
                .exchange();
    }

    private MvcTestResult invite(UUID by, String email) {
        return post(
                PATH + "/invitations",
                by,
                "{\"email\":\"%s\",\"firstName\":\"Ana\",\"lastName\":\"Pruebas\",\"role\":\"RECEPCION\"}"
                        .formatted(email));
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
}
