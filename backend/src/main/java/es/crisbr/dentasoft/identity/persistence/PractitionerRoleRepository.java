package es.crisbr.dentasoft.identity.persistence;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/** Practitioner records of the active clinic: Hibernate and Row Level Security only ever show its own. */
public interface PractitionerRoleRepository extends JpaRepository<PractitionerRole, UUID> {

    /** The properties a list of practitioners may be sorted by. */
    Set<String> SORTABLE = Set.of("displayName", "specialty", "status", "createdAt");

    Page<PractitionerRole> findAllByStatus(PractitionerRoleStatus status, Pageable pageable);

    /** The active records, by name and then id, so that names that repeat keep a fixed order. */
    List<PractitionerRole> findAllByStatusOrderByDisplayNameAscIdAsc(PractitionerRoleStatus status);

    List<PractitionerRole> findAllByIdIn(Collection<UUID> ids);

    /**
     * The record with {@code id}, locked for sharing ({@code SELECT … FOR SHARE}) until the transaction ends: it cannot
     * be deactivated meanwhile, as while giving the practitioner a new appointment (M06).
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT p FROM PractitionerRole p WHERE p.id = :id")
    Optional<PractitionerRole> lockSharedById(UUID id);

    /** The record of {@code userId} in the active clinic, active or not: there is at most one (V012). */
    Optional<PractitionerRole> findByUserId(UUID userId);

    @Query("SELECT count(p) > 0 FROM PractitionerRole p"
            + " WHERE p.status = es.crisbr.dentasoft.identity.persistence.PractitionerRoleStatus.ACTIVE"
            + " AND p.licenseProvince = :province AND p.licenseNumber = :number")
    boolean existsActiveWithLicense(String province, String number);

    @Query("SELECT count(p) > 0 FROM PractitionerRole p"
            + " WHERE p.status = es.crisbr.dentasoft.identity.persistence.PractitionerRoleStatus.ACTIVE"
            + " AND p.licenseProvince = :province AND p.licenseNumber = :number AND p.id <> :id")
    boolean existsOtherActiveWithLicense(String province, String number, UUID id);
}
