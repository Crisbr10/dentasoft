package es.crisbr.dentasoft.audit.internal;

import es.crisbr.dentasoft.audit.AuditAction;
import es.crisbr.dentasoft.audit.AuditEntityType;
import es.crisbr.dentasoft.audit.AuditOutcome;
import es.crisbr.dentasoft.audit.AuditedEntity;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.event.spi.PostDeleteEvent;
import org.hibernate.event.spi.PostDeleteEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostInsertEventListener;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.event.spi.PostUpdateEventListener;
import org.hibernate.persister.entity.EntityPersister;

/**
 * Records the inserts, updates and deletes of {@link AuditedEntity} entities right after Hibernate writes each one,
 * during the flush: same connection, same transaction. If the entry cannot be written the flush fails, and the change
 * with it; if the transaction rolls back later, the entry goes too.
 *
 * <p>It sees only what Hibernate writes entity by entity: neither bulk JPQL nor native SQL (see {@link AuditedEntity}).
 */
final class AuditChangeListener implements PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener {

    private static final ClassValue<Optional<AuditEntityType>> AUDITED = new ClassValue<>() {
        @Override
        protected Optional<AuditEntityType> computeValue(Class<?> type) {
            return Optional.ofNullable(type.getAnnotation(AuditedEntity.class)).map(AuditedEntity::value);
        }
    };

    private final AuditLogWriter writer;

    AuditChangeListener(AuditLogWriter writer) {
        this.writer = writer;
    }

    @Override
    public void onPostInsert(PostInsertEvent event) {
        record(event.getPersister(), event.getSession(), event.getId(), AuditAction.CREATE);
    }

    @Override
    public void onPostUpdate(PostUpdateEvent event) {
        record(event.getPersister(), event.getSession(), event.getId(), AuditAction.UPDATE);
    }

    @Override
    public void onPostDelete(PostDeleteEvent event) {
        record(event.getPersister(), event.getSession(), event.getId(), AuditAction.DELETE);
    }

    private void record(
            EntityPersister persister, SharedSessionContractImplementor session, Object id, AuditAction action) {
        Optional<AuditEntityType> type = AUDITED.get(persister.getMappedClass());
        if (type.isEmpty()) {
            return;
        }
        AuditEntry entry = AuditEntry.of(action, type.get(), (UUID) id, AuditOutcome.SUCCESS);
        session.doWork(connection -> writer.write(connection, entry));
    }
}
