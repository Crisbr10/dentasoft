package es.crisbr.dentasoft.audit;

/** What an action meant, when its {@link AuditAction} alone does not say it (FHIR {@code AuditEvent.subtype}). */
public enum AuditSubtype {
    CLINIC_SUSPENDED,
    CLINIC_REACTIVATED
}
