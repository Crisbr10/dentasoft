package es.crisbr.dentasoft.audit;

import es.crisbr.dentasoft.audit.internal.AuditEntry;
import es.crisbr.dentasoft.audit.internal.AuditLogWriter;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Records reads in {@code audit_log}, for the cases {@link AuditedRead} does not fit (a read of several records, for
 * instance). Changes need nothing: {@link AuditedEntity} records them.
 *
 * <p>Every entry is written in a read-write transaction of its own, so it works after a read-only one, which cannot
 * insert. The transaction begins with the active clinic, as every transaction does ({@code TenantAwareJpaDialect}),
 * and the entry is stored under it; without an active clinic it fails. A failure always reaches the caller, which
 * must not hand out what it read.
 *
 * <p>Call it after the read's own transaction has ended. From inside an open transaction it still works ({@code
 * REQUIRES_NEW}), but that transaction keeps its connection while this one takes another (ADR 0012).
 */
@Service
public class AuditService {

    private final AuditLogWriter writer;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate ownTransaction;

    AuditService(AuditLogWriter writer, JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.writer = writer;
        this.jdbc = jdbc;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** {@code entityId} of type {@code entityType} was read. */
    public void recordRead(AuditEntityType entityType, UUID entityId) {
        record(AuditEntry.of(AuditAction.READ, entityType, entityId, AuditOutcome.SUCCESS));
    }

    /** Reading {@code entityId} of type {@code entityType} was attempted and denied. */
    public void recordDeniedRead(AuditEntityType entityType, UUID entityId) {
        record(AuditEntry.of(AuditAction.READ, entityType, entityId, AuditOutcome.DENIED));
    }

    private void record(AuditEntry entry) {
        ownTransaction.executeWithoutResult(status -> jdbc.execute((ConnectionCallback<Void>) connection -> {
            writer.write(connection, entry);
            return null;
        }));
    }
}
