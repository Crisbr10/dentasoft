package es.crisbr.dentasoft.tenancy.internal;

import es.crisbr.dentasoft.tenancy.TenantContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.hibernate.engine.spi.SessionImplementor;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;

/**
 * Passes the clinic of each transaction to PostgreSQL, so Row Level Security (ADR 0002) uses it: right after the
 * transaction begins, {@code set_config('app.tenant_id', clinic, true)} on the session's connection.
 *
 * <p>{@code true} makes the setting local to the transaction, like {@code SET LOCAL} (which takes no parameters).
 * PostgreSQL drops it on commit and on rollback, so a connection never goes back to the pool with a clinic, even when
 * the transaction fails. Nothing is set when the connection is taken from the pool or for the whole session.
 *
 * <p>The clinic is the session's ({@link ActiveClinicResolver}), which must be the active one: if they differ, the
 * transaction does not start. Without a clinic ({@code NO_CLINIC}) nothing is set and tables with Row Level Security
 * return no rows. Code that runs outside a Spring transaction never gets here, so it sees no clinic data either.
 *
 * <p>{@code JdbcClient} and {@code JdbcTemplate} inside the transaction use the same connection, so they are covered
 * too. Applied by {@link TenantAwareJpaDialectPostProcessor}.
 */
final class TenantAwareJpaDialect extends HibernateJpaDialect {

    private static final String SET_DATABASE_CLINIC = "SELECT set_config('app.tenant_id', ?, true)";

    @Override
    public Object beginTransaction(EntityManager entityManager, TransactionDefinition definition)
            throws PersistenceException, SQLException, TransactionException {
        SessionImplementor session = entityManager.unwrap(SessionImplementor.class);
        UUID clinic = clinicOf(session);
        Object transactionData = super.beginTransaction(entityManager, definition);
        if (!ActiveClinicResolver.NO_CLINIC.equals(clinic)) {
            try {
                session.doWork(connection -> setDatabaseClinic(connection, clinic));
            } catch (RuntimeException e) {
                // Spring only rolls back sessions it opened for this transaction, not one bound beforehand.
                try {
                    entityManager.getTransaction().rollback();
                } catch (RuntimeException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            }
        }
        return transactionData;
    }

    /** The session's clinic, checked against the active one before the transaction begins. */
    private static UUID clinicOf(SessionImplementor session) {
        UUID active = TenantContext.isBound() ? TenantContext.current() : ActiveClinicResolver.NO_CLINIC;
        if (!(session.getTenantIdentifierValue() instanceof UUID clinic) || !clinic.equals(active)) {
            throw new IllegalStateException(
                    "The session was opened for another clinic than the active one: the transaction cannot start");
        }
        return clinic;
    }

    private static void setDatabaseClinic(Connection connection, UUID clinic) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SET_DATABASE_CLINIC)) {
            statement.setString(1, clinic.toString());
            statement.execute();
        }
    }
}
