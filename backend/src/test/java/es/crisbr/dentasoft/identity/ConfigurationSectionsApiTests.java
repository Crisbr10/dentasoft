package es.crisbr.dentasoft.identity;

import static es.crisbr.dentasoft.SecurityResponses.assertForbidden;
import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.TestData;
import es.crisbr.dentasoft.TestTokens;
import es.crisbr.dentasoft.shared.ApiSecurity;
import es.crisbr.dentasoft.tenancy.TenancyTestDatabase;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The configuration sections over HTTP (M02-T09), with real tokens and the active clinic checked on every request:
 * each clinic role gets its row of the matrix, {@code PLATFORM_ADMIN} gets the platform, and neither gets the other's.
 */
@IntegrationTest
@AutoConfigureMockMvc
class ConfigurationSectionsApiTests {

    private static final UUID CLINIC_A = TestData.CLINIC_A.id();
    private static final Map<ClinicRole, UUID> MEMBERS = Map.of(
            ClinicRole.ADMIN_CLINICA, UUID.fromString("5f0c2b1e-0000-4000-8000-0000000009a1"),
            ClinicRole.ODONTOLOGO, UUID.fromString("5f0c2b1e-0000-4000-8000-0000000009a2"),
            ClinicRole.HIGIENISTA, UUID.fromString("5f0c2b1e-0000-4000-8000-0000000009a3"),
            ClinicRole.RECEPCION, UUID.fromString("5f0c2b1e-0000-4000-8000-0000000009a4"),
            ClinicRole.FACTURACION, UUID.fromString("5f0c2b1e-0000-4000-8000-0000000009a5"));
    private static final UUID PLATFORM_ADMIN = UUID.fromString("5f0c2b1e-0000-4000-8000-0000000009a6");
    private static final String CLINIC_PATH = "/api/v1/clinic/configuration/sections";
    private static final String PLATFORM_PATH = "/api/v1/platform/configuration/sections";

    @Autowired
    private MockMvcTester mvc;

    @BeforeEach
    void seed() throws SQLException {
        TenancyTestDatabase.insertClinic(TestData.CLINIC_A, "ACTIVE");
        for (Map.Entry<ClinicRole, UUID> member : MEMBERS.entrySet()) {
            IdentityTestDatabase.insertUser(member.getValue());
            IdentityTestDatabase.insertMembership(CLINIC_A, member.getValue(), member.getKey(), true);
        }
        IdentityTestDatabase.insertUser(PLATFORM_ADMIN);
    }

    @AfterEach
    void deleteData() throws SQLException {
        TenancyTestDatabase.deleteClinicsAnd(List.of("clinic_membership"));
        IdentityTestDatabase.deleteUsers(Stream.concat(MEMBERS.values().stream(), Stream.of(PLATFORM_ADMIN))
                .toList());
    }

    static Stream<Arguments> matrix() {
        String viewer =
                "[{\"section\":\"CLINIC\",\"access\":\"VIEW\"},{\"section\":\"PRACTITIONERS\",\"access\":\"VIEW\"}]";
        return Stream.of(
                Arguments.of(
                        ClinicRole.ADMIN_CLINICA,
                        "[{\"section\":\"CLINIC\",\"access\":\"EDIT\"},"
                                + "{\"section\":\"USERS_AND_ROLES\",\"access\":\"EDIT\"},"
                                + "{\"section\":\"PRACTITIONERS\",\"access\":\"EDIT\"}]"),
                Arguments.of(ClinicRole.ODONTOLOGO, viewer),
                Arguments.of(ClinicRole.HIGIENISTA, viewer),
                Arguments.of(ClinicRole.RECEPCION, viewer),
                Arguments.of(ClinicRole.FACTURACION, viewer));
    }

    @ParameterizedTest
    @MethodSource("matrix")
    void eachClinicRoleGetsItsSectionsInMenuOrder(ClinicRole role, String expected) {
        MvcTestResult result = clinicSections(TestTokens.token(MEMBERS.get(role)));

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().isStrictlyEqualTo(expected);
    }

    @Test
    void thePlatformAdministratorGetsOnlyThePlatform() {
        MvcTestResult result = platformSections(TestTokens.platformAdmin(PLATFORM_ADMIN));

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().isStrictlyEqualTo("[{\"section\":\"PLATFORM\",\"access\":\"EDIT\"}]");
    }

    @Test
    void aClinicAdministratorIsRefusedThePlatformSections() {
        assertForbidden(platformSections(TestTokens.token(MEMBERS.get(ClinicRole.ADMIN_CLINICA))));
    }

    /** The platform role says nothing about a clinic: without a membership, no clinic sections. */
    @Test
    void thePlatformAdministratorIsRefusedTheClinicSections() {
        assertForbidden(clinicSections(TestTokens.platformAdmin(PLATFORM_ADMIN)));
    }

    /** A section missing from the role's list is refused by its own API, not only hidden from the menu. */
    @Test
    void aSectionTheRoleDoesNotSeeIsRefusedByItsApi() {
        MvcTestResult users = mvc.get()
                .uri("/api/v1/clinic/users")
                .header(
                        HttpHeaders.AUTHORIZATION,
                        TestTokens.token(MEMBERS.get(ClinicRole.RECEPCION)).bearer())
                .header(ApiSecurity.CLINIC_HEADER, CLINIC_A.toString())
                .exchange();

        assertForbidden(users);
    }

    private MvcTestResult clinicSections(TestTokens token) {
        return mvc.get()
                .uri(CLINIC_PATH)
                .header(HttpHeaders.AUTHORIZATION, token.bearer())
                .header(ApiSecurity.CLINIC_HEADER, CLINIC_A.toString())
                .exchange();
    }

    private MvcTestResult platformSections(TestTokens token) {
        return mvc.get()
                .uri(PLATFORM_PATH)
                .header(HttpHeaders.AUTHORIZATION, token.bearer())
                .exchange();
    }
}
