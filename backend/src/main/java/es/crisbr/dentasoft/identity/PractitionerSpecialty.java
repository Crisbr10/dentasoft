package es.crisbr.dentasoft.identity;

/**
 * The clinical area a practitioner works in at one clinic ({@code practitioner_role.specialty}, V012), as FHIR
 * {@code PractitionerRole.specialty}. A closed list, never free text.
 *
 * <p>Dentistry has no officially recognised specialties in Spain: user-facing texts call this «área clínica», never
 * «especialidad», so as not to suggest an official title.
 */
public enum PractitionerSpecialty {
    GENERAL_DENTISTRY,
    ORTHODONTICS,
    ENDODONTICS,
    PERIODONTICS,
    IMPLANTOLOGY,
    ORAL_SURGERY,
    PROSTHODONTICS,
    PEDIATRIC_DENTISTRY,
    /** The only area of a {@link ClinicRole#HIGIENISTA}, and never that of an {@link ClinicRole#ODONTOLOGO}. */
    DENTAL_HYGIENE;

    /** Whether a member with {@code role} may have a practitioner record in this area. */
    public boolean suits(ClinicRole role) {
        return switch (role) {
            case HIGIENISTA -> this == DENTAL_HYGIENE;
            case ODONTOLOGO -> this != DENTAL_HYGIENE;
            case ADMIN_CLINICA, RECEPCION, FACTURACION -> false;
        };
    }
}
