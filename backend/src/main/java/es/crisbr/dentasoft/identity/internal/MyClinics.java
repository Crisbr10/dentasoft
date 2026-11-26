package es.crisbr.dentasoft.identity.internal;

import es.crisbr.dentasoft.identity.ClinicRole;
import es.crisbr.dentasoft.identity.internal.UserAccounts.ActiveMembership;
import es.crisbr.dentasoft.tenancy.ClinicDirectory;
import es.crisbr.dentasoft.tenancy.ClinicDirectory.ClinicSummary;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The clinics of the signed-in user, with their role in each: only their own active memberships, never anyone else's
 * (Row Level Security, V007). A suspended clinic is listed, marked as such, so the user knows why they cannot choose
 * it. Without active memberships, or with a disabled account, the list is empty: the user cannot work in any clinic.
 */
@Service
@PreAuthorize("isAuthenticated()")
public class MyClinics {

    private static final Comparator<MyClinic> BY_NAME =
            Comparator.comparing(MyClinic::name).thenComparing(MyClinic::clinicId);

    private final UserAccounts accounts;
    private final ClinicDirectory directory;

    MyClinics(UserAccounts accounts, ClinicDirectory directory) {
        this.accounts = accounts;
        this.directory = directory;
    }

    public record MyClinic(UUID clinicId, String name, ClinicRole role, boolean suspended) {}

    /** Sorted by name. */
    @Transactional(readOnly = true)
    public List<MyClinic> ofCurrentUser() {
        List<ActiveMembership> memberships = accounts.activeMembershipsOfCurrentUser();
        Map<UUID, ClinicSummary> clinics =
                directory
                        .visibleClinics(memberships.stream()
                                .map(ActiveMembership::clinicId)
                                .toList())
                        .stream()
                        .collect(Collectors.toMap(ClinicSummary::id, Function.identity()));
        return memberships.stream()
                .filter(membership -> clinics.containsKey(membership.clinicId()))
                .map(membership -> {
                    ClinicSummary clinic = clinics.get(membership.clinicId());
                    return new MyClinic(clinic.id(), clinic.name(), membership.role(), clinic.suspended());
                })
                .sorted(BY_NAME)
                .toList();
    }
}
