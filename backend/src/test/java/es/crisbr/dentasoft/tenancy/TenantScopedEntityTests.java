package es.crisbr.dentasoft.tenancy;

import static es.crisbr.dentasoft.TestPostgresConfiguration.connectAsApplication;
import static es.crisbr.dentasoft.TestPostgresConfiguration.connectAsOwner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.catchThrowable;

import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.TestData;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.hibernate.PropertyValueException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.support.JpaEntityInformationSupport;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Clinic isolation in JPA through {@link TenantScopedEntity}, on the test-only table {@code tenancy_test_record}.
 *
 * <p>Until M01-T03 the application does not pass the active clinic to PostgreSQL, so Row Level Security would block
 * every row. These tests set {@code app.tenant_id} themselves, inside each transaction. Setting it to a clinic other
 * than the active one lets Row Level Security through and shows that Hibernate filters on its own.
 */
@IntegrationTest
class TenantScopedEntityTests {

    private static final UUID CLINIC_A = TestData.CLINIC_A.id();
    private static final UUID CLINIC_B = TestData.CLINIC_B.id();

    @Autowired
    private TenancyTestRecordRepository records;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void createClinics() throws SQLException {
        try (Connection connection = connectAsApplication()) {
            insertClinic(connection, CLINIC_A, TestData.CLINIC_A.name());
            insertClinic(connection, CLINIC_B, TestData.CLINIC_B.name());
        }
    }

    /** The application cannot delete; the owner can, one clinic at a time, as Row Level Security is forced. */
    @AfterEach
    void deleteData() throws SQLException {
        try (Connection connection = connectAsOwner()) {
            for (UUID clinic : List.of(CLINIC_A, CLINIC_B)) {
                setDatabaseClinic(connection, clinic);
                execute(connection, "DELETE FROM tenancy_test_record WHERE tenant_id = ?", clinic);
                execute(connection, "DELETE FROM clinic WHERE id = ?", clinic);
            }
        }
    }

    @Test
    void insertTakesTheActiveClinic() throws SQLException {
        TenancyTestRecord record = asClinic(CLINIC_A, () -> records.save(new TenancyTestRecord("north")));

        assertThat(record.getTenantId()).isEqualTo(CLINIC_A);
        assertThat(stored(CLINIC_A, record.getId())).hasValue(new StoredRecord(CLINIC_A, "north"));
    }

    @Test
    void baseEntityGetsAVersion7IdAuditColumnsAndAVersion() {
        TenancyTestRecord record = asClinic(CLINIC_A, () -> records.save(new TenancyTestRecord("north")));

        assertThat(record.getId().version()).isEqualTo(7);
        assertThat(record.getVersion()).isZero();
        assertThat(record.getCreatedAt()).isNotNull();
        assertThat(record.getUpdatedAt()).isEqualTo(record.getCreatedAt());
        // No users until M02.
        assertThat(record.getCreatedBy()).isNull();
        assertThat(record.getUpdatedBy()).isNull();
    }

    /** With a {@code Long} version, {@code save()} persists a new entity (same instance) instead of merging a copy. */
    @Test
    void newEntitiesArePersistedNotMerged() {
        var information = JpaEntityInformationSupport.getEntityInformation(TenancyTestRecord.class, entityManager);
        TenancyTestRecord record = new TenancyTestRecord("north");
        assertThat(information.isNew(record)).isTrue();

        TenancyTestRecord saved = asClinic(CLINIC_A, () -> records.save(record));

        assertThat(saved).isSameAs(record);
        assertThat(information.isNew(saved)).isFalse();
    }

    @Test
    void insertWithoutActiveClinicFails() throws SQLException {
        Throwable thrown = catchThrowable(
                () -> inTransaction(CLINIC_A, () -> records.saveAndFlush(new TenancyTestRecord("north"))));

        assertThat(causes(thrown)).anyMatch(NoActiveClinicException.class::isInstance);
        assertThat(countStored(CLINIC_A)).isZero();
    }

    @Test
    void insertWithAnotherClinicAssignedFails() throws SQLException {
        TenancyTestRecord record = new TenancyTestRecord("north");
        ReflectionTestUtils.setField(record, "tenantId", CLINIC_B);

        Throwable thrown = catchThrowable(() -> asClinic(CLINIC_A, () -> records.saveAndFlush(record)));

        assertThat(causes(thrown)).anyMatch(PropertyValueException.class::isInstance);
        assertThat(countStored(CLINIC_A)).isZero();
        assertThat(countStored(CLINIC_B)).isZero();
    }

    @Test
    void clinicOfAStoredEntityCannotChange() throws SQLException {
        UUID id = asClinic(CLINIC_A, () -> records.save(new TenancyTestRecord("north")))
                .getId();

        asClinic(CLINIC_A, () -> {
            TenancyTestRecord record = records.findById(id).orElseThrow();
            ReflectionTestUtils.setField(record, "tenantId", CLINIC_B);
            record.setLabel("north, renamed");
            return records.saveAndFlush(record);
        });

        assertThat(stored(CLINIC_A, id)).hasValue(new StoredRecord(CLINIC_A, "north, renamed"));
        assertThat(countStored(CLINIC_B)).isZero();
    }

    @Test
    void queriesOnlyReturnTheActiveClinic() {
        seed();

        assertThat(asClinic(CLINIC_A, this::allLabels)).containsExactly("north");
        assertThat(asClinic(CLINIC_B, this::allLabels)).containsExactly("south");
    }

