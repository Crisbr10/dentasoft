package es.crisbr.dentasoft.audit;

import static es.crisbr.dentasoft.TestPostgresConfiguration.APP_USER;
import static es.crisbr.dentasoft.TestPostgresConfiguration.connectAsOwner;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code audit_log} as the owner sees it, with direct connections that do not go through the application: the
 * application cannot read the log (V004).
 *
 * <p>Entries can never be deleted, not even by the owner, so tests never clean the log: they look only at the entries
 * of the records they created, which have ids of their own.
 */
public final class AuditLogDatabase {

    private AuditLogDatabase() {}

    /** One stored entry, without its id and time. */
    public record StoredEntry(
            UUID tenantId,
            String actorType,
            @Nullable UUID actorId,
            String action,
            String entityType,
            UUID entityId,
            String outcome,
            @Nullable UUID requestId,
            @Nullable String details) {}

    /** The entries about {@code entityId} stored under {@code clinic}, oldest first. */
    public static List<StoredEntry> entries(UUID clinic, UUID entityId) throws SQLException {
        try (Connection connection = connectAsOwner()) {
            setSessionClinic(connection, clinic);
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT tenant_id, actor_type, actor_id, action, entity_type, entity_id, outcome, request_id,"
                            + " details::text FROM audit_log WHERE entity_id = ? ORDER BY occurred_at, id")) {
                statement.setObject(1, entityId);
                try (ResultSet rows = statement.executeQuery()) {
                    List<StoredEntry> entries = new ArrayList<>();
                    while (rows.next()) {
                        entries.add(new StoredEntry(
                                rows.getObject(1, UUID.class),
                                rows.getString(2),
                                rows.getObject(3, UUID.class),
                                rows.getString(4),
                                rows.getString(5),
                                rows.getObject(6, UUID.class),
                                rows.getString(7),
                                rows.getObject(8, UUID.class),
                                rows.getString(9)));
                    }
                    return entries;
                }
            }
        }
    }

    /** The actions recorded about {@code entityId} under {@code clinic}, oldest first. */
    public static List<String> actions(UUID clinic, UUID entityId) throws SQLException {
        return entries(clinic, entityId).stream().map(StoredEntry::action).toList();
    }

    /**
     * Runs {@code action} while the application cannot write to the log, as when the database rejects an entry; the
     * privilege is given back afterwards, whatever happens. Tests run one at a time, so no other test sees it.
     */
    public static void withoutApplicationInsert(Runnable action) throws SQLException {
        try (Connection owner = connectAsOwner();
                Statement statement = owner.createStatement()) {
            statement.execute("REVOKE INSERT ON audit_log FROM " + APP_USER);
            try {
                action.run();
            } finally {
                statement.execute("GRANT INSERT ON audit_log TO " + APP_USER);
            }
        }
    }

    static void setSessionClinic(Connection connection, UUID clinic) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT set_config('app.tenant_id', ?, false)")) {
            statement.setString(1, clinic.toString());
            statement.execute();
        }
    }
}
