package es.crisbr.dentasoft.tenancy;

import es.crisbr.dentasoft.shared.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.util.UUID;
import org.hibernate.annotations.TenantId;

/**
 * Base of every entity that belongs to a clinic. When an entity is saved, Hibernate fills {@code tenant_id} with the
 * active clinic of {@link TenantContext}, and it adds {@code tenant_id = <active clinic>} to every JPQL query,
 * {@code find()}, entity update and delete and JPQL update and delete, so no repository writes that filter by hand.
 *
 * <p>An entity cannot change its clinic: there is no setter and the column is not updatable. A different value set
 * by other means is ignored on update and rejected when a new entity is saved.
 *
 * <p>Only Row Level Security protects what Hibernate does not filter: native queries, {@code JdbcClient}, custom SQL
 * such as {@code @SQLDelete}, JPQL inserts and JPQL updates that assign {@code tenantId}.
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
