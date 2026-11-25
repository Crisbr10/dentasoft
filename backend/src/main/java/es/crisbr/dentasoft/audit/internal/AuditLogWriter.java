package es.crisbr.dentasoft.audit.internal;

import es.crisbr.dentasoft.shared.RequestId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.InstantSource;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.springframework.stereotype.Component;

/**
 * Writes entries to {@code audit_log} on the connection it is given, which must be the one of the transaction the
 * entry belongs to: the database takes the clinic from it ({@code app.tenant_id}, V004) and fails without one.
 *
 * <p>Plain JDBC, so it writes from inside a Hibernate flush ({@code AuditChangeListener}) as well as from a
 * transaction of its own ({@code AuditService}). Entries of the main unit carry no details.
 */
@Component
public class AuditLogWriter {

    static final String INSERT = "INSERT INTO audit_log (id, occurred_at, actor_type, actor_id, action, entity_type,"
            + " entity_id, outcome, request_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final InstantSource clock;

    AuditLogWriter() {
        this(InstantSource.system());
    }

    AuditLogWriter(InstantSource clock) {
        this.clock = clock;
    }

    public void write(Connection connection, AuditEntry entry) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setObject(1, newId());
            statement.setObject(2, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
            statement.setString(3, entry.actorType().name());
            statement.setObject(4, entry.actorId());
            statement.setString(5, entry.action().name());
            statement.setString(6, entry.entityType().name());
            statement.setObject(7, entry.entityId());
            statement.setString(8, entry.outcome().name());
            statement.setObject(9, RequestId.serverGenerated().orElse(null));
            statement.executeUpdate();
        }
    }

    /** UUID v7, from the same generator as the ids of the entities ({@code BaseEntity}). */
    private static UUID newId() {
        return UuidVersion7Strategy.INSTANCE.generateUuid(null);
    }
}
