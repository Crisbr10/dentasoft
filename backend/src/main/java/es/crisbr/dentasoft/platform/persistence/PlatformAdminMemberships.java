package es.crisbr.dentasoft.platform.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * The administrators' memberships of the clinics, in the platform unit (M02-T11). Plain JPA, as {@link PlatformClinics}.
 * PostgreSQL only shows the platform {@code ADMIN_CLINICA} memberships (V013), so every query here returns those, also
 * without saying so.
 *
 * <p>Must run inside a transaction of the platform transaction manager.
 */
@Repository
public class PlatformAdminMemberships {

    @PersistenceContext(unitName = "platform")
    private EntityManager entityManager;

    /** The clinic's administrators' memberships, active or not, accepted or pending, oldest first. */
    public List<PlatformAdminMembership> ofClinic(UUID clinicId) {
        return entityManager
                .createQuery(
                        "SELECT m FROM PlatformAdminMembership m WHERE m.clinicId = :clinic ORDER BY m.id",
                        PlatformAdminMembership.class)
                .setParameter("clinic", clinicId)
                .getResultList();
    }

    /**
     * Creates {@code userId}'s account unless it exists, in the name of the platform administrator
     * ({@code create_invited_user_account}, V008): the membership references it.
     */
    public void createAccount(UUID userId) {
        entityManager
                .createNativeQuery("SELECT 1 FROM create_invited_user_account(:id)")
                .setParameter("id", userId)
                .getSingleResult();
    }

    /** Assigns the membership its id (UUID v7). Written on the next {@link #flush()} or at commit. */
    public void add(PlatformAdminMembership membership) {
        entityManager.persist(membership);
    }

    /**
     * Written on the next {@link #flush()}. Flush the membership first: PostgreSQL checks that it exists, and Hibernate
     * may reorder inserts of different entities.
     */
    public void add(PlatformAuditRecord record) {
        entityManager.persist(record);
    }

    /** Writes pending changes now, so the database's refusal surfaces here and not at commit. */
    public void flush() {
        entityManager.flush();
    }
}
