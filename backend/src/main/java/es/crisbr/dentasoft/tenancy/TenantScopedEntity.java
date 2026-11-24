package es.crisbr.dentasoft.tenancy;

import es.crisbr.dentasoft.shared.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * Base of every entity that belongs to a clinic. Hibernate fills {@code tenant_id} with the active clinic of
 * {@link TenantContext} on insert and adds {@code tenant_id = <active clinic>} to every JPA query, {@code find()},
 * update and delete, so no repository writes that filter by hand.
 *
 * <p>The clinic cannot be changed: there is no setter and the column is not updatable. A different value set by
 * other means is ignored on update and rejected on insert.
 *
 * <p>Native queries, {@code JdbcClient} and custom SQL such as {@code @SQLDelete} are not filtered by Hibernate:
 * there, only Row Level Security protects the data.
 */
@MappedSuperclass
public abstract class TenantScopedEntity extends BaseEntity {

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    public UUID getTenantId() {
        return tenantId;
    }
}
