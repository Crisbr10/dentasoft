package es.crisbr.dentasoft.identity;

import static es.crisbr.dentasoft.SecurityResponses.assertForbidden;
import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.TestData;
import es.crisbr.dentasoft.TestTokens;
import es.crisbr.dentasoft.tenancy.TenancyTestDatabase;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The active clinic of a request under {@code /api/v1/clinic/}: the {@code X-Clinic-Id} header, checked against the
 * database on every request, with real tokens. Clinic A is active; clinic B is suspended.
 */
@IntegrationTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ActiveClinicTests {

    private static final UUID CLINIC_A = TestData.CLINIC_A.id();
    private static final UUID CLINIC_B = TestData.CLINIC_B.id();

    /** Dentist in A, and in B, which is suspended. */
    private static final UUID DENTIST = UUID.fromString("5f0c2b1e-0000-4000-8000-0000000004a1");

    private static final UUID RECEPTIONIST = UUID.fromString("5f0c2b1e-0000-4000-8000-0000000004a2");
    /** Was a dentist in A: the membership is no longer active. */
    private static final UUID FORMER_DENTIST = UUID.fromString("5f0c2b1e-0000-4000-8000-0000000004a3");
    /** Active membership in A, but the account is disabled. */
    private static final UUID DISABLED_USER = UUID.fromString("5f0c2b1e-0000-4000-8000-0000000004a4");
    /** Member of B only. */
    private static final UUID OUTSIDER = UUID.fromString("5f0c2b1e-0000-4000-8000-0000000004a5");
    /** Platform administrator, with no membership. */
    private static final UUID PLATFORM_ADMIN = UUID.fromString("5f0c2b1e-0000-4000-8000-0000000004a6");

    private static final String HEADER = "X-Clinic-Id";

    @Autowired
    private MockMvcTester mvc;

    @BeforeEach
    void seed() throws SQLException {
        TenancyTestDatabase.insertClinic(TestData.CLINIC_A, "ACTIVE");
        TenancyTestDatabase.insertClinic(TestData.CLINIC_B, "SUSPENDED");
        for (UUID user : List.of(DENTIST, RECEPTIONIST, FORMER_DENTIST, OUTSIDER)) {
            IdentityTestDatabase.insertUser(user);
        }
        IdentityTestDatabase.insertUser(DISABLED_USER, "DISABLED");
        IdentityTestDatabase.insertMembership(CLINIC_A, DENTIST, ClinicRole.ODONTOLOGO, true);
        IdentityTestDatabase.insertMembership(CLINIC_B, DENTIST, ClinicRole.ODONTOLOGO, true);
        IdentityTestDatabase.insertMembership(CLINIC_A, RECEPTIONIST, ClinicRole.RECEPCION, true);
        IdentityTestDatabase.insertMembership(CLINIC_A, FORMER_DENTIST, ClinicRole.ODONTOLOGO, false);
        IdentityTestDatabase.insertMembership(CLINIC_A, DISABLED_USER, ClinicRole.ODONTOLOGO, true);
        IdentityTestDatabase.insertMembership(CLINIC_B, OUTSIDER, ClinicRole.RECEPCION, true);
        ActiveClinicTestEndpoints.SWITCHED.set(false);
    }

    @AfterEach
    void deleteData() throws SQLException {
        TenancyTestDatabase.deleteClinicsAnd(List.of("clinic_membership"));
        IdentityTestDatabase.deleteUsers(
                List.of(DENTIST, RECEPTIONIST, FORMER_DENTIST, DISABLED_USER, OUTSIDER, PLATFORM_ADMIN));
    }

    /**
     * The clinic is active for the whole request, the role of the membership is added to the token's own authorities
     * (Spring Security's {@code FACTOR_BEARER} among them), and
     * the transaction of the check is closed before the request goes on.
     */
    @Test
    void aMemberActsAsTheClinicWithTheRoleOfTheMembership() {
        assertThat(get(ActiveClinicTestEndpoints.CONTEXT, TestTokens.token(DENTIST), CLINIC_A.toString()))
                .hasStatusOk()
                .bodyJson()
                .isStrictlyEqualTo(
                        "{\"clinic\":\"%s\",\"authorities\":[\"FACTOR_BEARER\",\"ROLE_ODONTOLOGO\"],\"transaction\":false}"
                                .formatted(CLINIC_A));
    }

    /** The header is a UUID in any letter case, as {@code GET /api/v1/me} may list it. */
    @Test
    void theClinicIdMayBeInUpperCase() {
        assertThat(get(
                        ActiveClinicTestEndpoints.CONTEXT,
                        TestTokens.token(DENTIST),
                        CLINIC_A.toString().toUpperCase()))
                .hasStatusOk();
    }

    @Test
    void preAuthorizeChecksTheRoleInTheActiveClinic() {
        assertThat(get(ActiveClinicTestEndpoints.DENTISTS_ONLY, TestTokens.token(DENTIST), CLINIC_A.toString()))
                .hasStatusOk()
                .hasBodyTextEqualTo("hola");
        assertForbidden(
                get(ActiveClinicTestEndpoints.DENTISTS_ONLY, TestTokens.token(RECEPTIONIST), CLINIC_A.toString()));
    }

    /** Clinic roles come from the database only: the same names as realm roles in the token count for nothing. */
    @Test
    void realmRolesNamedLikeClinicRolesGrantNothing() {
        TestTokens token = TestTokens.token(RECEPTIONIST).realmRoles("ODONTOLOGO", "ADMIN_CLINICA");

        assertThat(get(ActiveClinicTestEndpoints.CONTEXT, token, CLINIC_A.toString()))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.authorities")
                .asArray()
                .containsExactly("FACTOR_BEARER", "ROLE_RECEPCION");
        assertForbidden(get(ActiveClinicTestEndpoints.DENTISTS_ONLY, token, CLINIC_A.toString()));
        assertForbidden(get(
                ActiveClinicTestEndpoints.CONTEXT,
                TestTokens.token(OUTSIDER).realmRoles("ADMIN_CLINICA"),
                CLINIC_A.toString()));
    }

    /**
     * A clinic that does not exist, someone else's, a suspended one, a membership that is no longer active and a
     * disabled account all get the very same 403, so nobody can find out which clinics exist.
     */
    @Test
    void everyRefusedClinicGetsTheSameForbidden() {
        List<MvcTestResult> refusals = List.of(
                get(
                        ActiveClinicTestEndpoints.CONTEXT,
                        TestTokens.token(DENTIST),
                        UUID.randomUUID().toString()),
                get(ActiveClinicTestEndpoints.CONTEXT, TestTokens.token(OUTSIDER), CLINIC_A.toString()),
                get(ActiveClinicTestEndpoints.CONTEXT, TestTokens.token(DENTIST), CLINIC_B.toString()),
                get(ActiveClinicTestEndpoints.CONTEXT, TestTokens.token(FORMER_DENTIST), CLINIC_A.toString()),
                get(ActiveClinicTestEndpoints.CONTEXT, TestTokens.token(DISABLED_USER), CLINIC_A.toString()));

        refusals.forEach(result -> assertForbidden(result));
        assertThat(refusals).extracting(ActiveClinicTests::body).containsOnly(body(refusals.getFirst()));
    }

    /** The platform role says nothing about a clinic: without a membership, the same 403 as anybody else. */
    @Test
    void thePlatformAdministratorWithoutAMembershipIsRefusedLikeAnybodyElse() {
        MvcTestResult admin =
                get(ActiveClinicTestEndpoints.CONTEXT, TestTokens.platformAdmin(PLATFORM_ADMIN), CLINIC_A.toString());
        MvcTestResult outsider =
                get(ActiveClinicTestEndpoints.CONTEXT, TestTokens.token(OUTSIDER), CLINIC_A.toString());

        assertForbidden(admin);
        assertThat(body(admin)).isEqualTo(body(outsider));
    }

    /** Nothing is cached: a membership that ends is refused from the very next request. */
    @Test
    void anEndedMembershipIsRefusedOnTheNextRequest() throws SQLException {
        assertThat(get(ActiveClinicTestEndpoints.CONTEXT, TestTokens.token(DENTIST), CLINIC_A.toString()))
                .hasStatusOk();

        IdentityTestDatabase.setMembershipActive(CLINIC_A, DENTIST, false);

        assertForbidden(get(ActiveClinicTestEndpoints.CONTEXT, TestTokens.token(DENTIST), CLINIC_A.toString()));
    }

    /** Without the header, with a value that is not a UUID, or with it twice: the same 400. */
    @Test
    void aMissingOrMalformedClinicHeaderIsABadRequest() {
        String token = TestTokens.token(DENTIST).bearer();
        List<MvcTestResult> refusals = List.of(
                mvc.get()
                        .uri(ActiveClinicTestEndpoints.CONTEXT)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .exchange(),
                get(ActiveClinicTestEndpoints.CONTEXT, TestTokens.token(DENTIST), ""),
                get(ActiveClinicTestEndpoints.CONTEXT, TestTokens.token(DENTIST), "clinica-a"),
                get(ActiveClinicTestEndpoints.CONTEXT, TestTokens.token(DENTIST), "1-1-1-1-1"),
                get(ActiveClinicTestEndpoints.CONTEXT, TestTokens.token(DENTIST), " " + CLINIC_A),
                mvc.get()
                        .uri(ActiveClinicTestEndpoints.CONTEXT)
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .header(HEADER, CLINIC_A.toString(), CLINIC_A.toString())
                        .exchange());

        for (MvcTestResult result : refusals) {
            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST).hasContentType(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(result).bodyJson().extractingPath("$.title").isEqualTo("Clínica no indicada");
            assertThat(result)
                    .bodyJson()
                    .extractingPath("$.detail")
                    .isEqualTo("Indica la clínica activa con su identificador en la cabecera X-Clinic-Id.");
        }
        assertThat(refusals).extracting(ActiveClinicTests::body).containsOnly(body(refusals.getFirst()));
    }

    /** The clinic of a request cannot change halfway through: switching to another one fails and runs nothing. */
    @Test
    void aRequestCannotSwitchToAnotherClinic() {
        assertThat(get(
                        ActiveClinicTestEndpoints.SWITCH + "?to=" + CLINIC_B,
                        TestTokens.token(DENTIST),
                        CLINIC_A.toString()))
                .hasStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .bodyJson()
                .extractingPath("$.detail")
                .asString()
                .doesNotContain("clinic");
        assertThat(ActiveClinicTestEndpoints.SWITCHED).isFalse();
    }

    /** Someone trying clinics shows up in the log, with nothing but the user and the clinic asked for. */
    @Test
    void aRefusedClinicIsLoggedWithIdsOnly(CapturedOutput output) {
        assertForbidden(get(ActiveClinicTestEndpoints.CONTEXT, TestTokens.token(OUTSIDER), CLINIC_A.toString()));

        assertThat(output.getOut().lines().filter(line -> line.contains("Active clinic refused")))
                .singleElement()
                .asString()
                .contains("user " + OUTSIDER, "clinic " + CLINIC_A)
                .doesNotContain(TestData.CLINIC_A.name());
    }

    /** Only the clinic API needs the header: the rest of the application is not affected. */
    @Test
    void otherPathsNeedNoClinic() {
        assertThat(mvc.get()
                        .uri("/api/v1/me")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                TestTokens.token(DENTIST).bearer()))
                .hasStatusOk();
        assertThat(mvc.get()
                        .uri("/api/v1/clinics")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                TestTokens.token(DENTIST).bearer()))
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    private MvcTestResult get(String path, TestTokens token, String clinic) {
        MockMvcRequestBuilder request = mvc.get().uri(path).header(HttpHeaders.AUTHORIZATION, token.bearer());
        return request.header(HEADER, clinic).exchange();
    }

    private static String body(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }
}
