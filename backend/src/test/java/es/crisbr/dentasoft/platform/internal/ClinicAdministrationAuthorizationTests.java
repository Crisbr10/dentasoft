package es.crisbr.dentasoft.platform.internal;

import static es.crisbr.dentasoft.TestPostgresConfiguration.connectAsPlatform;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.platform.internal.ClinicAdministration.NewClinic;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/** Only {@code PLATFORM_ADMIN} administers clinics: every other caller is refused before anything is written. */
@IntegrationTest
@RecordApplicationEvents
class ClinicAdministrationAuthorizationTests {

    @Autowired
    private ClinicAdministration administration;

    @Autowired
    private ApplicationEvents events;

    private long clinicsBefore;

    @BeforeEach
    void countClinics() throws SQLException {
        clinicsBefore = countClinicsAsPlatform();
    }

    @Test
    void refusesEveryOperationWithoutAnAuthenticatedUser() throws SQLException {
        operations()
                .forEach(operation -> assertThatThrownBy(operation::run)
                        .isInstanceOf(AuthenticationCredentialsNotFoundException.class));

        assertNothingChanged();
    }

    @ParameterizedTest
    @MethodSource("es.crisbr.dentasoft.platform.internal.ClinicAdministrationAuthorizationTests#operationNames")
    @WithMockUser(roles = {"ADMIN_CLINICA", "ODONTOLOGO", "RECEPCION"})
    void refusesClinicRoles(String operation) throws SQLException {
        assertThatThrownBy(() -> operation(operation).run()).isInstanceOf(AccessDeniedException.class);

        assertNothingChanged();
    }

    /** {@code hasRole} expects the {@code ROLE_} prefix: a bare authority with the same name is not the role. */
    @ParameterizedTest
    @MethodSource("es.crisbr.dentasoft.platform.internal.ClinicAdministrationAuthorizationTests#operationNames")
    @WithMockUser(authorities = "PLATFORM_ADMIN")
    void refusesAnAuthorityThatIsNotTheRole(String operation) throws SQLException {
        assertThatThrownBy(() -> operation(operation).run()).isInstanceOf(AccessDeniedException.class);

        assertNothingChanged();
    }

    static Stream<String> operationNames() {
        return Stream.of("create", "list", "suspend", "reactivate");
    }

    private Stream<Runnable> operations() {
        return operationNames().map(this::operation);
    }

    private Runnable operation(String name) {
        UUID anyClinic = UUID.randomUUID();
        return switch (name) {
            case "create" -> () -> administration.create(new NewClinic("Denegada", "Denegada S.L.", "B1", null, null));
            case "list" -> () -> administration.list(PageRequest.of(0, 10));
            case "suspend" -> () -> administration.suspend(anyClinic);
            case "reactivate" -> () -> administration.reactivate(anyClinic);
            default -> throw new IllegalArgumentException(name);
        };
    }

    private void assertNothingChanged() throws SQLException {
        assertThat(countClinicsAsPlatform()).isEqualTo(clinicsBefore);
        assertThat(events.stream(es.crisbr.dentasoft.platform.ClinicCreated.class))
                .isEmpty();
    }

    private static long countClinicsAsPlatform() throws SQLException {
        try (Connection connection = connectAsPlatform();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT count(*) FROM clinic")) {
            result.next();
            return result.getLong(1);
        }
    }

    @Test
    @WithMockUser(roles = "PLATFORM_ADMIN")
    void letsThePlatformAdministratorList() {
        assertThat(administration.list(PageRequest.of(0, 1))).isNotNull();
    }
}