    /** PostgreSQL lets clinic B's rows through, yet with clinic A active no JPA query or lookup returns them. */
    @Test
    void hibernateFiltersByTheActiveClinicOnItsOwn() {
        UUID southId = seed().south();

        TenantContext.runAs(
                CLINIC_A,
                () -> inTransaction(CLINIC_B, () -> {
                    assertThat(records.findAll()).isEmpty();
                    assertThat(records.findById(southId)).isEmpty();
                    assertThat(entityManager.find(TenancyTestRecord.class, southId))
                            .isNull();
                    assertThat(records.findByLabel("south")).isEmpty();
                    assertThat(records.findByLabelPrefix("so")).isEmpty();
                    assertThat(records.count()).isZero();
                    return null;
                }));
        assertThat(TenantContext.callAs(CLINIC_B, () -> inTransaction(CLINIC_B, this::allLabels)))
                .containsExactly("south");
    }

    /**
     * Without an active clinic, sessions use the {@code NO_CLINIC} marker: PostgreSQL lets clinic A's rows through, yet
     * no query returns any row, and a bulk update changes none.
     */
    @Test
    void withoutActiveClinicQueriesReturnNoRows() throws SQLException {
        UUID northId = seed().north();

        inTransaction(CLINIC_A, () -> {
            assertThat(records.findAll()).isEmpty();
            assertThat(records.findById(northId)).isEmpty();
            assertThat(entityManager.find(TenancyTestRecord.class, northId)).isNull();
            assertThat(records.findByLabel("north")).isEmpty();
            assertThat(records.findByLabelPrefix("no")).isEmpty();
            assertThat(records.count()).isZero();
            assertThat(records.relabelAll("changed")).isZero();
            return null;
        });

        assertThat(stored(CLINIC_A, northId)).hasValue(new StoredRecord(CLINIC_A, "north"));
    }

    @Test
    void withoutActiveClinicAStoredEntityCannotBeUpdatedOrDeleted() throws SQLException {
        TenancyTestRecord record = asClinic(CLINIC_A, () -> records.save(new TenancyTestRecord("north")));
        record.setLabel("north, renamed");

        assertThat(catchThrowable(() -> inTransaction(CLINIC_A, () -> records.saveAndFlush(record))))
                .isNotNull();
        inTransaction(CLINIC_A, () -> {
            records.deleteById(record.getId());
            records.delete(record);
            return null;
        });

        assertThat(stored(CLINIC_A, record.getId())).hasValue(new StoredRecord(CLINIC_A, "north"));
    }

    /** The session took its clinic when the transaction began: binding one afterwards would not reach it. */
    @Test
    void clinicCannotBeBoundInsideATransactionStartedWithoutOne() {
        inTransaction(CLINIC_A, () -> {
            assertThatIllegalStateException()
                    .isThrownBy(() -> TenantContext.runAs(CLINIC_A, () -> {}))
                    .withMessageContaining("before the transaction");
            return null;
        });
    }

    private Seeded seed() {
        UUID north = asClinic(CLINIC_A, () -> records.save(new TenancyTestRecord("north")))
                .getId();
        UUID south = asClinic(CLINIC_B, () -> records.save(new TenancyTestRecord("south")))
                .getId();
        return new Seeded(north, south);
    }

    private List<String> allLabels() {
        return records.findAll().stream().map(TenancyTestRecord::getLabel).toList();
    }

    /** {@code work} with {@code clinic} active, in a transaction where PostgreSQL also uses {@code clinic}. */
    private <T> T asClinic(UUID clinic, Supplier<T> work) {
        return TenantContext.callAs(clinic, () -> inTransaction(clinic, work));
    }

    /** {@code work} in a transaction where PostgreSQL uses {@code databaseClinic}; the active clinic is not changed. */
    private <T> T inTransaction(UUID databaseClinic, Supplier<T> work) {
        return transaction.execute(status -> {
            jdbc.sql("SELECT set_config('app.tenant_id', ?, true)")
                    .param(databaseClinic.toString())
                    .query(String.class)
                    .single();
            return work.get();
        });
    }

    /** The row as the owner sees it with {@code clinic} active. */
    private static Optional<StoredRecord> stored(UUID clinic, UUID id) throws SQLException {
        try (Connection connection = connectAsOwner()) {
            setDatabaseClinic(connection, clinic);
            try (PreparedStatement statement =
                    connection.prepareStatement("SELECT tenant_id, label FROM tenancy_test_record WHERE id = ?")) {
                statement.setObject(1, id);
                try (ResultSet row = statement.executeQuery()) {
                    return row.next()
                            ? Optional.of(new StoredRecord(row.getObject(1, UUID.class), row.getString(2)))
                            : Optional.empty();
                }
            }
        }
    }

    private static int countStored(UUID clinic) throws SQLException {
        try (Connection connection = connectAsOwner()) {
            setDatabaseClinic(connection, clinic);
            try (PreparedStatement statement = connection.prepareStatement("SELECT count(*) FROM tenancy_test_record");
                    ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        }
    }

    private static void insertClinic(Connection connection, UUID id, String name) throws SQLException {
        setDatabaseClinic(connection, id);
        try (PreparedStatement statement =
                connection.prepareStatement("INSERT INTO clinic (id, name, legal_name, tax_id, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'B00000000', now(), now())")) {
            statement.setObject(1, id);
            statement.setString(2, name);
            statement.setString(3, name + " S.L.");
            statement.executeUpdate();
        }
    }

    private static void setDatabaseClinic(Connection connection, UUID clinic) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT set_config('app.tenant_id', ?, false)")) {
            statement.setString(1, clinic.toString());
            statement.execute();
        }
    }

    private static void execute(Connection connection, String sql, UUID parameter) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, parameter);
            statement.executeUpdate();
        }
    }

    private static Stream<Throwable> causes(Throwable thrown) {
        return Stream.iterate(thrown, Objects::nonNull, Throwable::getCause);
    }

    private record Seeded(UUID north, UUID south) {}

    private record StoredRecord(UUID tenantId, String label) {}
}
