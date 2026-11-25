package es.crisbr.dentasoft.audit;

/** What was done, as FHIR {@code AuditEvent.action} (C, R, U, D). Stored by name in {@code audit_log.action}. */
public enum AuditAction {
    CREATE,
    READ,
    UPDATE,
    DELETE
}
