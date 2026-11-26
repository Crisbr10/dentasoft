package es.crisbr.dentasoft.identity;

import static es.crisbr.dentasoft.TestPostgresConfiguration.connectAsApplication;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.TestData;
import es.crisbr.dentasoft.tenancy.TenancyTestDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What PostgreSQL itself guarantees about practitioner records (V012), through SQL on the application's role, which
 * Hibernate does not filter: a record never changes person, a linked user is a member of the same clinic, nothing is
 * deleted, and the data keeps its format whatever the application sends.
 */
@IntegrationTest
class PractitionerRoleDatabaseTests {

    private static final UUID CLINIC_A = TestData.CLINIC_A.id();
    private static final UUID CLINIC_B = TestData.CLINIC_B.id();
    private static final UUID DENTIST_A = UUID.fromString("5f0c2b1e-0000-4000-8000-0000000007a1");
    private static final UUID DENTIST_A2 = UUID.fromString("5f0c2b1e-0000-4000-8000-0000000007a2");
    private static final UUID DENTIST_B = UUID.fromString("5f0c2b1e-0000-4000-8000-0000000007b1");
    private static final List<UUID> USERS = List.of(DENTIST_A, DENTIST_A2, DENTIST_B);

    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String CHECK_VIOLATION = "23514";

    @BeforeEach
    void seed() throws SQLException {
        TenancyTestDatabase.insertClinics();
        for (UUID user : USERS) {
            IdentityTestDatabase.insertUser(user);
        }
        IdentityTestDatabase.insertMembership(CLINIC_A, DENTIST_A, ClinicRole.ODONTOLOGO, true);
        IdentityTestDatabase.insertMembership(CLINIC_A, DENTIST_A2, ClinicRole.ODONTOLOGO, true);
        IdentityTestDatabase.insertMembership(CLINIC_B, DENTIST_B, ClinicRole.ODONTOLOGO, true);
    }

    @AfterEach
    void deleteData() throws SQLException {
        TenancyTestDatabase.deleteClinicsAnd(List.of("practitioner_role", "clinic_membership"));
        IdentityTestDatabase.deleteUsers(USERS);
    }

    @Test
    void aLinkedRecordNeverChangesUser() throws SQLException {
        UUID record = insert(DENTIST_A);

        assertRejected(INSUFFICIENT_PRIVILEGE, "UPDATE practitioner_role SET user_id = '" + DENTIST_A2 + "'");
        assertRejected(INSUFFICIENT_PRIVILEGE, "UPDATE practitioner_role SET user_id = NULL");
        // What Hibernate sends on every change: the same user, with the rest of the row.
        assertThat(update("UPDATE practitioner_role SET user_id = '" + DENTIST_A + "', display_name = 'Dra. Nueva'"))
                .isOne();
        assertThat(userOf(record)).isEqualTo(DENTIST_A);
    }

    @Test
    void aRecordWithoutAUserIsLinkedOnce() throws SQLException {
        UUID record = insert(null);

        assertThat(update("UPDATE practitioner_role SET user_id = '" + DENTIST_A + "'"))
                .isOne();

        assertThat(userOf(record)).isEqualTo(DENTIST_A);
        assertRejected(INSUFFICIENT_PRIVILEGE, "UPDATE practitioner_role SET user_id = '" + DENTIST_A2 + "'");
    }

    /** Someone who is not a member of the clinic, or only of another one, cannot be its practitioner. */
    @Test
    void aLinkedUserIsAMemberOfTheSameClinic() {
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insert(DENTIST_B))
                .satisfies(error -> assertThat(error.getSQLState()).isEqualTo(FOREIGN_KEY_VIOLATION));
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> insert(UUID.randomUUID()))
                .satisfies(error -> assertThat(error.getSQLState()).isEqualTo(FOREIGN_KEY_VIOLATION));
    }

    @Test
    void recordsAreNeverDeletedAndTheirCreationIsNeverRewritten() throws SQLException {
        insert(DENTIST_A);

        assertRejected(INSUFFICIENT_PRIVILEGE, "DELETE FROM practitioner_role");
        assertRejected(INSUFFICIENT_PRIVILEGE, "UPDATE practitioner_role SET id = gen_random_uuid()");
        assertRejected(INSUFFICIENT_PRIVILEGE, "UPDATE practitioner_role SET created_at = now()");
        assertRejected(INSUFFICIENT_PRIVILEGE, "UPDATE practitioner_role SET created_by = NULL");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "agenda_color = '#1e88e5'",
                "agenda_color = 'red'",
                "specialty = 'COSMETIC'",
                "status = 'DELETED'",
                "license_province = '53'",
                "license_province = '00'",
                "license_number = '12'",
                "license_number = '28 1234'",
                "license_number = '28--1234'",
                "license_number = 'abcd'",
                "license_province = NULL",
                "license_number = NULL, license_province = NULL"
            })
    void theDataKeepsItsFormat(String change) throws SQLException {
        insert(DENTIST_A);

        assertRejected(CHECK_VIOLATION, "UPDATE practitioner_role SET " + change);
    }

    /** Only a hygienist's licence number is optional. */
    @Test
    void aHygienistMayHaveNoLicenseNumber() throws SQLException {
        insert(null);

        assertThat(update("UPDATE practitioner_role SET specialty = 'DENTAL_HYGIENE', license_number = NULL,"
                        + " license_province = NULL"))
                .isOne();
    }

    // --- Helpers ---

    /** Inserts a record of clinic A as the application, with an orthodontics licence of Madrid. */
    private static UUID insert(@Nullable UUID user) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = inClinicA()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO practitioner_role (id, tenant_id, user_id, display_name, specialty, license_province,"
                            + " license_number, agenda_color, created_at, updated_at)"
                            + " VALUES (?, ?, ?, 'Dra. Pruebas', 'ORTHODONTICS', '28', '1234', '#1E88E5', now(), now())")) {
                statement.setObject(1, id);
                statement.setObject(2, CLINIC_A);
                statement.setObject(3, user);
                statement.executeUpdate();
            }
            connection.commit();
        }
        return id;
    }

    private static int update(String sql) throws SQLException {
        try (Connection connection = inClinicA();
                Statement statement = connection.createStatement()) {
            int rows = statement.executeUpdate(sql);
            connection.commit();
            return rows;
        }
    }

    private static void assertRejected(String sqlState, String sql) {
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(() -> update(sql))
                .satisfies(error -> assertThat(error.getSQLState()).as(sql).isEqualTo(sqlState));
    }

    private static @Nullable UUID userOf(UUID record) throws SQLException {
        try (Connection connection = inClinicA();
                PreparedStatement statement =
                        connection.prepareStatement("SELECT user_id FROM practitioner_role WHERE id = ?")) {
            statement.setObject(1, record);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getObject(1, UUID.class);
            }
        }
    }

    /** A transaction of the application in clinic A, as the application opens one ({@code set_config(…, true)}). */
    private static Connection inClinicA() throws SQLException {
        Connection connection = connectAsApplication();
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
            statement.setString(1, CLINIC_A.toString());
            statement.execute();
        }
        return connection;
    }
}
