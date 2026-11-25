package es.crisbr.dentasoft.audit;

/**
 * How it ended, as FHIR {@code AuditEvent.outcome}. A change is only ever recorded as {@link #SUCCESS}: it is recorded
 * in its own transaction, so a change that fails takes its entry with it.
 */
public enum AuditOutcome {
    SUCCESS,
    /** The caller was not allowed to do it ({@code @PreAuthorize}). */
    DENIED
}
