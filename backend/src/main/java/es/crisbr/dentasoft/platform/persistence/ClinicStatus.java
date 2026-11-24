package es.crisbr.dentasoft.platform.persistence;

/** Values of {@code clinic.status} (its CHECK constraint, V001). */
public enum ClinicStatus {
    ACTIVE,
    /** Nobody can sign in to it (M02-T04); its data stays. */
    SUSPENDED
}
