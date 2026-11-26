package es.crisbr.dentasoft.identity.web;

import es.crisbr.dentasoft.identity.ClinicRole;
import es.crisbr.dentasoft.identity.internal.ClinicUsers.Invitation;
import es.crisbr.dentasoft.shared.NoControlCharacters;
import es.crisbr.dentasoft.shared.StrictEmail;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Locale;

/**
 * Inviting someone to the active clinic. There is no password: the person chooses it from the invitation email.
 *
 * @param email where the invitation goes; compared without case, as Keycloak stores it
 * @param firstName given name, as the person will see it
 * @param lastName family names
 */
public record InvitationRequest(
        @NotBlank @StrictEmail String email,

        @NotBlank @NoControlCharacters @Pattern(regexp = PERSON_NAME, message = PERSON_NAME_MESSAGE) @Size(max = 100)
        String firstName,

        @NotBlank @NoControlCharacters @Pattern(regexp = PERSON_NAME, message = PERSON_NAME_MESSAGE) @Size(max = 100)
        String lastName,

        @NotNull ClinicRole role) {

    /**
     * At least one letter, and no format characters (U+200B zero-width space, U+202E right-to-left override…), which
     * {@code @NoControlCharacters} lets through: a name of only Unicode spaces would be blank once stripped, and format
     * characters can disguise what a name looks like.
     */
    static final String PERSON_NAME = "(?=.*\\p{L})[^\\p{Cf}]*";

    static final String PERSON_NAME_MESSAGE = "{es.crisbr.dentasoft.identity.PersonName.message}";

    Invitation toInvitation() {
        return new Invitation(email.toLowerCase(Locale.ROOT), firstName.strip(), lastName.strip(), role);
    }

    /** Never prints the email or the name, should a request end up in a log. */
    @Override
    public String toString() {
        return "InvitationRequest[role=" + role + "]";
    }
}
