package es.crisbr.dentasoft.tenancy;

import es.crisbr.dentasoft.shared.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * A deliberately broken entity, only for {@link TenantIsolationTestMutationTests}: {@code tenancy_test_record} mapped
 * as if its entity had lost {@code @TenantId}, with a plain {@code tenant_id} column and no {@link TenantScopedEntity}.
 * It is only read and changed there, never used to insert.
 */
@Entity
@Table(name = "tenancy_test_record")
class TenancyTestRecordWithoutTenantId extends BaseEntity {

    @Column(name = "tenant_id", nullable = false, insertable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "label", nullable = false)
    private String label;

    protected TenancyTestRecordWithoutTenantId() {}

    UUID getTenantId() {
        return tenantId;
    }

    String getLabel() {
        return label;
    }

    void setLabel(String label) {
        this.label = label;
    }
}
