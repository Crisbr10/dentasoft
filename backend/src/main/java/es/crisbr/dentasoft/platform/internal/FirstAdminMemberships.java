package es.crisbr.dentasoft.platform.internal;

import es.crisbr.dentasoft.audit.AuditAction;
import es.crisbr.dentasoft.audit.AuditDetails;
import es.crisbr.dentasoft.audit.AuditSubtype;
import es.crisbr.dentasoft.platform.internal.FirstAdminInvitations.AdminInvitationState;
import es.crisbr.dentasoft.platform.internal.FirstAdminInvitations.AdminInvitationStatus;
import es.crisbr.dentasoft.platform.persistence.PlatformAdminMembership;
import es.crisbr.dentasoft.platform.persistence.PlatformAdminMemberships;
import es.crisbr.dentasoft.platform.persistence.PlatformAuditRecord;
import es.crisbr.dentasoft.platform.persistence.PlatformClinics;
import es.crisbr.dentasoft.tenancy.TenantContext;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The database side of the first administrator's invitation (M02-T11, ADR 0018): each method is one transaction of the
 * platform unit, and none calls Keycloak ({@link FirstAdminInvitations} does that outside them). Each change is
 * recorded in {@code audit_log} with actor {@code PLATFORM}, in the same transaction.
 *
 * <p>The checks here give clear answers; the rule itself is PostgreSQL's (V013). Its trigger locks the clinic's row
 * before making an administrator active, so two invitations at the same time cannot both pass: the second fails at
 * {@link PlatformAdminMemberships#flush()} with a unique violation, answered as {@link FirstAdminConflictException}.
 */
@Service
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
class FirstAdminMemberships {

    private static final String UNIQUE_VIOLATION = "23505";

    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    private final PlatformClinics clinics;
    private final PlatformAdminMemberships memberships;

    FirstAdminMemberships(PlatformClinics clinics, PlatformAdminMemberships memberships) {
        this.clinics = clinics;
        this.memberships = memberships;
    }

    /**
     * Checks, before anything is sent to Keycloak, that the clinic exists and has no active administrator.
     *
     * @throws ClinicNotFoundException if there is no clinic with {@code clinicId}
     * @throws FirstAdminConflictException if it has an active administrator, accepted or pending
     */
    @Transactional(value = PlatformPersistenceConfiguration.TRANSACTION_MANAGER, readOnly = true)
    void requireNoAdministrator(UUID clinicId) {
        if (adminsOf(clinicId).stream().anyMatch(PlatformAdminMembership::isActive)) {
            throw new FirstAdminConflictException();
        }
    }

    /**
     * Creates {@code userId}'s account if missing and invites them as the clinic's administrator: a new pending
     * membership, or theirs made active again if the platform had cancelled it.
     *
     * @return the membership's id
     * @throws ClinicNotFoundException if there is no clinic with {@code clinicId}
     * @throws FirstAdminConflictException if the clinic has an active administrator, accepted or pending, or the person
     *     already accepted a membership of it
     */
    @Transactional(PlatformPersistenceConfiguration.TRANSACTION_MANAGER)
    UUID invite(UUID clinicId, UUID userId) {
        List<PlatformAdminMembership> admins = adminsOf(clinicId);
        if (admins.stream().anyMatch(PlatformAdminMembership::isActive)) {
            throw new FirstAdminConflictException();
        }
        memberships.createAccount(userId);
        Optional<PlatformAdminMembership> previous = admins.stream()
                .filter(admin -> admin.getUserId().equals(userId))
                .findFirst();
        PlatformAdminMembership membership;
        AuditAction action;
        if (previous.isPresent()) {
            membership = previous.get();
            if (membership.isAccepted()) {
                // An administrator who accepted once is out of the platform's reach (V013).
                throw new FirstAdminConflictException();
            }
            membership.reactivate();
            action = AuditAction.UPDATE;
        } else {
            membership = new PlatformAdminMembership(clinicId, userId);
            memberships.add(membership);
            action = AuditAction.CREATE;
        }
        flushRefusingASecondAdministrator();
        UUID id = idOf(membership);
        memberships.add(PlatformAuditRecord.ofMembership(
                clinicId, id, action, AuditDetails.of(AuditSubtype.FIRST_ADMIN_INVITED)));
        memberships.flush();
        return id;
    }

    /**
     * Whether the clinic has an administrator: accepted (when), a pending invitation (when it was sent) or none. A
     * cancelled invitation counts as none. With several accepted administrators, the date is the first acceptance.
     *
     * @throws ClinicNotFoundException if there is no clinic with {@code clinicId}
     */
    @Transactional(value = PlatformPersistenceConfiguration.TRANSACTION_MANAGER, readOnly = true)
    AdminInvitationStatus status(UUID clinicId) {
        List<PlatformAdminMembership> active = adminsOf(clinicId).stream()
                .filter(PlatformAdminMembership::isActive)
                .toList();
        Optional<Instant> firstAcceptance = active.stream()
                .map(PlatformAdminMembership::getAcceptedAt)
                .filter(Objects::nonNull)
                .map(OffsetDateTime::toInstant)
                .min(Comparator.naturalOrder());
        if (firstAcceptance.isPresent()) {
            return new AdminInvitationStatus(AdminInvitationState.ACCEPTED, null, firstAcceptance.get());
        }
        return active.stream()
                .findFirst()
                .map(pending -> new AdminInvitationStatus(AdminInvitationState.PENDING, pending.getUpdatedAt(), null))
                .orElse(new AdminInvitationStatus(AdminInvitationState.NONE, null, null));
    }

    /**
     * The clinic's pending invitation, to send its email again. Changes no membership.
     *
     * @throws ClinicNotFoundException if there is no clinic with {@code clinicId}
     * @throws FirstAdminInvitationNotFoundException if it has no pending invitation of an administrator
     */
    @Transactional(value = PlatformPersistenceConfiguration.TRANSACTION_MANAGER, readOnly = true)
    PendingInvitation pendingInvitation(UUID clinicId) {
        PlatformAdminMembership pending = pendingOf(clinicId);
        return new PendingInvitation(idOf(pending), pending.getUserId());
    }

    /** Records that the invitation's email was sent again; the membership does not change. */
    @Transactional(PlatformPersistenceConfiguration.TRANSACTION_MANAGER)
    void recordResend(UUID clinicId, UUID membershipId) {
        requireNoActiveClinic();
        memberships.add(PlatformAuditRecord.ofMembership(
                clinicId,
                membershipId,
                AuditAction.UPDATE,
                AuditDetails.of(AuditSubtype.FIRST_ADMIN_INVITATION_RESENT)));
        memberships.flush();
    }

    /**
     * Cancels the clinic's pending invitation: the membership becomes inactive and grants nothing. Another person can
     * then be invited.
     *
     * @throws ClinicNotFoundException if there is no clinic with {@code clinicId}
     * @throws FirstAdminInvitationNotFoundException if it has no pending invitation, also when the person accepted it
     *     or it was cancelled while this request ran
     */
    @Transactional(PlatformPersistenceConfiguration.TRANSACTION_MANAGER)
    void cancel(UUID clinicId) {
        PlatformAdminMembership pending = pendingOf(clinicId);
        pending.cancel();
        try {
            memberships.flush();
        } catch (OptimisticLockingFailureException changedMeanwhile) {
            // Accepted or cancelled since it was read. PostgreSQL's USING (accepted_at IS NULL) also hides an accepted
            // one from the UPDATE, which Hibernate sees as a stale version.
            throw new FirstAdminInvitationNotFoundException();
        } catch (DataAccessException refused) {
            // V013's trigger: another administrator accepted meanwhile, so this invitation is the clinic's own now.
            if (hasSqlState(refused, INSUFFICIENT_PRIVILEGE)) {
                throw new FirstAdminInvitationNotFoundException();
            }
            throw refused;
        }
        memberships.add(PlatformAuditRecord.ofMembership(
                clinicId,
                idOf(pending),
                AuditAction.UPDATE,
                AuditDetails.of(AuditSubtype.FIRST_ADMIN_INVITATION_CANCELLED)));
        memberships.flush();
    }

    /** The invitation to resend: the membership and its user. */
    record PendingInvitation(UUID membershipId, UUID userId) {}

    /**
     * The first administrator's pending invitation. Once the clinic has an accepted administrator there is none: the
     * pending invitations left are the clinic's own (M02-T05), which the platform does not touch (PostgreSQL refuses
     * cancelling them too, V013).
     */
    private PlatformAdminMembership pendingOf(UUID clinicId) {
        List<PlatformAdminMembership> admins = adminsOf(clinicId);
        if (admins.stream().anyMatch(admin -> admin.isActive() && admin.isAccepted())) {
            throw new FirstAdminInvitationNotFoundException();
        }
        return admins.stream()
                .filter(admin -> admin.isActive() && !admin.isAccepted())
                .findFirst()
                .orElseThrow(FirstAdminInvitationNotFoundException::new);
    }

    /** The clinic's administrators' memberships, after checking that the clinic exists. */
    private List<PlatformAdminMembership> adminsOf(UUID clinicId) {
        requireNoActiveClinic();
        if (clinics.find(clinicId).isEmpty()) {
            throw new ClinicNotFoundException();
        }
        return memberships.ofClinic(clinicId);
    }

    private void flushRefusingASecondAdministrator() {
        try {
            memberships.flush();
        } catch (DataIntegrityViolationException violation) {
            // The trigger's refusal (constraint clinic_first_admin), or UNIQUE (tenant_id, user_id): the same person
            // was invited at the same time. Either way the clinic has an administrator now.
            if (hasSqlState(violation, UNIQUE_VIOLATION)) {
                throw new FirstAdminConflictException();
            }
            throw violation;
        } catch (OptimisticLockingFailureException changedMeanwhile) {
            // The cancelled membership being reactivated was changed by another request.
            throw new FirstAdminConflictException();
        }
    }

    /** Read from the SQLSTATE, not from the message, which is in the server's language. */
    private static boolean hasSqlState(Throwable failure, String sqlState) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sqlState.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static UUID idOf(PlatformAdminMembership membership) {
        UUID id = membership.getId();
        if (id == null) {
            throw new IllegalStateException("The membership has no id yet");
        }
        return id;
    }

    /** As in {@link ClinicAdministration}: platform administration never runs with an active clinic. */
    private static void requireNoActiveClinic() {
        if (TenantContext.isBound()) {
            throw new IllegalStateException("Platform administration cannot run with an active clinic");
        }
    }
}
