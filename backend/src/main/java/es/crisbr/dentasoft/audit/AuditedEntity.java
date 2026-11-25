package es.crisbr.dentasoft.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Records every insert, update and delete of this entity in {@code audit_log}, in the same transaction and on the same
 * connection as the change: if the entry cannot be written, the change fails; if the change rolls back, so does its
 * entry.
 *
 * <p>Only what Hibernate writes entity by entity is seen. Bulk {@code UPDATE} or {@code DELETE} in JPQL ({@code
 * @Modifying} queries, {@code deleteAllInBatch}) and native SQL bypass it, so the repositories of these entities must
 * not use them ({@code AuditedRepositoriesTests}, ADR 0012).
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditedEntity {

    AuditEntityType value();
}
