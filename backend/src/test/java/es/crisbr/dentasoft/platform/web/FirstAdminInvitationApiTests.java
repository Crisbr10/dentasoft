package es.crisbr.dentasoft.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.jayway.jsonpath.JsonPath;
import es.crisbr.dentasoft.FakeKeycloak;
import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.TestData;
import es.crisbr.dentasoft.TestTokens;
import es.crisbr.dentasoft.audit.AuditLogDatabase;
import es.crisbr.dentasoft.audit.AuditLogDatabase.StoredEntry;
import es.crisbr.dentasoft.identity.ClinicRole;
import es.crisbr.dentasoft.identity.IdentityTestDatabase;
import es.crisbr.dentasoft.shared.ApiSecurity;
import es.crisbr.dentasoft.tenancy.TenancyTestDatabase;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The platform invites the first administrator of a clinic (M02-T11), through the whole HTTP stack: security chain,
 * validation, the platform's database role and Keycloak (the fake one). What PostgreSQL refuses on its own, concurrent
 * requests included, is in {@code PlatformFirstAdminDatabaseTests}.
 */
@IntegrationTest
@AutoConfigureMockMvc
class FirstAdminInvitationApiTests {

    private static final UUID CLINIC_A = TestData.CLINIC_A.id();
    private static final UUID PLATFORM_ADMIN = UUID.fromString("7a1d0c00-0000-4000-8000-0000000001f1");
    private static final UUID CLINIC_ADMIN = UUID.fromString("7a1d0c00-0000-4000-8000-0000000001a1");
    private static final String CLINICS = "/api/v1/platform/clinics";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private FakeKeycloak keycloak;

    @Autowired
    private StringRedisTemplate redis;

    private final List<UUID> createdClinics = new ArrayList<>();

    @BeforeEach
    void seed() throws SQLException {
        keycloak.reset();
        deleteQuotas();
        TenancyTestDatabase.insertClinic(TestData.CLINIC_A, "ACTIVE");
    }

    @AfterEach
    void deleteData() throws SQLException {
        TenancyTestDatabase.deleteClinicsAnd(List.of("clinic_membership"));
        for (UUID clinic : createdClinics) {
            TenancyTestDatabase.deleteClinicAnd(clinic, List.of("clinic_membership", "clinic_settings"));
        }
        List<UUID> users = new ArrayList<>(List.of(CLINIC_ADMIN));
        keycloak.users().forEach(user -> users.add(user.id()));
        IdentityTestDatabase.deleteUsers(users);
        deleteQuotas();
    }

    /**
     * The whole flow of the issue: create a clinic → invite its administrator → they accept → they invite a dentist.
     */
    @Test
    void aNewClinicGetsItsFirstAdministratorWhoThenInvitesTheirTeam() {
        MvcTestResult created = platform(post(CLINICS)
                .content("{\"name\":\"Clínica Nueva\",\"legalName\":\"Clínica Nueva S.L.\",\"taxId\":\"B12345674\"}"));
        assertThat(created).hasStatus(HttpStatus.CREATED);
        UUID clinic = UUID.fromString(JsonPath.read(body(created), "$.id"));
        createdClinics.add(clinic);

        assertThat(inviteAdmin(clinic, "directora@clinica-nueva.test")).hasStatus(HttpStatus.ACCEPTED);
        UUID admin = keycloak.userByEmail("directora@clinica-nueva.test")
                .orElseThrow()
                .id();
        assertThat(keycloak.actionsEmails())
                .extracting(FakeKeycloak.ActionsEmail::userId)
                .containsExactly(admin);

        MvcTestResult me = user(mvc.get().uri("/api/v1/me"), admin);
        assertThat(me).bodyJson().extractingPath("$.invitations[0].role").isEqualTo("ADMIN_CLINICA");
        String invitation = JsonPath.read(body(me), "$.invitations[0].id");
        assertThat(user(mvc.post().uri("/api/v1/me/invitations/" + invitation + "/acceptance"), admin))
                .hasStatus(HttpStatus.NO_CONTENT);

        MvcTestResult dentist = user(
                mvc.post()
                        .uri("/api/v1/clinic/users/invitations")
                        .header(ApiSecurity.CLINIC_HEADER, clinic.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"dentista@clinica-nueva.test\",\"firstName\":\"Dentista\","
                                + "\"lastName\":\"Pruebas\",\"role\":\"ODONTOLOGO\"}"),
                admin);
        assertThat(dentist).hasStatus(HttpStatus.ACCEPTED);
        UUID dentistId = keycloak.userByEmail("dentista@clinica-nueva.test")
                .orElseThrow()
                .id();
        assertThat(membership(clinic, dentistId)).contains("ODONTOLOGO/true");

        // Once the clinic has its administrator, the platform is out of it.
        assertThat(inviteAdmin(clinic, "otra@clinica-nueva.test")).hasStatus(HttpStatus.CONFLICT);
        assertThat(platform(post(CLINICS + "/" + clinic + "/admin-invitation/cancellation")))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(membership(clinic, admin)).contains("ADMIN_CLINICA/true");
    }

