package es.crisbr.dentasoft.identity.persistence;

import es.crisbr.dentasoft.audit.AuditEntityType;
import es.crisbr.dentasoft.audit.AuditedEntity;
import es.crisbr.dentasoft.identity.ClinicRole;
import es.crisbr.dentasoft.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * What a user is in the active clinic ({@code clinic_membership}, V007): one role per clinic. Memberships come with the
 * invitations of M02-T05; no one can create their own. Never deleted: a membership that ends is made inactive, and an
 * inactive membership grants nothing until an administrator invites the person again.
 *
 * <p>Every insert, change of role and deactivation is recorded in {@code audit_log}.
 */
@Entity
@AuditedEntity(AuditEntityType.CLINIC_MEMBERSHIP)
@Table(name = "clinic_membership")
public class ClinicMembership extends TenantScopedEntity {

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private ClinicRole role;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected ClinicMembership() {}

    public ClinicMembership(UUID userId, ClinicRole role) {
        this.userId = userId;
        this.role = role;
    }

    public UUID getUserId() {
        return userId;
    }

    public ClinicRole getRole() {
        return role;
    }

    public void setRole(ClinicRole role) {
        this.role = role;
    }

    public boolean isActive() {
        return active;
    }

    public void deactivate() {
        this.active = false;
    }

    /** Makes an inactive membership active again, with {@code role}: what inviting the person again does (M02-T05). */
    public void reactivate(ClinicRole role) {
        this.role = role;
        this.active = true;
    }
}
