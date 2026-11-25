package es.crisbr.dentasoft.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Records a read of one record in {@code audit_log}: the method's parameter marked {@link AuditedEntityId} says which.
 *
 * <p>The interceptor runs outside {@code @PreAuthorize} and {@code @Transactional}: the read finishes and returns its
 * connection, and then the entry is written in a transaction of its own. If the entry cannot be written, the method
 * throws and its result is never returned. A read that {@code @PreAuthorize} denies is recorded as {@link
 * AuditOutcome#DENIED}.
 *
 * <p>Called from inside a transaction that is already open, the entry is written in a new transaction ({@code
 * REQUIRES_NEW}) while the caller's one waits with its connection: that caller holds two connections for a moment
 * (ADR 0012).
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditedRead {

    AuditEntityType value();
}
