package es.crisbr.dentasoft.audit.internal;

import es.crisbr.dentasoft.shared.RequestId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import org.hibernate.id.uuid.UuidVersion7Strategy;

/**
 * Writes entries to {@code audit_log} on the connection it is given, which must be the one of the transaction the
 * entry belongs to. The database fills in the clinic, from that transaction ({@code app.tenant_id}), and the time,
 * from its own clock (V004): without an active clinic the insert fails.
 *
 * <p>Plain JDBC, so it writes from inside a Hibernate flush ({@code AuditChangeListener}) as well as from a
 * transaction of its own ({@code AuditService}). Entries of the main unit carry no details: the application may not
 * even write that column.
 */
public final class AuditLogWriter {

    static final String INSERT = "INSERT INTO audit_log (id, actor_type, actor_id, action, entity_type, entity_id,"
            + " outcome, request_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    public void write(Connection connection, AuditEntry entry) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setObject(1, newId());
            statement.setString(2, entry.actorType().name());
            statement.setObject(3, entry.actorId());
            statement.setString(4, entry.action().name());
            statement.setString(5, entry.entityType().name());
            statement.setObject(6, entry.entityId());
            statement.setString(7, entry.outcome().name());
            statement.setObject(8, RequestId.serverGenerated().orElse(null));
            statement.executeUpdate();
        }
    }

    /** UUID v7, from the same generator as the ids of the entities ({@code BaseEntity}). */
    private static UUID newId() {
        return UuidVersion7Strategy.INSTANCE.generateUuid(null);
    }
}
