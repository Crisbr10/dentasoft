package es.crisbr.dentasoft.identity.internal;

import es.crisbr.dentasoft.identity.ClinicRole;
import es.crisbr.dentasoft.identity.internal.keycloak.KeycloakUser;
import es.crisbr.dentasoft.identity.persistence.ClinicMembership;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A user of the active clinic: their membership, from the database, and their profile, from Keycloak.
 *
 * @param id the membership's id, which the other operations take
 * @param version the membership's version, sent back to change its role or deactivate it
 * @param profile empty if Keycloak did not answer, or no longer knows the user
 */
public record ClinicUserDetails(
        UUID id,
        UUID userId,
        ClinicRole role,
        boolean active,
        long version,
        @Nullable Profile profile) {

    /**
     * What Keycloak knows of the person.
     *
     * @param invitationPending they have not set up their account yet (email, password and one-time code)
     */
    public record Profile(
            String email,
            @Nullable String firstName,
            @Nullable String lastName,
            boolean invitationPending) {

        static Profile of(KeycloakUser user) {
            return new Profile(user.email(), user.firstName(), user.lastName(), user.invitationPending());
        }

        /** Never prints the email or the name, should a profile end up in a log. */
        @Override
        public String toString() {
            return "Profile[invitationPending=" + invitationPending + "]";
        }
    }

    static ClinicUserDetails of(ClinicMembership membership, @Nullable Profile profile) {
        return new ClinicUserDetails(
                membership.getId(),
                membership.getUserId(),
                membership.getRole(),
                membership.isActive(),
                membership.getVersion() == null ? 0 : membership.getVersion(),
                profile);
    }
}
