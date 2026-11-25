package es.crisbr.dentasoft.platform.persistence;

import es.crisbr.dentasoft.audit.AuditAction;
import es.crisbr.dentasoft.audit.AuditActorType;
import es.crisbr.dentasoft.audit.AuditDetails;
import es.crisbr.dentasoft.audit.AuditEntityType;
import es.crisbr.dentasoft.audit.AuditOutcome;
import es.crisbr.dentasoft.shared.RequestId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;
import org.jspecify.annotations.Nullable;

/**
 * An {@code audit_log} entry for what the platform does to a clinic, written in the same transaction as the change.
 * Insert-only, as everything in the log (V004).
 *
 * <p>{@code occurred_at} is not mapped: the database's clock sets it, and the platform may not. The platform has no
 * active clinic, so it names the affected clinic itself in {@code tenant_id}; its policy only
 * lets it write {@link AuditActorType#PLATFORM} entries. Not a {@code BaseEntity}: an entry is never updated, so it
 * has no {@code updated_*} columns and no version. The clinic's own entries are written by the audit module.
 */
@Entity(name = "PlatformAuditRecord")
@Table(name = "audit_log")
@Immutable
public class PlatformAuditRecord {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private @Nullable UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID clinicId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, updatable = false)
    private AuditActorType actorType = AuditActorType.PLATFORM;

    /** The Keycloak {@code sub} of the platform administrator: empty until M02. */
    @Column(name = "actor_id", updatable = false)
    private @Nullable UUID actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, updatable = false)
    private AuditAction action;

    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, updatable = false)
    private AuditEntityType entityType = AuditEntityType.CLINIC;

    @Column(name = "entity_id", nullable = false, updatable = false)
    private UUID entityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, updatable = false)
    private AuditOutcome outcome = AuditOutcome.SUCCESS;

    @Column(name = "request_id", updatable = false)
    private @Nullable UUID requestId;

    /** {@link AuditDetails#toJson()}: the only way anything reaches this column. */
    @Column(name = "details", updatable = false)
    @ColumnTransformer(write = "?::jsonb")
    private @Nullable String details;

    protected PlatformAuditRecord() {
        // For Hibernate.
        clinicId = new UUID(0L, 0L);
        action = AuditAction.READ;
        entityId = clinicId;
    }

    private PlatformAuditRecord(UUID clinicId, AuditAction action, @Nullable AuditDetails details) {
        this.clinicId = clinicId;
        this.action = action;
        this.entityId = clinicId;
        this.requestId = RequestId.serverGenerated().orElse(null);
        this.details = details == null ? null : details.toJson();
    }

    /** {@code action} done by the platform to clinic {@code clinicId}, recorded under that clinic. */
    public static PlatformAuditRecord ofClinic(UUID clinicId, AuditAction action, @Nullable AuditDetails details) {
        return new PlatformAuditRecord(clinicId, action, details);
    }

    public @Nullable UUID getId() {
        return id;
    }

    public UUID getClinicId() {
        return clinicId;
    }

    public AuditAction getAction() {
        return action;
    }

    public @Nullable String getDetails() {
        return details;
    }
}
