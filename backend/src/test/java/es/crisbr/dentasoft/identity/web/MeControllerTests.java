package es.crisbr.dentasoft.identity.web;

import static es.crisbr.dentasoft.SecurityResponses.assertUnauthenticated;
import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.TestData;
import es.crisbr.dentasoft.TestTokens;
import es.crisbr.dentasoft.identity.ClinicRole;
import es.crisbr.dentasoft.identity.IdentityTestDatabase;
import es.crisbr.dentasoft.tenancy.TenancyTestDatabase;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * {@code GET /api/v1/me} (M02-T03): the signed-in user's own clinics and their role in each, the suspended ones
 * marked, and never anyone else's memberships.
 */
@IntegrationTest
@AutoConfigureMockMvc
class MeControllerTests {

    private static final UUID CLINIC_A = TestData.CLINIC_A.id();
    private static final UUID CLINIC_B = TestData.CLINIC_B.id();

    /** In clinic A as a dentist and in clinic B, which is suspended, at the front desk. */
    private static final UUID TWO_CLINICS = UUID.fromString("5f0c2b1e-0000-4000-8000-0000000004a1");

    /** The administrator of clinic B. */
    private static final UUID ADMIN_OF_B = UUID.fromString("5f0c2b1e-0000-4000-8000-0000000004b2");

    private final List<UUID> users = new ArrayList<>(List.of(TWO_CLINICS, ADMIN_OF_B));

    @Autowired
    private MockMvcTester mvc;

    @BeforeEach
    void seed() throws SQLException {
        TenancyTestDatabase.insertClinic(TestData.CLINIC_A, "ACTIVE");
        TenancyTestDatabase.insertClinic(TestData.CLINIC_B, "SUSPENDED");
        IdentityTestDatabase.insertUser(TWO_CLINICS);
        IdentityTestDatabase.insertUser(ADMIN_OF_B);
        IdentityTestDatabase.insertMembership(CLINIC_A, TWO_CLINICS, ClinicRole.ODONTOLOGO, true);
        IdentityTestDatabase.insertMembership(CLINIC_B, TWO_CLINICS, ClinicRole.RECEPCION, true);
        IdentityTestDatabase.insertMembership(CLINIC_B, ADMIN_OF_B, ClinicRole.ADMIN_CLINICA, true);
    }

    @AfterEach
    void deleteData() throws SQLException {
        TenancyTestDatabase.deleteClinicsAnd(List.of("clinic_membership"));
        IdentityTestDatabase.deleteUsers(users);
    }

    @Test
    void listsEachClinicOfTheUserWithItsRoleAndWhetherItIsSuspended() {
        assertThat(me(TWO_CLINICS)).hasStatusOk().bodyJson().isStrictlyEqualTo("""
                        {"clinics": [
                          {"clinicId": "%s", "name": "Clínica Prueba Norte", "role": "ODONTOLOGO", "suspended": false},
                          {"clinicId": "%s", "name": "Clínica Prueba Sur", "role": "RECEPCION", "suspended": true}
                        ], "invitations": []}""".formatted(CLINIC_A, CLINIC_B));
    }

    /** Clinic B has two members, but each one only ever sees their own membership. */
    @Test
    void aUserNeverSeesTheMembershipsOfAnotherUser() {
        assertThat(me(ADMIN_OF_B)).hasStatusOk().bodyJson().isStrictlyEqualTo("""
                        {"clinics": [
                          {"clinicId": "%s", "name": "Clínica Prueba Sur", "role": "ADMIN_CLINICA", "suspended": true}
                        ], "invitations": []}""".formatted(CLINIC_B));
        assertThat(me(TWO_CLINICS))
                .bodyJson()
                .extractingPath("$.clinics[*].role")
                .asArray()
                .containsExactly("ODONTOLOGO", "RECEPCION");
    }

    @Test
    void aDeactivatedMembershipNoLongerListsItsClinic() throws SQLException {
        IdentityTestDatabase.setMembershipActive(CLINIC_B, TWO_CLINICS, false);

        assertThat(me(TWO_CLINICS))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.clinics[*].clinicId")
                .asArray()
                .containsExactly(CLINIC_A.toString());
    }

    /** Without an active membership the user cannot work in any clinic: there is none to choose (M02-T04). */
    @Test
    void aUserWithoutActiveMembershipsHasNoClinics() throws SQLException {
        UUID formerMember = newUser();
        IdentityTestDatabase.insertUser(formerMember);
        IdentityTestDatabase.insertMembership(CLINIC_A, formerMember, ClinicRole.HIGIENISTA, false);

        assertThat(me(formerMember))
                .hasStatusOk()
                .bodyJson()
                .isStrictlyEqualTo("{\"clinics\": [], \"invitations\": []}");
    }

    @Test
    void aDisabledAccountHasNoClinics() throws SQLException {
        UUID disabled = newUser();
        IdentityTestDatabase.insertUser(disabled, "DISABLED");
        IdentityTestDatabase.insertMembership(CLINIC_A, disabled, ClinicRole.FACTURACION, true);

        assertThat(me(disabled)).hasStatusOk().bodyJson().isStrictlyEqualTo("{\"clinics\": [], \"invitations\": []}");
    }

    @Test
    void theFirstRequestOfANewUserCreatesTheirAccount() throws SQLException {
        UUID newcomer = newUser();
        assertThat(IdentityTestDatabase.userStatus(newcomer)).isEmpty();

        assertThat(me(newcomer)).hasStatusOk().bodyJson().isStrictlyEqualTo("{\"clinics\": [], \"invitations\": []}");

        assertThat(IdentityTestDatabase.userStatus(newcomer)).contains("ACTIVE");
    }

    @Test
    void rejectsRequestsWithoutAToken() {
        assertUnauthenticated(mvc.get().uri(MeController.PATH).exchange());
    }

    private MvcTestResult me(UUID user) {
        return mvc.get()
                .uri(MeController.PATH)
                .header(HttpHeaders.AUTHORIZATION, TestTokens.token(user).bearer())
                .exchange();
    }

    /** A user no other test knows, so the registration filter has never seen them. */
    private UUID newUser() {
        UUID user = UUID.randomUUID();
        users.add(user);
        return user;
    }
}
