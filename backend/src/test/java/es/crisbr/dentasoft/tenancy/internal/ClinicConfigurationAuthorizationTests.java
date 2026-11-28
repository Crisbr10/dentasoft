package es.crisbr.dentasoft.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.TestData;
import es.crisbr.dentasoft.WithJwtUser;
import es.crisbr.dentasoft.tenancy.TenancyTestDatabase;
import es.crisbr.dentasoft.tenancy.TenantContext;
import es.crisbr.dentasoft.tenancy.internal.ClinicConfiguration.ClinicChanges;
import es.crisbr.dentasoft.tenancy.internal.ClinicConfiguration.SettingsChanges;
import es.crisbr.dentasoft.tenancy.internal.ClinicOpeningHours.Slot;
import es.crisbr.dentasoft.tenancy.internal.Locations.LocationData;
import es.crisbr.dentasoft.tenancy.persistence.ClinicSettings;
import es.crisbr.dentasoft.tenancy.persistence.ClinicSettingsRepository;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every role of the clinic reads its configuration and only {@code ADMIN_CLINICA} changes it (M01-T10): any other
 * caller is refused before anything is read or written, even with the clinic active. The role comes from a simulated
 * user; {@code ConfigurationRolesMatrixTests} checks each role against the matrix, and {@code ClinicApiSecurityTests}
 * with real tokens.
 */
@IntegrationTest
class ClinicConfigurationAuthorizationTests {

    private static final UUID CLINIC_A = TestData.CLINIC_A.id();

    private static final List<String> TABLES = List.of("clinic_hours_of_operation", "location");

    @Autowired
    private ClinicConfiguration configuration;

    @Autowired
    private ClinicOpeningHours openingHours;

    @Autowired
    private Locations locations;

    @Autowired
    private ClinicSettingsRepository settings;

    @Autowired
    private TransactionTemplate transaction;

    private String clinicBefore;

    @BeforeEach
    void createClinics() throws SQLException {
        TenancyTestDatabase.insertClinics();
        TenantContext.runAs(
                CLINIC_A,
                () -> transaction.executeWithoutResult(
                        status -> settings.save(new ClinicSettings(ClinicSettings.DEFAULT_APPOINTMENT_MINUTES))));
        clinicBefore = storedClinic();
    }

    @AfterEach
    void deleteClinics() throws SQLException {
        TenancyTestDatabase.deleteClinicsAnd(
                Stream.concat(Stream.of("clinic_settings"), TABLES.stream()).toList());
    }

    @Test
    void refusesEveryOperationWithoutAnAuthenticatedUser() throws SQLException {
        for (String operation : operationNames().toList()) {
            assertThatThrownBy(() -> TenantContext.runAs(CLINIC_A, operation(operation)))
                    .as(operation)
                    .isInstanceOf(AuthenticationCredentialsNotFoundException.class);
        }

        assertNothingChanged();
    }

    /** The platform administrator is no member of the clinic: not even reads. */
    @ParameterizedTest
    @MethodSource("operationNames")
    @WithJwtUser(roles = "PLATFORM_ADMIN")
    void refusesEveryOperationToThePlatformAdministrator(String operation) throws SQLException {
        assertThatThrownBy(() -> TenantContext.runAs(CLINIC_A, operation(operation)))
                .isInstanceOf(AccessDeniedException.class);

        assertNothingChanged();
    }

    @ParameterizedTest
    @MethodSource("writeNames")
    @WithJwtUser(roles = {"PLATFORM_ADMIN", "ODONTOLOGO", "HIGIENISTA", "RECEPCION", "FACTURACION"})
    void refusesEveryWriteToTheOtherRoles(String operation) throws SQLException {
        assertThatThrownBy(() -> TenantContext.runAs(CLINIC_A, operation(operation)))
                .isInstanceOf(AccessDeniedException.class);

        assertNothingChanged();
    }

    /** Past authorization: reading a room that does not exist is a not found, never a refusal. */
    @ParameterizedTest
    @MethodSource("readNames")
    @WithJwtUser(roles = "RECEPCION")
    void letsAnyOtherRoleRead(String operation) throws SQLException {
        Throwable thrown = catchThrowable(() -> TenantContext.runAs(CLINIC_A, operation(operation)));

        if (operation.equals("getLocation")) {
            assertThat(thrown).isInstanceOf(LocationNotFoundException.class);
        } else {
            assertThat(thrown).isNull();
        }
        assertNothingChanged();
    }

    /** {@code hasRole} expects the {@code ROLE_} prefix: a bare authority with the same name is not the role. */
    @ParameterizedTest
    @MethodSource("operationNames")
    @WithJwtUser(authorities = "ADMIN_CLINICA")
    void refusesAnAuthorityThatIsNotTheRole(String operation) throws SQLException {
        assertThatThrownBy(() -> TenantContext.runAs(CLINIC_A, operation(operation)))
                .isInstanceOf(AccessDeniedException.class);

        assertNothingChanged();
    }

    static Stream<String> operationNames() {
        return Stream.concat(readNames(), writeNames());
    }

    static Stream<String> readNames() {
        return Stream.of("clinic", "settings", "week", "listLocations", "getLocation");
    }

    static Stream<String> writeNames() {
        return Stream.of(
                "updateClinic",
                "updateSettings",
                "replaceWeek",
                "createLocation",
                "updateLocation",
                "deactivateLocation",
                "activateLocation");
    }

    private Runnable operation(String name) {
        UUID anyRoom = UUID.randomUUID();
        Slot slot = new Slot(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(14, 0));
        return switch (name) {
            case "clinic" -> configuration::clinic;
            case "updateClinic" ->
                () -> configuration.updateClinic(new ClinicChanges("Denegada", "Atlantic/Canary", 0));
            case "settings" -> configuration::settings;
            case "updateSettings" -> () -> configuration.updateSettings(new SettingsChanges(45, null, null, 0));
            case "week" -> openingHours::week;
            case "replaceWeek" -> () -> openingHours.replaceWeek(List.of(slot));
            case "listLocations" -> () -> locations.list(null, PageRequest.of(0, 20));
            case "getLocation" -> () -> locations.get(anyRoom);
            case "createLocation" -> () -> locations.create(new LocationData("Denegado", null));
            case "updateLocation" -> () -> locations.update(anyRoom, new LocationData("Denegado", null), 0);
            case "deactivateLocation" -> () -> locations.deactivate(anyRoom, 0);
            case "activateLocation" -> () -> locations.activate(anyRoom, 0);
            default -> throw new IllegalArgumentException(name);
        };
    }

    private void assertNothingChanged() throws SQLException {
        assertThat(storedClinic()).isEqualTo(clinicBefore);
        for (String table : TABLES) {
            assertThat(TenancyTestDatabase.countRows(table, CLINIC_A)).as(table).isZero();
        }
    }

    private static String storedClinic() throws SQLException {
        return TenancyTestDatabase.storedRow("clinic", CLINIC_A, CLINIC_A).orElseThrow();
    }
}