    /** The account is created in the platform administrator's name: the platform unit sets {@code app.user_id}. */
    @Test
    void invitesAPendingAdministratorInThePlatformAdministratorsName() throws SQLException {
        assertThat(inviteAdmin(CLINIC_A, "directora@clinica-a.test"))
                .hasStatus(HttpStatus.ACCEPTED)
                .body()
                .isEmpty();

        UUID admin =
                keycloak.userByEmail("directora@clinica-a.test").orElseThrow().id();
        assertThat(IdentityTestDatabase.membership(CLINIC_A, admin)).contains("ADMIN_CLINICA/true");
        assertThat(IdentityTestDatabase.userCreatedBy(admin)).contains(PLATFORM_ADMIN);
        assertThat(keycloak.user(admin).orElseThrow().groups()).isNotEmpty();
    }

    @Test
    void aPendingInvitationIsResentOrCancelledNeverDoubled() throws SQLException {
        inviteAdmin(CLINIC_A, "directora@clinica-a.test");
        UUID first =
                keycloak.userByEmail("directora@clinica-a.test").orElseThrow().id();

        MvcTestResult second = inviteAdmin(CLINIC_A, "otra@clinica-a.test");
        assertThat(second).hasStatus(HttpStatus.CONFLICT);
        assertThat(second).bodyJson().extractingPath("$.title").isEqualTo("La clínica ya tiene administrador");
        assertThat(keycloak.userByEmail("otra@clinica-a.test")).isEmpty();

        assertThat(platform(post(CLINICS + "/" + CLINIC_A + "/admin-invitation/resend")))
                .hasStatus(HttpStatus.ACCEPTED);
        assertThat(keycloak.actionsEmails())
                .extracting(FakeKeycloak.ActionsEmail::userId)
                .containsExactly(first, first);

        assertThat(platform(post(CLINICS + "/" + CLINIC_A + "/admin-invitation/cancellation")))
                .hasStatus(HttpStatus.NO_CONTENT);
        assertThat(IdentityTestDatabase.membership(CLINIC_A, first)).contains("ADMIN_CLINICA/false");
        assertThat(platform(post(CLINICS + "/" + CLINIC_A + "/admin-invitation/cancellation")))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(platform(post(CLINICS + "/" + CLINIC_A + "/admin-invitation/resend")))
                .hasStatus(HttpStatus.NOT_FOUND);

        assertThat(inviteAdmin(CLINIC_A, "otra@clinica-a.test")).hasStatus(HttpStatus.ACCEPTED);
        UUID other = keycloak.userByEmail("otra@clinica-a.test").orElseThrow().id();
        assertThat(IdentityTestDatabase.membership(CLINIC_A, other)).contains("ADMIN_CLINICA/true");
    }

