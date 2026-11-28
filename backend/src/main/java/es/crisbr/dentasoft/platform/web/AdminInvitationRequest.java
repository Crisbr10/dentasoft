package es.crisbr.dentasoft.platform.web;

import es.crisbr.dentasoft.platform.internal.FirstAdminInvitations.NewAdministrator;
import es.crisbr.dentasoft.shared.NoControlCharacters;
import es.crisbr.dentasoft.shared.PersonNames;
import es.crisbr.dentasoft.shared.StrictEmail;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;

/** The first administrator of a clinic, as the platform administrator types them (M02-T11). The role is always ADMIN_CLINICA. */
record AdminInvitationRequest(
        @NotBlank @StrictEmail String email,

        @NotBlank
        @NoControlCharacters
        @Pattern(regexp = PersonNames.PATTERN, message = PersonNames.MESSAGE)
        @Size(max = 100)
        String firstName,

        @NotBlank
        @NoControlCharacters
        @Pattern(regexp = PersonNames.PATTERN, message = PersonNames.MESSAGE)
        @Size(max = 100)
        String lastName) {

    NewAdministrator toNewAdministrator() {
        return new NewAdministrator(email.toLowerCase(Locale.ROOT), firstName.strip(), lastName.strip());
    }

    /** Never prints the email or the name, should a request end up in a log. */
    @Override
    public String toString() {
        return "AdminInvitationRequest[…]";
    }
}
