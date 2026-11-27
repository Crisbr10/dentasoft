package es.crisbr.dentasoft.identity.web;

import static es.crisbr.dentasoft.identity.web.InvitationRequest.PERSON_NAME;
import static es.crisbr.dentasoft.identity.web.InvitationRequest.PERSON_NAME_MESSAGE;
import static es.crisbr.dentasoft.identity.web.PractitionerRoleRequest.COLOR;
import static es.crisbr.dentasoft.identity.web.PractitionerRoleRequest.LICENSE_NUMBER;
import static es.crisbr.dentasoft.identity.web.PractitionerRoleRequest.PROVINCE;

import es.crisbr.dentasoft.identity.PractitionerSpecialty;
import es.crisbr.dentasoft.identity.internal.PractitionerRoles.PractitionerData;
import es.crisbr.dentasoft.shared.NoControlCharacters;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The whole new data of a practitioner record, with the same rules as {@link PractitionerRoleRequest}.
 *
 * @param userId the record's user: may be set if the record had none, and must then stay the same
 * @param version the version of the record the client read; the change is refused with 409 if it is no longer current
 */
public record UpdatePractitionerRoleRequest(
        @Nullable UUID userId,

        @NotBlank @NoControlCharacters @Pattern(regexp = PERSON_NAME, message = PERSON_NAME_MESSAGE) @Size(max = 100)
        String displayName,

        @NotNull PractitionerSpecialty specialty,

        @Nullable @Pattern(regexp = PROVINCE) String licenseProvince,

        @Nullable @Size(min = 3, max = 12) @Pattern(regexp = LICENSE_NUMBER)
        String licenseNumber,

        @NotNull @Pattern(regexp = COLOR) String agendaColor,

        @NotNull @PositiveOrZero Long version) {

    @AssertTrue(message = "{es.crisbr.dentasoft.identity.PractitionerLicense.complete}")
    boolean isLicenseComplete() {
        return PractitionerRoleRequest.licenseComplete(licenseProvince, licenseNumber);
    }

    @AssertTrue(message = "{es.crisbr.dentasoft.identity.PractitionerLicense.required}")
    boolean isLicenseGiven() {
        return PractitionerRoleRequest.licenseGiven(specialty, licenseNumber);
    }

    PractitionerData toData() {
        return PractitionerRoleRequest.data(
                userId, displayName, specialty, licenseProvince, licenseNumber, agendaColor);
    }

    /** Never prints the name or the licence number, should a request end up in a log. */
    @Override
    public String toString() {
        return "UpdatePractitionerRoleRequest[specialty=" + specialty + ", version=" + version + "]";
    }
}
