package es.crisbr.dentasoft.audit;

/** Who did it, as FHIR {@code AuditEvent.agent.type}. */
public enum AuditActorType {
    /** A user of the clinic, identified by the Keycloak {@code sub} (from M02). */
    USER,
    /** The application itself, without a user: jobs and webhooks (M01-T08), and everything until M02. */
    SYSTEM,
    /** The platform administration ({@code PLATFORM_ADMIN}, ADR 0011). */
    PLATFORM
}
