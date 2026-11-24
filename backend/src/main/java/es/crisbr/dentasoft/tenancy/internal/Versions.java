package es.crisbr.dentasoft.tenancy.internal;

import es.crisbr.dentasoft.shared.BaseEntity;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Optimistic locking of what the clinic edits. {@code @Version} alone only compares the version that was loaded, inside
 * the same transaction: the version the client read, maybe minutes earlier, has to be compared by hand.
 */
final class Versions {

    private Versions() {}

    /** @throws VersionConflictException if {@code entity} is no longer at the version the client read */
    static void require(BaseEntity entity, long expected) {
        Long current = entity.getVersion();
        if (current == null || current != expected) {
            throw new VersionConflictException();
        }
    }

    /**
     * Writes the pending changes now, so that a concurrent change made between the read and this write becomes a 409
     * here instead of a 500 at commit.
     */
    static void flush(JpaRepository<?, ?> repository) {
        try {
            repository.flush();
        } catch (OptimisticLockingFailureException concurrentChange) {
            throw new VersionConflictException();
        }
    }
}
