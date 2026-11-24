package es.crisbr.dentasoft.tenancy.persistence;

/** Whether a treatment room is in use, as FHIR {@code Location.status} ({@code active}, {@code inactive}). */
public enum LocationStatus {
    ACTIVE,
    INACTIVE
}
