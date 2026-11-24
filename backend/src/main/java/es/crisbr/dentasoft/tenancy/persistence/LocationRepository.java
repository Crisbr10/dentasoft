package es.crisbr.dentasoft.tenancy.persistence;

import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Treatment rooms of the active clinic: Hibernate and Row Level Security only ever show its own.
 *
 * <p>Names are compared with {@code lower()}, as the unique index on active names does (V003).
 */
public interface LocationRepository extends JpaRepository<Location, UUID> {

    /** The properties a list of rooms may be sorted by. */
    Set<String> SORTABLE = Set.of("name", "status", "createdAt");

    Page<Location> findAllByStatus(LocationStatus status, Pageable pageable);

    @Query(
            "SELECT count(l) > 0 FROM Location l WHERE l.status = es.crisbr.dentasoft.tenancy.persistence.LocationStatus.ACTIVE"
                    + " AND lower(l.name) = lower(:name)")
    boolean existsActiveWithName(String name);

    @Query(
            "SELECT count(l) > 0 FROM Location l WHERE l.status = es.crisbr.dentasoft.tenancy.persistence.LocationStatus.ACTIVE"
                    + " AND lower(l.name) = lower(:name) AND l.id <> :id")
    boolean existsOtherActiveWithName(String name, UUID id);
}
