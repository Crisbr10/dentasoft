package es.crisbr.dentasoft.tenancy;

import es.crisbr.dentasoft.TestData;
import java.sql.SQLException;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What the isolation checks work on: a table with one row of clinic A ({@code ownRow}) and one of clinic B
 * ({@code otherRow}), and the transactions to reach it with a clinic active.
 */
record IsolationFixture(String table, UUID ownRow, UUID otherRow, TransactionTemplate transaction) {

    static final UUID CLINIC_A = TestData.CLINIC_A.id();
    static final UUID CLINIC_B = TestData.CLINIC_B.id();

    IsolationFixture {
        TenancyTestDatabase.checkedTable(table);
    }

    /** {@code work} with {@code clinic} active, in a transaction, which passes it to PostgreSQL: the real path. */
    <T> T asClinic(UUID clinic, Supplier<T> work) {
        return TenantContext.callAs(clinic, () -> transaction.execute(status -> work.get()));
    }

    /**
     * Tries {@code work} with clinic A active, in a transaction of its own, and ignores how it fails: being refused is
     * not enough, the checks then read what is stored. Assertion errors are not ignored.
     */
    void attemptAsClinicA(Supplier<?> work) {
        try {
            asClinic(CLINIC_A, work);
        } catch (RuntimeException refused) {
            // Expected for most attempts; the stored rows tell whether it was refused.
        }
    }

    /** Clinic A's row as the owner sees it. */
    String storedOwnRow() {
        return stored(CLINIC_A, ownRow);
    }

    /** Clinic B's row as the owner sees it. */
    String storedOtherRow() {
        return stored(CLINIC_B, otherRow);
    }

    int storedRowsOf(UUID clinic) {
        try {
            return TenancyTestDatabase.countRows(table, clinic);
        } catch (SQLException e) {
            throw new IllegalStateException("could not count the rows of " + table, e);
        }
    }

    private String stored(UUID clinic, UUID id) {
        try {
            return TenancyTestDatabase.storedRow(table, clinic, id)
                    .orElseThrow(() ->
                            new AssertionError("row %s of %s is not stored for its clinic, so nothing can be compared"
                                    .formatted(id, table)));
        } catch (SQLException e) {
            throw new IllegalStateException("could not read " + table, e);
        }
    }
}
