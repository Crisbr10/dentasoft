package es.crisbr.dentasoft.tenancy.persistence;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/**
 * The active clinic. Row Level Security only ever shows its own row; Hibernate adds no filter (see {@link Clinic}), so
 * the services always look it up by the id of {@code TenantContext}.
 */
public interface ClinicRepository extends JpaRepository<Clinic, UUID> {

    /**
     * The clinic, locked until the transaction ends ({@code SELECT … FOR UPDATE}): changes that must not run at the
     * same time for one clinic, such as replacing its opening hours, take this lock first.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Clinic c WHERE c.id = :id")
    Optional<Clinic> lockById(UUID id);
}