    /** The same person invited again after a cancellation gets their membership back, pending, not a second one. */
    @Test
    void invitingTheSamePersonAgainAfterCancellingReactivatesTheirMembership() throws SQLException {
        inviteAdmin(CLINIC_A, "directora@clinica-a.test");
        platform(post(CLINICS + "/" + CLINIC_A + "/admin-invitation/cancellation"));

        assertThat(inviteAdmin(CLINIC_A, "directora@clinica-a.test")).hasStatus(HttpStatus.ACCEPTED);

        UUID admin =
                keycloak.userByEmail("directora@clinica-a.test").orElseThrow().id();
        assertThat(IdentityTestDatabase.membership(CLINIC_A, admin)).contains("ADMIN_CLINICA/true");
    }

    @Test
    void refusesAClinicThatAlreadyHasAnAdministrator() throws SQLException {
        IdentityTestDatabase.insertUser(CLINIC_ADMIN);
        IdentityTestDatabase.insertMembership(CLINIC_A, CLINIC_ADMIN, ClinicRole.ADMIN_CLINICA, true);

        assertThat(inviteAdmin(CLINIC_A, "directora@clinica-a.test")).hasStatus(HttpStatus.CONFLICT);
        assertThat(keycloak.requests()).isEmpty();
        assertThat(platform(post(CLINICS + "/" + CLINIC_A + "/admin-invitation/cancellation")))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(IdentityTestDatabase.membership(CLINIC_A, CLINIC_ADMIN)).contains("ADMIN_CLINICA/true");
    }

