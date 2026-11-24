package es.crisbr.dentasoft.platform.persistence;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

/**
 * Clinics and their initial settings in the platform unit. Plain JPA rather than Spring Data: declaring Spring Data
 * repositories for a second unit would switch off Spring Boot's auto-configured ones for the main unit.
 *
 * <p>Must run inside a transaction of the platform transaction manager: outside it, the shared entity manager has no
 * platform transaction to join.
 */
@Repository
public class PlatformClinics {

    /** Sortable properties, by the name the API uses. Anything else is rejected, never passed to the query. */
    public static final Map<String, String> SORTABLE = Map.of(
            "name", "name",
            "legalName", "legalName",
            "taxId", "taxId",
            "status", "status",
            "createdAt", "createdAt");

    @PersistenceContext(unitName = "platform")
    private EntityManager entityManager;

    /** Assigns the clinic its id (UUID v7). Written on the next {@link #flush()} or at commit. */
    public void add(PlatformClinic clinic) {
        entityManager.persist(clinic);
    }

    public void add(PlatformClinicSettings settings) {
        entityManager.persist(settings);
    }

    /** Writes pending changes now, so a constraint violation surfaces here and not at commit. */
    public void flush() {
        entityManager.flush();
    }

    public Optional<PlatformClinic> find(UUID id) {
        return Optional.ofNullable(entityManager.find(PlatformClinic.class, id));
    }

    /** One page, ordered as {@code pageable} says and then by id, so pages never overlap. */
    public Page<PlatformClinic> page(Pageable pageable) {
        CriteriaBuilder criteria = entityManager.getCriteriaBuilder();

        CriteriaQuery<PlatformClinic> select = criteria.createQuery(PlatformClinic.class);
        Root<PlatformClinic> clinic = select.from(PlatformClinic.class);
        List<Order> order = new ArrayList<>();
        for (Sort.Order by : pageable.getSort()) {
            String property = SORTABLE.get(by.getProperty());
            if (property == null) {
                throw new IllegalArgumentException("Not a sortable property: " + by.getProperty());
            }
            order.add(by.isAscending() ? criteria.asc(clinic.get(property)) : criteria.desc(clinic.get(property)));
        }
        order.add(criteria.asc(clinic.get("id")));
        select.orderBy(order);
        List<PlatformClinic> content = entityManager
                .createQuery(select)
                .setFirstResult(Math.toIntExact(pageable.getOffset()))
                .setMaxResults(pageable.getPageSize())
                .getResultList();

        CriteriaQuery<Long> count = criteria.createQuery(Long.class);
        count.select(criteria.count(count.from(PlatformClinic.class)));
        long total = entityManager.createQuery(count).getSingleResult();

        return new PageImpl<>(content, pageable, total);
    }
}
