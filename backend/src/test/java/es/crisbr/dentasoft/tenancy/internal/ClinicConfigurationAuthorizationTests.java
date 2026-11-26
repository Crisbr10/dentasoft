package es.crisbr.dentasoft.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.TestData;
import es.crisbr.dentasoft.WithJwtUser;
import es.crisbr.dentasoft.tenancy.TenancyTestDatabase;
import es.crisbr.dentasoft.tenancy.TenantContext;
import es.crisbr.dentasoft.tenancy.internal.ClinicConfiguration.ClinicChanges;
import es.crisbr.dentasoft.tenancy.internal.ClinicConfiguration.SettingsChanges;
import es.crisbr.dentasoft.tenancy.internal.ClinicOpeningHours.Slot;
import es.crisbr.dentasoft.tenancy.internal.Locations.LocationData;
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

/**
 * Only {@code ADMIN_CLINICA} configures its clinic: every other caller is refused before anything is read or written,
 * even with the clinic active. Until M02 the role comes from a simulated user.
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

    private String clinicBefore;

    @BeforeEach
    void createClinics() throws SQLException {
        TenancyTestDatabase.insertClinics();
        clinicBefore = storedClinic();
    }

    @AfterEach
    void deleteClinics() throws SQLException {
        TenancyTestDatabase.deleteClinicsAnd(TABLES);
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

    @ParameterizedTest
    @MethodSource("operationNames")
    @WithJwtUser(roles = {"PLATFORM_ADMIN", "ODONTOLOGO", "HIGIENISTA", "RECEPCION", "FACTURACION"})
    void refusesEveryOtherRole(String operation) throws SQLException {
        assertThatThrownBy(() -> TenantContext.runAs(CLINIC_A, operation(operation)))
                .isInstanceOf(AccessDeniedException.class);

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
        return Stream.of(
                "clinic",
                "updateClinic",
                "settings",
                "updateSettings",
                "week",
                "replaceWeek",
                "listLocations",
                "getLocation",
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
