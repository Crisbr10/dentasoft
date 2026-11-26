package es.crisbr.dentasoft.identity.web;

import static es.crisbr.dentasoft.identity.web.InvitationRequest.PERSON_NAME;
import static es.crisbr.dentasoft.identity.web.InvitationRequest.PERSON_NAME_MESSAGE;

import es.crisbr.dentasoft.identity.PractitionerSpecialty;
import es.crisbr.dentasoft.identity.internal.PractitionerRoles.PractitionerData;
import es.crisbr.dentasoft.shared.NoControlCharacters;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A new practitioner record of the active clinic.
 *
 * @param userId the user of the person, a member of the clinic as {@code ODONTOLOGO} or {@code HIGIENISTA}; missing
 *     for someone who never signs in
 * @param displayName how the clinic names the practitioner in its calendar and documents
 * @param specialty the clinical area («área clínica»)
 * @param licenseProvince INE code (01–52) of the province of the college the licence number belongs to
 * @param licenseNumber the licence number (número de colegiado), kept in upper case; required except for {@code
 *     DENTAL_HYGIENE}, and unique among the clinic's active records of the same province
 * @param agendaColor colour in the calendar, as {@code #RRGGBB} in upper case
 */
public record PractitionerRoleRequest(
        @Nullable UUID userId,

        @NotBlank @NoControlCharacters @Pattern(regexp = PERSON_NAME, message = PERSON_NAME_MESSAGE) @Size(max = 100)
        String displayName,

        @NotNull PractitionerSpecialty specialty,

        @Nullable @Pattern(regexp = PROVINCE) String licenseProvince,

        @Nullable @Size(min = 3, max = 12) @Pattern(regexp = LICENSE_NUMBER)
        String licenseNumber,

        @NotNull @Pattern(regexp = COLOR) String agendaColor) {

    /** The INE codes of the 50 provinces, Ceuta (51) and Melilla (52). */
    static final String PROVINCE = "0[1-9]|[1-4][0-9]|5[0-2]";

    /**
     * Letters and digits, with at most one {@code -} or {@code /} between them: colleges number their members in their
     * own way, and there is no national format to check against.
     */
    static final String LICENSE_NUMBER = "[0-9A-Za-z]+([-/][0-9A-Za-z]+)?";

    static final String COLOR = "#[0-9A-F]{6}";

    @AssertTrue(message = "{es.crisbr.dentasoft.identity.PractitionerLicense.complete}")
    boolean isLicenseComplete() {
        return licenseComplete(licenseProvince, licenseNumber);
    }

    @AssertTrue(message = "{es.crisbr.dentasoft.identity.PractitionerLicense.required}")
    boolean isLicenseGiven() {
        return licenseGiven(specialty, licenseNumber);
    }

    PractitionerData toData() {
        return data(userId, displayName, specialty, licenseProvince, licenseNumber, agendaColor);
    }

    static boolean licenseComplete(@Nullable String province, @Nullable String number) {
        return (province == null) == (number == null);
    }

    /** A missing area is reported by {@code @NotNull} alone. */
    static boolean licenseGiven(@Nullable PractitionerSpecialty specialty, @Nullable String number) {
        return specialty == null || specialty == PractitionerSpecialty.DENTAL_HYGIENE || number != null;
    }

    static PractitionerData data(
            @Nullable UUID userId,
            String displayName,
            PractitionerSpecialty specialty,
            @Nullable String licenseProvince,
            @Nullable String licenseNumber,
            String agendaColor) {
        return new PractitionerData(
                userId,
                displayName.strip(),
                Objects.requireNonNull(specialty),
                licenseProvince,
                licenseNumber == null ? null : licenseNumber.toUpperCase(Locale.ROOT),
                agendaColor);
    }

    /** Never prints the name or the licence number, should a request end up in a log. */
    @Override
    public String toString() {
        return "PractitionerRoleRequest[specialty=" + specialty + "]";
    }
}
