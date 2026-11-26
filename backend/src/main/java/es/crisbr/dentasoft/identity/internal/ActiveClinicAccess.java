package es.crisbr.dentasoft.identity.internal;

import es.crisbr.dentasoft.identity.ClinicRole;
import es.crisbr.dentasoft.tenancy.ClinicDirectory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Whether the signed-in user may act as a clinic, and with which role: an active membership, an active account and a
 * clinic that is not suspended. Read from the database on every call, in a read-only transaction of its own, on the
 * application's connection ({@code dentasoft_app} with {@code app.user_id}): Row Level Security shows only the user's
 * own memberships ({@code own_memberships}) and the clinics they belong to ({@code member_select}), so a mistake here
 * still could not let a user into someone else's clinic. Never the platform's connection.
 *
 * <p>Nothing is cached, so an ended membership, a disabled account or a suspended clinic is refused from the next
 * request on. It costs two lookups by key per request.
 */
@Service
@PreAuthorize("isAuthenticated()")
class ActiveClinicAccess {

    private final UserAccounts accounts;
    private final ClinicDirectory clinics;

    ActiveClinicAccess(UserAccounts accounts, ClinicDirectory clinics) {
        this.accounts = accounts;
        this.clinics = clinics;
    }

    /** The user's role in {@code clinicId}; empty when they may not act as it, whatever the reason. */
    @Transactional(readOnly = true)
    public Optional<ClinicRole> roleIn(UUID clinicId) {
        Optional<ClinicRole> role = accounts.activeRoleIn(clinicId);
        if (role.isEmpty()) {
            return role;
        }
        boolean usable = clinics.visibleClinics(List.of(clinicId)).stream()
                .anyMatch(clinic -> clinic.id().equals(clinicId) && !clinic.suspended());
        return usable ? role : Optional.empty();
    }
}
