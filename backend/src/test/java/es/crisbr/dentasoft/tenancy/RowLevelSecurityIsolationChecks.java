package es.crisbr.dentasoft.tenancy;

import static es.crisbr.dentasoft.tenancy.IsolationFixture.CLINIC_A;
import static es.crisbr.dentasoft.tenancy.IsolationFixture.CLINIC_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The first layer on its own: Row Level Security. With clinic A active, through the real path, native SQL, which
 * Hibernate does not filter, must not read, change or delete clinic B's row, nor write rows into clinic B. Every write
 * check then reads, as the owner, what is stored: an attempt that does not fail is not enough.
 *
 * <p>Each check throws an {@link AssertionError} when the layer lets something through.
 */
final class RowLevelSecurityIsolationChecks {

    private final IsolationFixture fixture;
    private final JdbcClient jdbc;
    private final String table;

    RowLevelSecurityIsolationChecks(IsolationFixture fixture, JdbcClient jdbc) {
        this.fixture = fixture;
        this.jdbc = jdbc;
        this.table = fixture.table();
    }

    void hidesTheOtherClinic() {
        fixture.asClinic(CLINIC_A, () -> {
            // Without this control, removing the policy would pass: with Row Level Security forced and no policy,
            // PostgreSQL hides every row, clinic A's too.
            assertThat(count("SELECT count(*) FROM %s WHERE id = ?", fixture.ownRow()))
                    .as("clinic A must see its own row in %s; is its tenant_isolation policy missing?", table)
                    .isOne();
            assertThat(count("SELECT count(*) FROM %s WHERE id = ?", fixture.otherRow()))
                    .as("Row Level Security let clinic A read clinic B's row in %s", table)
                    .isZero();
            assertThat(count("SELECT count(*) FROM %s WHERE tenant_id <> ?", CLINIC_A))
                    .as("Row Level Security let clinic A read rows of other clinics in %s", table)
                    .isZero();
            return null;
        });
    }

    void blocksUpdatesOfTheOtherClinic() {
        String before = fixture.storedOtherRow();

        fixture.attemptAsClinicA(() -> jdbc.sql("UPDATE %s SET version = version + 1 WHERE id = ?".formatted(table))
                .param(fixture.otherRow())
                .update());

        assertThat(fixture.storedOtherRow())
                .as("clinic B's row in %s after an UPDATE with clinic A active", table)
                .isEqualTo(before);
    }

    /**
     * {@code dentasoft_app} has DELETE only on {@code clinic_hours_of_operation} (V003): there the policy hides clinic
     * B's row from the statement; elsewhere the statement fails for lack of the grant. The row must still be there.
     */
    void blocksDeletesOfTheOtherClinic() {
        String before = fixture.storedOtherRow();

        fixture.attemptAsClinicA(() -> jdbc.sql("DELETE FROM %s WHERE id = ?".formatted(table))
                .param(fixture.otherRow())
                .update());

        assertThat(fixture.storedOtherRow())
                .as("clinic B's row in %s after a DELETE with clinic A active", table)
                .isEqualTo(before);
    }

    /** The policy's WITH CHECK: clinic A cannot move its row to clinic B, nor insert a row for clinic B. */
    void blocksWritesIntoTheOtherClinic() {
        String ownBefore = fixture.storedOwnRow();
        int otherRowsBefore = fixture.storedRowsOf(CLINIC_B);

        Throwable moved = catchThrowable(() -> fixture.asClinic(
                CLINIC_A,
                () -> jdbc.sql("UPDATE %s SET tenant_id = ? WHERE id = ?".formatted(table))
                        .params(CLINIC_B, fixture.ownRow())
                        .update()));
        // A copy of clinic A's row with a new id and clinic B, whatever columns the table has.
        Throwable inserted = catchThrowable(() -> fixture.asClinic(
                CLINIC_A,
                () -> jdbc.sql(("INSERT INTO %1$s SELECT (jsonb_populate_record(NULL::%1$s, to_jsonb(r)"
                                        + " || jsonb_build_object('id', ?::uuid, 'tenant_id', ?::uuid))).*"
                                        + " FROM %1$s r WHERE id = ?")
                                .formatted(table))
                        .params(UUID.randomUUID(), CLINIC_B, fixture.ownRow())
                        .update()));

        assertThat(rejectedByRowLevelSecurity(moved))
                .as("Row Level Security must reject moving clinic A's row in %s to clinic B; got %s", table, moved)
                .isTrue();
        assertThat(rejectedByRowLevelSecurity(inserted))
                .as("Row Level Security must reject inserting a row for clinic B into %s; got %s", table, inserted)
                .isTrue();
        assertThat(fixture.storedOwnRow())
                .as("clinic A's row in %s after trying to move it to clinic B", table)
                .isEqualTo(ownBefore);
        assertThat(fixture.storedRowsOf(CLINIC_B))
                .as("rows of clinic B in %s after clinic A tried to write into it", table)
                .isEqualTo(otherRowsBefore);
    }

    private long count(String sql, UUID parameter) {
        return jdbc.sql(sql.formatted(table)).param(parameter).query(Long.class).single();
    }

    private static boolean rejectedByRowLevelSecurity(Throwable thrown) {
        return Stream.iterate(thrown, Objects::nonNull, Throwable::getCause)
                .anyMatch(cause -> String.valueOf(cause.getMessage()).contains("violates row-level security policy"));
    }
}