    @Test
    void anUnknownClinicIsNotFound() {
        UUID unknown = UUID.fromString("0190a6e8-0000-7000-8000-0000000000aa");

        assertThat(inviteAdmin(unknown, "directora@clinica-a.test")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(platform(post(CLINICS + "/" + unknown + "/admin-invitation/resend")))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(platform(post(CLINICS + "/" + unknown + "/admin-invitation/cancellation")))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(keycloak.requests()).isEmpty();
    }

    /** Another platform administrator's email: nothing is done, with the same answer. */
    @Test
    void anEmailOutsideTheClinicUsersGetsTheSameAnswerAndNothingIsCreated() throws SQLException {
        keycloak.addInvisibleUser("plataforma@clinica-a.test");

        assertThat(inviteAdmin(CLINIC_A, "plataforma@clinica-a.test"))
                .hasStatus(HttpStatus.ACCEPTED)
                .body()
                .isEmpty();
        assertThat(platform(post(CLINICS + "/" + CLINIC_A + "/admin-invitation/resend")))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void invalidInvitationsAreRefusedBeforeReachingKeycloak() {
        for (String body : List.of(
                "{\"email\":\"ana@clinica\",\"firstName\":\"Ana\",\"lastName\":\"Pruebas\"}",
                "{\"email\":\"ana@clinica-a.test\",\"firstName\":\" \",\"lastName\":\"Pruebas\"}",
                "{\"email\":\"ana@clinica-a.test\",\"firstName\":\"Ana‮anilc\",\"lastName\":\"Pruebas\"}",
                "{\"email\":\"ana@clinica-a.test\",\"firstName\":\"Ana\"}")) {
            assertThat(platform(
                            post(CLINICS + "/" + CLINIC_A + "/admin-invitation").content(body)))
                    .hasStatus(HttpStatus.BAD_REQUEST);
        }
        assertThat(keycloak.requests()).isEmpty();
    }

    /** A clinic's administrator cannot use the platform's way in, even for their own clinic. */
    @Test
    void aClinicAdministratorCannotInviteThroughThePlatform() throws SQLException {
        IdentityTestDatabase.insertUser(CLINIC_ADMIN);
        IdentityTestDatabase.insertMembership(CLINIC_A, CLINIC_ADMIN, ClinicRole.ADMIN_CLINICA, true);

        assertThat(user(
                        post(CLINICS + "/" + CLINIC_A + "/admin-invitation")
                                .content(invitationBody("directora@clinica-a.test")),
                        CLINIC_ADMIN))
                .hasStatus(HttpStatus.FORBIDDEN);
        assertThat(keycloak.requests()).isEmpty();
    }

    @Test
    void everyInvitationResendAndCancellationIsAudited() throws SQLException {
        inviteAdmin(CLINIC_A, "directora@clinica-a.test");
        platform(post(CLINICS + "/" + CLINIC_A + "/admin-invitation/resend"));
        platform(post(CLINICS + "/" + CLINIC_A + "/admin-invitation/cancellation"));
        inviteAdmin(CLINIC_A, "directora@clinica-a.test");

        UUID admin =
                keycloak.userByEmail("directora@clinica-a.test").orElseThrow().id();
        UUID membership = membershipId(CLINIC_A, admin);
        List<StoredEntry> entries = AuditLogDatabase.entries(CLINIC_A, membership);
        assertThat(entries)
                .extracting(StoredEntry::action, StoredEntry::details)
                .containsExactly(
                        tuple("CREATE", "{\"subtype\": \"FIRST_ADMIN_INVITED\"}"),
                        tuple("UPDATE", "{\"subtype\": \"FIRST_ADMIN_INVITATION_RESENT\"}"),
                        tuple("UPDATE", "{\"subtype\": \"FIRST_ADMIN_INVITATION_CANCELLED\"}"),
                        tuple("UPDATE", "{\"subtype\": \"FIRST_ADMIN_INVITED\"}"));
        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry.actorType()).isEqualTo("PLATFORM");
            assertThat(entry.actorId()).isEqualTo(PLATFORM_ADMIN);
            assertThat(entry.entityType()).isEqualTo("CLINIC_MEMBERSHIP");
            assertThat(entry.tenantId()).isEqualTo(CLINIC_A);
            assertThat(entry.outcome()).isEqualTo("SUCCESS");
        });
    }

    /** A refused invitation records nothing. */
    @Test
    void aRefusedInvitationIsNotAudited() throws SQLException {
        inviteAdmin(CLINIC_A, "directora@clinica-a.test");
        inviteAdmin(CLINIC_A, "otra@clinica-a.test");

        UUID admin =
                keycloak.userByEmail("directora@clinica-a.test").orElseThrow().id();
        assertThat(AuditLogDatabase.actions(CLINIC_A, membershipId(CLINIC_A, admin)))
                .containsExactly("CREATE");
    }

    private MvcTestResult inviteAdmin(UUID clinic, String email) {
        return platform(post(CLINICS + "/" + clinic + "/admin-invitation").content(invitationBody(email)));
    }

    private static String invitationBody(String email) {
        return "{\"email\":\"" + email + "\",\"firstName\":\"Directora\",\"lastName\":\"Pruebas\"}";
    }

    private MockMvcTester.MockMvcRequestBuilder post(String path) {
        return mvc.post().uri(path).contentType(MediaType.APPLICATION_JSON);
    }

    private MvcTestResult platform(MockMvcTester.MockMvcRequestBuilder request) {
        return request.header(
                        HttpHeaders.AUTHORIZATION,
                        TestTokens.platformAdmin(PLATFORM_ADMIN).bearer())
                .exchange();
    }

    private static MvcTestResult user(MockMvcTester.MockMvcRequestBuilder request, UUID user) {
        return request.header(HttpHeaders.AUTHORIZATION, TestTokens.token(user).bearer())
                .exchange();
    }

    private static Optional<String> membership(UUID clinic, UUID user) {
        try {
            return IdentityTestDatabase.membership(clinic, user);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static UUID membershipId(UUID clinic, UUID user) throws SQLException {
        return IdentityTestDatabase.membershipId(clinic, user).orElseThrow();
    }

    private static String body(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private void deleteQuotas() {
        Set<String> keys = redis.keys("dentasoft:quota:*");
        if (!keys.isEmpty()) {
            redis.delete(keys);
        }
    }
}
