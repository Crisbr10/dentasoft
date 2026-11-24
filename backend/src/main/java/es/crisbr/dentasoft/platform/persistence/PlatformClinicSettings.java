package es.crisbr.dentasoft.platform.persistence;

import es.crisbr.dentasoft.shared.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/**
 * The initial {@code clinic_settings} row of a new clinic, written by the platform in the same transaction as the
 * clinic. Insert-only: it maps only the id, the clinic and the audit and version columns, the ones {@code
 * dentasoft_platform} may insert (V002); every setting takes its column default. The clinic's own mapping, which reads
 * and edits it, is {@code ClinicSettings} in the tenancy module.
 *
 * <p>{@code tenant_id} is an ordinary column here, not a {@code TenantScopedEntity}: the platform runs without an
 * active clinic and names the clinic explicitly.
 */
@Entity(name = "PlatformClinicSettings")
@Table(name = "clinic_settings")
@Immutable
public class PlatformClinicSettings extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID clinicId;

    protected PlatformClinicSettings() {
        // For Hibernate.
        clinicId = new UUID(0L, 0L);
    }

    public PlatformClinicSettings(UUID clinicId) {
        this.clinicId = clinicId;
    }

    public UUID getClinicId() {
        return clinicId;
    }
}
