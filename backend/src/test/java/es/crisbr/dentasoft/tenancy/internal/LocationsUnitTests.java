package es.crisbr.dentasoft.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.tenancy.persistence.Location;
import java.sql.BatchUpdateException;
import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * How {@link Locations} tells the active-name index from any other refused write. The server's error is built here as
 * PostgreSQL sends it: fields of one letter (S severity, C SQLSTATE, M message, n constraint), each ended by U+0000.
 */
class LocationsUnitTests {

    /**
     * The message as a Spanish server writes it, with «» quotes: Hibernate finds no constraint name in it (it gives
     * {@code null}), but the server names the constraint in a field of its own.
     */
    @Test
    void recognisesTheActiveNameKeyWhateverTheServersLanguage() {
        PSQLException error = serverError(
                "23505",
                "llave duplicada viola restricción de unicidad «" + Location.ACTIVE_NAME_KEY + "»",
                Location.ACTIVE_NAME_KEY);

        assertThat(Locations.violatesActiveNameKey(translated(error))).isTrue();
    }

    @Test
    void recognisesItInABatchedWrite() {
        BatchUpdateException batch = new BatchUpdateException("Batch entry 0 was aborted", "23505", new int[0]);
        batch.setNextException(serverError("23505", "duplicate key", Location.ACTIVE_NAME_KEY));

        assertThat(Locations.violatesActiveNameKey(translated(batch))).isTrue();
    }

    @Test
    void anyOtherRefusedWriteIsNotANameConflict() {
        assertThat(Locations.violatesActiveNameKey(translated(serverError("23505", "duplicate key", "location_pkey"))))
                .isFalse();
        assertThat(Locations.violatesActiveNameKey(
                        translated(serverError("23514", "check violated", Location.ACTIVE_NAME_KEY))))
                .isFalse();
        assertThat(Locations.violatesActiveNameKey(translated(new SQLException("no server error", "23505"))))
                .isFalse();
    }

    private static PSQLException serverError(String sqlState, String message, String constraint) {
        return new PSQLException(
                new ServerErrorMessage("SERROR\0C" + sqlState + "\0M" + message + "\0n" + constraint + "\0"));
    }

    /** As Spring hands it over: Hibernate's exception, without a constraint name, wrapping the driver's. */
    private static DataIntegrityViolationException translated(SQLException error) {
        return new DataIntegrityViolationException(
                "could not execute statement", new ConstraintViolationException("insert failed", error, null));
    }
}
