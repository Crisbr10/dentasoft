package es.crisbr.dentasoft.tenancy;

import static es.crisbr.dentasoft.TestPostgresConfiguration.APP_USER;
import static es.crisbr.dentasoft.TestPostgresConfiguration.connectAsOwner;
import static es.crisbr.dentasoft.tenancy.IsolationFixture.CLINIC_A;
import static es.crisbr.dentasoft.tenancy.IsolationFixture.CLINIC_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.TestData;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves that {@link TenantIsolationTest} detects each broken layer: its checks fail when Row Level Security or
 * {@code @TenantId} is removed, each one on its own while the other layer still protects the data.
 *
 * <p>Row Level Security is broken on a throwaway copy of {@code tenancy_test_record} in the schema
 * {@value #SCHEMA}, never on the shared table: a failed restore cannot affect other tests, and
 * {@link RowLevelSecurityGuardTests}, which only inspects {@code public}, never sees the copy. Before it is broken,
 * each test checks that the copy is isolated as AGENTS.md requires. {@code @TenantId} is removed with
 * {@link TenancyTestRecordWithoutTenantId}, the same table mapped without it.
 */
@IntegrationTest
class TenantIsolationTestMutationTests {

    private static final String SCHEMA = "isolation_probe";
    private static final String COPY = SCHEMA + ".tenancy_test_record";
    private static final String TENANT = "tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid";

    @Autowired
    private TenancyTestRecordRepository records;

    @Autowired
    private TenancyTestRecordWithoutTenantIdRepository recordsWithoutTenantId;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcClient jdbc;

    private IsolationFixture fixture;

    @BeforeEach
    void storeOneRecordForEachClinic() throws SQLException {
        dropCopy();
        TenancyTestDatabase.insertClinics();
        IsolationFixture seeding = new IsolationFixture("tenancy_test_record", null, null, transaction);
        fixture = new IsolationFixture(
                "tenancy_test_record",
                seeding.asClinic(CLINIC_A, () -> records.save(new TenancyTestRecord(name(0))))
                        .getId(),
                seeding.asClinic(CLINIC_B, () -> records.save(new TenancyTestRecord(name(1))))
                        .getId(),
                transaction);
    }

    @AfterEach
    void deleteCopyAndClinics() throws SQLException {
        try {
            dropCopy();
        } finally {
            TenancyTestDatabase.deleteClinicsAndRecords();
        }
    }

    @Test
    void rowLevelSecurityChecksPassOnTheIntactCopy() throws SQLException {
        RowLevelSecurityIsolationChecks checks = rowLevelSecurityOnCopy();

        assertThatCode(checks::hidesTheOtherClinic).doesNotThrowAnyException();
        assertThatCode(checks::blocksUpdatesOfTheOtherClinic).doesNotThrowAnyException();
        assertThatCode(checks::blocksDeletesOfTheOtherClinic).doesNotThrowAnyException();
        assertThatCode(checks::blocksWritesIntoTheOtherClinic).doesNotThrowAnyException();
    }

    /** With Row Level Security forced and no policy, nothing is visible: only the control of the read check fails. */
    @Test
    void withoutThePolicyTheReadCheckFails() throws SQLException {
        RowLevelSecurityIsolationChecks checks = rowLevelSecurityOnCopy();

        ownerExecute("DROP POLICY tenant_isolation ON " + COPY);

        assertFails(checks::hidesTheOtherClinic, "must see its own row");
    }

    @Test
    void withRowLevelSecurityDisabledTheChecksFail() throws SQLException {
        RowLevelSecurityIsolationChecks checks = rowLevelSecurityOnCopy();

        ownerExecute("ALTER TABLE %s NO FORCE ROW LEVEL SECURITY".formatted(COPY));
        ownerExecute("ALTER TABLE %s DISABLE ROW LEVEL SECURITY".formatted(COPY));

        assertFails(checks::hidesTheOtherClinic, "let clinic A read clinic B's row");
        assertFails(checks::blocksUpdatesOfTheOtherClinic, "after an UPDATE with clinic A active");
        assertFails(checks::blocksWritesIntoTheOtherClinic, "must reject moving clinic A's row");
    }

    /**
     * Without WITH CHECK, clinic A can insert rows for clinic B. Moving its own row is still rejected: an UPDATE whose
     * WHERE reads columns also checks the new row against USING.
     */
    @Test
    void withAPolicyThatDoesNotCheckWritesTheWriteCheckFails() throws SQLException {
        RowLevelSecurityIsolationChecks checks = rowLevelSecurityOnCopy();

        ownerExecute("ALTER POLICY tenant_isolation ON %s WITH CHECK (true)".formatted(COPY));

        assertThatCode(checks::hidesTheOtherClinic).doesNotThrowAnyException();
        assertThatCode(checks::blocksUpdatesOfTheOtherClinic).doesNotThrowAnyException();
        assertFails(checks::blocksWritesIntoTheOtherClinic, "must reject inserting a row for clinic B");
    }

    @Test
    void hibernateChecksPassWithTenantId() {
        HibernateIsolationChecks<TenancyTestRecord> checks = new HibernateIsolationChecks<>(
                fixture,
                TenancyTestRecord.class,
                records,
                record -> record.setLabel(record.getLabel() + " (changed)"),
                entityManager,
                jdbc);

        assertThatCode(checks::hidesTheOtherClinic).doesNotThrowAnyException();
        assertThatCode(checks::blocksUpdatesOfTheOtherClinic).doesNotThrowAnyException();
        assertThatCode(checks::blocksDeletesOfTheOtherClinic).doesNotThrowAnyException();
    }

    /**
     * Without {@code @TenantId}, Hibernate reads and changes clinic B's row once Row Level Security lets it through.
     * The delete check still passes: {@code dentasoft_app} has no DELETE grant at all.
     */
    @Test
    void withoutTenantIdTheHibernateChecksFail() {
        HibernateIsolationChecks<TenancyTestRecordWithoutTenantId> checks = new HibernateIsolationChecks<>(
                fixture,
                TenancyTestRecordWithoutTenantId.class,
                recordsWithoutTenantId,
                record -> record.setLabel(record.getLabel() + " (changed)"),
                entityManager,
                jdbc);

        assertFails(checks::hidesTheOtherClinic, "returned clinic B's");
        assertFails(checks::blocksUpdatesOfTheOtherClinic, "tried to change its TenancyTestRecordWithoutTenantId");
        assertThatCode(checks::blocksDeletesOfTheOtherClinic).doesNotThrowAnyException();
        // Row Level Security, the other layer, still protects the row on its own.
        assertThatCode(new RowLevelSecurityIsolationChecks(fixture, jdbc)::hidesTheOtherClinic)
                .doesNotThrowAnyException();
    }

    private static void assertFails(ThrowingCallable check, String message) {
        assertThatThrownBy(check).isInstanceOf(AssertionError.class).hasMessageContaining(message);
    }

    /**
     * Copies {@code tenancy_test_record} and its rows into {@value #SCHEMA}, isolated exactly as
     * {@code enable_tenant_isolation} isolates the tables of {@code public}, and checks that it is.
     */
    private RowLevelSecurityIsolationChecks rowLevelSecurityOnCopy() throws SQLException {
        ownerExecute("CREATE SCHEMA " + SCHEMA);
        ownerExecute("CREATE TABLE %s (LIKE public.tenancy_test_record INCLUDING ALL)".formatted(COPY));
        ownerExecute("ALTER TABLE %s ENABLE ROW LEVEL SECURITY".formatted(COPY));
        ownerExecute("ALTER TABLE %s FORCE ROW LEVEL SECURITY".formatted(COPY));
        ownerExecute("CREATE POLICY tenant_isolation ON %s FOR ALL USING (%s) WITH CHECK (%s)"
                .formatted(COPY, TENANT, TENANT));
        ownerExecute("GRANT USAGE ON SCHEMA %s TO %s".formatted(SCHEMA, APP_USER));
        ownerExecute("GRANT SELECT, INSERT, UPDATE ON %s TO %s".formatted(COPY, APP_USER));
        try (Connection connection = connectAsOwner()) {
            for (var clinic : List.of(CLINIC_A, CLINIC_B)) {
                try (PreparedStatement statement =
                        connection.prepareStatement("SELECT set_config('app.tenant_id', ?, false)")) {
                    statement.setString(1, clinic.toString());
                    statement.execute();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO %s SELECT * FROM public.tenancy_test_record WHERE tenant_id = ?"
                                .formatted(COPY))) {
                    statement.setObject(1, clinic);
                    statement.executeUpdate();
                }
            }
            assertThat(new TenantIsolationInspector(connection, SCHEMA, Map.of(), Set.of()).problems())
                    .as("the copy must be isolated as AGENTS.md requires before it is broken")
                    .isEmpty();
        }
        return new RowLevelSecurityIsolationChecks(
                new IsolationFixture(COPY, fixture.ownRow(), fixture.otherRow(), transaction), jdbc);
    }

    private static void dropCopy() throws SQLException {
        ownerExecute("DROP SCHEMA IF EXISTS %s CASCADE".formatted(SCHEMA));
    }

    /** DDL with fixed identifiers from this class: nothing here comes from outside the test. */
    private static void ownerExecute(String sql) throws SQLException {
        try (Connection connection = connectAsOwner();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String name(int n) {
        return TestData.person(n).givenName();
    }
}
