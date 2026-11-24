package es.crisbr.dentasoft.tenancy;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository of the deliberately broken {@link TenancyTestRecordWithoutTenantId}. */
interface TenancyTestRecordWithoutTenantIdRepository extends JpaRepository<TenancyTestRecordWithoutTenantId, UUID> {}
