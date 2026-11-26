package es.crisbr.dentasoft.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.identity.PractitionerSpecialty;
import es.crisbr.dentasoft.identity.PractitionerSummary;
import es.crisbr.dentasoft.identity.internal.PractitionerRoles.PractitionerData;
import es.crisbr.dentasoft.identity.persistence.PractitionerRole;
import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * How {@link PractitionerRoles} tells which unique key refused a write, so that two requests racing for the same
 * licence number or user get a 409 and not a 500. The server's error is built as PostgreSQL sends it: fields of one
 * letter (S severity, C SQLSTATE, M message, n constraint), each ended by U+0000.
 */
class PractitionerRolesUnitTests {

    @Test
    void namesTheUniqueKeyWhateverTheServersLanguage() {
        PSQLException license = serverError(
                "23505", "llave duplicada viola restricción de unicidad", PractitionerRole.ACTIVE_LICENSE_KEY);
        PSQLException user = serverError("23505", "duplicate key", PractitionerRole.USER_KEY);

        assertThat(PractitionerRoles.violatedUniqueKey(translated(license)))
                .isEqualTo(PractitionerRole.ACTIVE_LICENSE_KEY);
        assertThat(PractitionerRoles.violatedUniqueKey(translated(user))).isEqualTo(PractitionerRole.USER_KEY);
    }

    @Test
    void namesItInABatchedWrite() {
        BatchUpdateException batch = new BatchUpdateException("Batch entry 0 was aborted", "23505", new int[0]);
        batch.setNextException(serverError("23505", "duplicate key", PractitionerRole.ACTIVE_LICENSE_KEY));

        assertThat(PractitionerRoles.violatedUniqueKey(translated(batch)))
                .isEqualTo(PractitionerRole.ACTIVE_LICENSE_KEY);
    }

    @Test
    void anyOtherRefusedWriteNamesNoUniqueKey() {
        assertThat(PractitionerRoles.violatedUniqueKey(
                        translated(serverError("23514", "check violated", PractitionerRole.ACTIVE_LICENSE_KEY))))
                .isNull();
        assertThat(PractitionerRoles.violatedUniqueKey(translated(new SQLException("no server error", "23505"))))
                .isNull();
    }

    /** Names and licence numbers are personal data: never in a log line through {@code toString}. */
    @Test
    void recordsNeverPrintTheNameOrTheLicenseNumber() {
        PractitionerData data = new PractitionerData(
                null, "Dra. Ana Pruebas", PractitionerSpecialty.ORTHODONTICS, "28", "28-1234", "#1E88E5");
        PractitionerSummary summary = new PractitionerSummary(
                UUID.fromString("5f0c2b1e-0000-4000-8000-0000000009a1"),
                "Dra. Ana Pruebas",
                PractitionerSpecialty.ORTHODONTICS,
                "#1E88E5",
                true);

        for (Object record : new Object[] {data, summary}) {
            assertThat(record.toString()).doesNotContain("Ana").doesNotContain("1234");
        }
    }

    private static PSQLException serverError(String sqlState, String message, String constraint) {
        return new PSQLException(
                new ServerErrorMessage("SERROR\0C" + sqlState + "\0M" + message + "\0n" + constraint + "\0"));
    }

    /** As Spring hands it over: Hibernate's exception wrapping the driver's. */
    private static DataIntegrityViolationException translated(SQLException error) {
        return new DataIntegrityViolationException(
                "could not execute statement", new ConstraintViolationException("insert failed", error, null));
    }
}
