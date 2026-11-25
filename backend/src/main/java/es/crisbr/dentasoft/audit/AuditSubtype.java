package es.crisbr.dentasoft.audit;

/** What an action meant, when its {@link AuditAction} alone does not say it (FHIR {@code AuditEvent.subtype}). */
public enum AuditSubtype {
    CLINIC_SUSPENDED,
    CLINIC_REACTIVATED,
    /** The platform corrected the clinic's legal name or tax identifier (M01-T11); the values are not recorded. */
    CLINIC_FISCAL_DATA_CORRECTED
}
