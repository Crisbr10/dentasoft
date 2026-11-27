package es.crisbr.dentasoft.identity.internal;

import es.crisbr.dentasoft.identity.ClinicRole;
import es.crisbr.dentasoft.identity.persistence.ClinicMembership;
import es.crisbr.dentasoft.identity.persistence.ClinicMembershipRepository;
import es.crisbr.dentasoft.identity.persistence.PractitionerRoleRepository;
import es.crisbr.dentasoft.shared.CurrentUser;
import es.crisbr.dentasoft.shared.PageLimits;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The database side of managing the active clinic's users: each method is one transaction, and none calls Keycloak
 * ({@link ClinicUsers} does that outside them). Every change of a membership is recorded in {@code audit_log} by its
 * entity.
 *
 * <p>Changes that could leave the clinic without an administrator first lock its active {@code ADMIN_CLINICA}
 * memberships ({@link ClinicMembershipRepository#lockActiveWithRole}), then check that the user making the change is
 * still one of them. Two administrators demoting each other at the same time therefore run one after the other, and
 * the second, no longer an administrator, is refused: the clinic always keeps one.
 *
 * <p>A change of role or a deactivation also deactivates the user's practitioner record when the membership no
 * longer allows it ({@link PractitionerRoles#followMembership}), in the same transaction.
 */
@Service
@PreAuthorize("hasRole('ADMIN_CLINICA')")
class ClinicMemberships {

    /** Each user of a page costs one request to Keycloak for their profile ({@link ClinicUsers#list}). */
    static final int MAX_PAGE_SIZE = 50;

    private static final String UNIQUE_VIOLATION = "23505";

    private final ClinicMembershipRepository memberships;
    private final PractitionerRoleRepository practitioners;
    private final JdbcClient jdbc;

    ClinicMemberships(
            ClinicMembershipRepository memberships, PractitionerRoleRepository practitioners, JdbcClient jdbc) {
        this.memberships = memberships;
        this.practitioners = practitioners;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    Page<ClinicMembership> list(Pageable pageable) {
        Pageable requested = PageLimits.bounded(pageable, ClinicMembershipRepository.SORTABLE);
        // The id last: roles and dates repeat, and rows that tie have no fixed order, so pages could overlap.
        return memberships.findAll(PageRequest.of(
                requested.getPageNumber(),
                Math.min(requested.getPageSize(), MAX_PAGE_SIZE),
                requested.getSort().and(Sort.by("id"))));
    }

    /** @throws MembershipNotFoundException if the active clinic has no membership with {@code id} */
    @Transactional(readOnly = true)
    ClinicMembership get(UUID id) {
        return find(id);
    }

    /**
     * Gives {@code userId} an active membership of the active clinic with {@code role}: a new one, or their inactive
     * one made active again. Creates their account first if it does not exist yet (V008); an existing account, even
     * a disabled one, is left as it is.
     *
     * @throws AlreadyMemberException if the user already has an active membership of the clinic
     */
    @Transactional
    ClinicMembership addOrReactivate(UUID userId, ClinicRole role) {
        // Not INSERT … ON CONFLICT DO NOTHING: PostgreSQL refuses it when the existing account is another user's,
        // invisible to the application (V008).
        jdbc.sql("SELECT create_invited_user_account(:id)")
                .param("id", userId)
                .query()
                .singleRow();
        Optional<ClinicMembership> existing = memberships.findByUserId(userId);
        if (existing.isPresent()) {
            ClinicMembership membership = existing.get();
            if (membership.isActive()) {
                throw new AlreadyMemberException();
            }
            membership.reactivate(role);
            flush();
            return membership;
        }
        ClinicMembership membership = new ClinicMembership(userId, role);
        try {
            memberships.saveAndFlush(membership);
        } catch (DataIntegrityViolationException violation) {
            // UNIQUE (tenant_id, user_id), the only unique key the insert can hit (the id is new): the same person
            // was invited at the same time by another request.
            if (isUniqueViolation(violation)) {
                throw new AlreadyMemberException();
            }
            throw violation;
        }
        return membership;
    }

    /** Read from the SQLSTATE, not from the message, which is in the server's language. */
    private static boolean isUniqueViolation(Throwable violation) {
        for (Throwable cause = violation; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    /**
     * @throws MembershipNotFoundException if the active clinic has no membership with {@code id}
     * @throws MembershipVersionConflictException if it changed since the client read {@code version}
     * @throws OwnMembershipException if it is the membership of the user making the change
     * @throws MembershipInactiveException if it is inactive
     * @throws LastClinicAdminException if it would leave the clinic without an active administrator
     */
    @Transactional
    ClinicMembership changeRole(UUID id, ClinicRole role, long version) {
        List<ClinicMembership> admins = lockAdminsCheckingActor();
        ClinicMembership membership = findChangeable(id, version);
        if (!membership.isActive()) {
            throw new MembershipInactiveException();
        }
        if (role != ClinicRole.ADMIN_CLINICA) {
            requireAnotherAdmin(admins, membership);
        }
        membership.setRole(role);
        // The membership first: its row lock waits for a creation or activation of the record that holds it (FOR
        // SHARE), and only then is the record looked up, so a record committed meanwhile is seen and deactivated.
        flush();
        PractitionerRoles.followMembership(practitioners, membership);
        flush();
        return membership;
    }

    /**
     * Deactivates the membership, which takes effect on the user's next request (M02-T04). Deactivating an inactive
     * membership changes nothing and ignores {@code version}, so a repeated request succeeds.
     *
     * @return the membership, and whether this call deactivated it
     * @throws MembershipNotFoundException if the active clinic has no membership with {@code id}
     * @throws MembershipVersionConflictException if it is active and changed since the client read {@code version}
     * @throws OwnMembershipException if it is the membership of the user making the change
     * @throws LastClinicAdminException if it would leave the clinic without an active administrator
     */
    @Transactional
    ClinicMembership deactivate(UUID id, long version) {
        List<ClinicMembership> admins = lockAdminsCheckingActor();
        ClinicMembership membership = find(id);
        if (membership.getUserId().equals(currentUser())) {
            throw new OwnMembershipException();
        }
        if (!membership.isActive()) {
            return membership;
        }
        requireVersion(membership, version);
        requireAnotherAdmin(admins, membership);
        membership.deactivate();
        // The membership first: its row lock waits for a creation or activation of the record that holds it (FOR
        // SHARE), and only then is the record looked up, so a record committed meanwhile is seen and deactivated.
        flush();
        PractitionerRoles.followMembership(practitioners, membership);
        flush();
        return membership;
    }

    /**
     * Locks the clinic's active administrators and checks that the user making the change is still one of them: the
     * role checked by {@code @PreAuthorize} was read at the start of the request, and another administrator may have
     * demoted them since.
     */
    private List<ClinicMembership> lockAdminsCheckingActor() {
        UUID actor = currentUser();
        List<ClinicMembership> admins = memberships.lockActiveWithRole(ClinicRole.ADMIN_CLINICA);
        if (admins.stream().noneMatch(admin -> admin.getUserId().equals(actor))) {
            throw new AccessDeniedException("No longer an administrator of the clinic");
        }
        return admins;
    }

    private ClinicMembership findChangeable(UUID id, long version) {
        ClinicMembership membership = find(id);
        if (membership.getUserId().equals(currentUser())) {
            throw new OwnMembershipException();
        }
        requireVersion(membership, version);
        return membership;
    }

    /** Never reached through the API while the rules above hold; kept so the rule does not depend on them. */
    private static void requireAnotherAdmin(List<ClinicMembership> admins, ClinicMembership changed) {
        boolean anotherRemains =
                admins.stream().anyMatch(admin -> !admin.getId().equals(changed.getId()));
        if (!anotherRemains) {
            throw new LastClinicAdminException();
        }
    }

    private static void requireVersion(ClinicMembership membership, long expected) {
        Long current = membership.getVersion();
        if (current == null || current != expected) {
            throw new MembershipVersionConflictException();
        }
    }

    /** Hibernate only finds the active clinic's memberships: another clinic's id is not found either. */
    private ClinicMembership find(UUID id) {
        return memberships.findById(id).orElseThrow(MembershipNotFoundException::new);
    }

    /** Writes now, so a concurrent change becomes a 409 here instead of a 500 at commit. */
    private void flush() {
        try {
            memberships.flush();
        } catch (OptimisticLockingFailureException concurrentChange) {
            throw new MembershipVersionConflictException();
        }
    }

    private static UUID currentUser() {
        return CurrentUser.id().orElseThrow(() -> new IllegalStateException("No signed-in user"));
    }
}
