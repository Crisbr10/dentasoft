package es.crisbr.dentasoft.audit;

/**
 * What kind of record an entry is about, as FHIR {@code AuditEvent.entity.what}. Stored by name in {@code
 * audit_log.entity_type}, which accepts only upper-case codes: each module adds its own entities here.
 */
public enum AuditEntityType {
    CLINIC,
    CLINIC_SETTINGS,
    LOCATION,
    HOURS_OF_OPERATION,
    CLINIC_MEMBERSHIP
}
