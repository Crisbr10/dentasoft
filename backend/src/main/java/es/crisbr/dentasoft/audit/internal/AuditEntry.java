package es.crisbr.dentasoft.audit.internal;

import es.crisbr.dentasoft.audit.AuditAction;
import es.crisbr.dentasoft.audit.AuditActorType;
import es.crisbr.dentasoft.audit.AuditEntityType;
import es.crisbr.dentasoft.audit.AuditOutcome;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One entry of {@code audit_log}, before it is written. The clinic is not here: the database takes it from the
 * transaction (V004).
 */
public record AuditEntry(
        AuditActorType actorType,
        @Nullable UUID actorId,
        AuditAction action,
        AuditEntityType entityType,
        UUID entityId,
        AuditOutcome outcome) {

    /** By whoever is acting now ({@link CurrentActor}). */
    public static AuditEntry of(AuditAction action, AuditEntityType entityType, UUID entityId, AuditOutcome outcome) {
        CurrentActor actor = CurrentActor.get();
        return new AuditEntry(actor.type(), actor.id(), action, entityType, entityId, outcome);
    }
}
