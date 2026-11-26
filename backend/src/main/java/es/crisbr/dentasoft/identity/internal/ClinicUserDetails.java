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
 * @param invitationPending the person has not accepted the invitation yet; until then the membership grants nothing,
 *     and the profile only has the email, the same for everyone invited (ADR 0016)
 * @param version the membership's version, sent back to change its role or deactivate it
 * @param profile empty if Keycloak did not answer, or no longer knows the user
 */
public record ClinicUserDetails(
        UUID id,
        UUID userId,
        ClinicRole role,
        boolean active,
        boolean invitationPending,
        long version,
        @Nullable Profile profile) {

    /**
     * What Keycloak knows of the person. While the invitation is pending, only the email the administrator typed:
     * names and whether the person had already set up their account would tell if they use Dentasoft elsewhere.
     */
    public record Profile(
            String email,
            @Nullable String firstName,
            @Nullable String lastName) {

        static Profile of(KeycloakUser user, boolean accepted) {
            return accepted
                    ? new Profile(user.email(), user.firstName(), user.lastName())
                    : new Profile(user.email(), null, null);
        }

        /** Never prints the email or the name, should a profile end up in a log. */
        @Override
        public String toString() {
            return "Profile[]";
        }
    }

    static ClinicUserDetails of(ClinicMembership membership, @Nullable Profile profile) {
        return new ClinicUserDetails(
                membership.getId(),
                membership.getUserId(),
                membership.getRole(),
                membership.isActive(),
                !membership.isAccepted(),
                membership.getVersion() == null ? 0 : membership.getVersion(),
                profile);
    }
}
