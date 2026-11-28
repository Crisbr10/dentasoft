package es.crisbr.dentasoft.platform.persistence;

import es.crisbr.dentasoft.identity.ClinicRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.UuidGenerator;
import org.jspecify.annotations.Nullable;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * An {@code ADMIN_CLINICA} membership as the platform sees it (M02-T11, V013): the only memberships it can read, and
 * only the columns it may. It creates the clinic's first one, pending, and cancels it or makes it active again while it
 * is pending; the invited person accepts it through the application ({@code identity}), never the platform.
 *
 * <p>The mapping follows the grants of V013, so Hibernate never asks for more:
 *
 * <ul>
 *   <li>{@code tenant_id}, {@code user_id} and {@code role} are written once; {@code accepted_at} is only read;
 *   <li>the audit columns are written and never read: their {@code SELECT} is {@code NULL} instead of the column, which
 *       the platform may not select. Their getters are therefore absent;
 *   <li>an update writes {@code active}, {@code updated_at}, {@code updated_by} and {@code version}, nothing else.
 * </ul>
 */
@Entity(name = "PlatformAdminMembership")
@Table(name = "clinic_membership")
@EntityListeners(AuditingEntityListener.class)
public class PlatformAdminMembership {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private @Nullable UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID clinicId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, updatable = false, length = 20)
    private ClinicRole role = ClinicRole.ADMIN_CLINICA;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "accepted_at", insertable = false, updatable = false)
    private @Nullable OffsetDateTime acceptedAt;

    @Version
    @Column(name = "version", nullable = false)
    private @Nullable Long version;

    @CreatedDate
    @ColumnTransformer(read = "NULL")
    @Column(name = "created_at", nullable = false, updatable = false)
    private @Nullable Instant createdAt;

    @CreatedBy
    @ColumnTransformer(read = "NULL")
    @Column(name = "created_by", updatable = false)
    private @Nullable UUID createdBy;

    @LastModifiedDate
    @ColumnTransformer(read = "NULL")
    @Column(name = "updated_at", nullable = false)
    private @Nullable Instant updatedAt;

    @LastModifiedBy
    @ColumnTransformer(read = "NULL")
    @Column(name = "updated_by")
    private @Nullable UUID updatedBy;

    protected PlatformAdminMembership() {
        // For Hibernate.
        clinicId = new UUID(0L, 0L);
        userId = clinicId;
    }

    /** A new invitation of {@code userId} as administrator of {@code clinicId}: active and pending. */
    public PlatformAdminMembership(UUID clinicId, UUID userId) {
        this.clinicId = clinicId;
        this.userId = userId;
    }

    public @Nullable UUID getId() {
        return id;
    }

    public UUID getClinicId() {
        return clinicId;
    }

    public UUID getUserId() {
        return userId;
    }

    public ClinicRole getRole() {
        return role;
    }

    public boolean isActive() {
        return active;
    }

    /** Whether its user has accepted the invitation: then the platform can no longer change it. */
    public boolean isAccepted() {
        return acceptedAt != null;
    }

    public @Nullable Long getVersion() {
        return version;
    }

    /** Cancels the pending invitation: the membership stays, inactive, as every ended membership does (V007). */
    public void cancel() {
        this.active = false;
    }

    /** Invites the same person again, after cancelling: the membership becomes active and is still pending. */
    public void reactivate() {
        this.active = true;
    }
}
