package es.crisbr.dentasoft.identity.persistence;

import es.crisbr.dentasoft.identity.ClinicRole;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/**
 * The memberships of the active clinic. Hibernate filters every query by the active clinic, so a user's memberships
 * in other clinics are read with SQL instead ({@code identity.internal.UserAccounts}).
 */
public interface ClinicMembershipRepository extends JpaRepository<ClinicMembership, UUID> {

    /** The properties the list of the clinic's users may be sorted by. Names live in Keycloak, so they are not here. */
    Set<String> SORTABLE = Set.of("role", "active", "createdAt");

    /** The user's membership of the active clinic, active or not: there is at most one (V007). */
    Optional<ClinicMembership> findByUserId(UUID userId);

    /**
     * The same membership, locked for sharing ({@code SELECT … FOR SHARE}) until the transaction ends: it cannot be
     * changed or deactivated meanwhile, as while checking that its user may have a practitioner record (M02-T06).
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT m FROM ClinicMembership m WHERE m.userId = :userId")
    Optional<ClinicMembership> lockSharedByUserId(UUID userId);

    /**
     * The active clinic's active and accepted memberships with {@code role}, locked ({@code SELECT … FOR UPDATE}) until the
     * transaction ends: two changes that each check how many administrators remain run one after the other.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM ClinicMembership m WHERE m.role = :role AND m.active = true AND m.acceptedAt IS NOT NULL"
            + " ORDER BY m.id")
    List<ClinicMembership> lockActiveWithRole(ClinicRole role);
}
