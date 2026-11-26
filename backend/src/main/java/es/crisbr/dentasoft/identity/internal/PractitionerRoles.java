package es.crisbr.dentasoft.identity.internal;

import es.crisbr.dentasoft.identity.ActivePractitioners;
import es.crisbr.dentasoft.identity.ClinicRole;
import es.crisbr.dentasoft.identity.PractitionerSpecialty;
import es.crisbr.dentasoft.identity.PractitionerSummary;
import es.crisbr.dentasoft.identity.PractitionerUnavailableException;
import es.crisbr.dentasoft.identity.internal.PractitionerConflictException.Reason;
import es.crisbr.dentasoft.identity.persistence.ClinicMembership;
import es.crisbr.dentasoft.identity.persistence.ClinicMembershipRepository;
import es.crisbr.dentasoft.identity.persistence.PractitionerRole;
import es.crisbr.dentasoft.identity.persistence.PractitionerRoleRepository;
import es.crisbr.dentasoft.identity.persistence.PractitionerRoleStatus;
import es.crisbr.dentasoft.shared.PageLimits;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The active clinic's practitioner records (M02-T06): only {@code ADMIN_CLINICA} manages them, and every member of the
 * clinic reads them. A record is never deleted: it is deactivated, and may be activated again.
 *
 * <p>A record linked to a user follows their membership: it needs an active membership of the clinic as {@code
 * ODONTOLOGO} or {@code HIGIENISTA} whose role suits its clinical area ({@link PractitionerSpecialty#suits}), and
 * {@link ClinicMemberships} deactivates it when the membership stops meeting that. The membership is locked while
 * checking it ({@code FOR SHARE}), so it cannot be deactivated between the check and the commit.
 */
@Service
public class PractitionerRoles implements ActivePractitioners {

    /** Any member of the active clinic: the roles exist only there (ADR 0015). */
    private static final String MEMBER =
            "hasAnyRole('ADMIN_CLINICA', 'ODONTOLOGO', 'HIGIENISTA', 'RECEPCION', 'FACTURACION')";

    private static final String ADMIN = "hasRole('ADMIN_CLINICA')";

    private final PractitionerRoleRepository practitioners;
    private final ClinicMembershipRepository memberships;

    PractitionerRoles(PractitionerRoleRepository practitioners, ClinicMembershipRepository memberships) {
        this.practitioners = practitioners;
        this.memberships = memberships;
    }

    /** One page of the clinic's records; all of them, or only those with {@code status}. */
    @PreAuthorize(MEMBER)
    @Transactional(readOnly = true)
    public Page<PractitionerDetails> list(@Nullable PractitionerRoleStatus status, Pageable pageable) {
        Pageable requested = PageLimits.bounded(pageable, PractitionerRoleRepository.SORTABLE);
        // The id last: names and statuses repeat, and rows that tie have no fixed order, so pages could overlap.
        Pageable bounded = PageRequest.of(
                requested.getPageNumber(),
                requested.getPageSize(),
                requested.getSort().and(Sort.by("id")));
        Page<PractitionerRole> page =
                status == null ? practitioners.findAll(bounded) : practitioners.findAllByStatus(status, bounded);
        return page.map(PractitionerDetails::of);
    }

    /** @throws PractitionerNotFoundException if the active clinic has no record with {@code id} */
    @PreAuthorize(MEMBER)
    @Transactional(readOnly = true)
    public PractitionerDetails get(UUID id) {
        return PractitionerDetails.of(find(id));
    }

    /**
     * @throws PractitionerConflictException if the user is not eligible or already has a record, the area does not
     *     suit their role, or another active record has the licence number
     */
    @PreAuthorize(ADMIN)
    @Transactional
    public PractitionerDetails create(PractitionerData data) {
        UUID user = data.userId();
        if (user != null) {
            requireEligible(user, data.specialty());
            if (practitioners.findByUserId(user).isPresent()) {
                throw new PractitionerConflictException(Reason.USER_TAKEN);
            }
        }
        if (data.hasLicense() && practitioners.existsActiveWithLicense(data.licenseProvince(), data.licenseNumber())) {
            throw new PractitionerConflictException(Reason.LICENSE_TAKEN);
        }
        PractitionerRole practitioner =
                new PractitionerRole(user, data.displayName(), data.specialty(), data.agendaColor());
        practitioner.setLicense(data.licenseProvince(), data.licenseNumber());
        practitioners.save(practitioner);
        flushCheckingKeys();
        return PractitionerDetails.of(practitioner);
    }

    /**
     * Replaces the record's data. Its user may be set if it had none, and never changed nor removed after.
     *
     * @throws PractitionerNotFoundException if the active clinic has no record with {@code id}
     * @throws PractitionerConflictException if the record changed since the client read {@code version}, would change
     *     user, the new user is not eligible or already has a record, the area does not suit the user's role while
     *     the record is active, or another active record has the licence number
     */
    @PreAuthorize(ADMIN)
    @Transactional
    public PractitionerDetails update(UUID id, PractitionerData data, long version) {
        PractitionerRole practitioner = find(id);
        requireVersion(practitioner, version);
        UUID current = practitioner.getUserId();
        UUID user = data.userId();
        if (current != null && !current.equals(user)) {
            throw new PractitionerConflictException(Reason.USER_FIXED);
        }
        boolean linking = current == null && user != null;
        if (user != null && (linking || practitioner.isActive())) {
            requireEligible(user, data.specialty());
        }
        if (linking && practitioners.findByUserId(user).isPresent()) {
            throw new PractitionerConflictException(Reason.USER_TAKEN);
        }
        if (practitioner.isActive()
                && data.hasLicense()
                && practitioners.existsOtherActiveWithLicense(data.licenseProvince(), data.licenseNumber(), id)) {
            throw new PractitionerConflictException(Reason.LICENSE_TAKEN);
        }
        if (linking) {
            practitioner.linkUser(user);
        }
        practitioner.setDisplayName(data.displayName());
        practitioner.setSpecialty(data.specialty());
        practitioner.setLicense(data.licenseProvince(), data.licenseNumber());
        practitioner.setAgendaColor(data.agendaColor());
        flushCheckingKeys();
        return PractitionerDetails.of(practitioner);
    }

    /**
     * Takes the practitioner out of new appointments; the appointments already given keep them. Deactivating an
     * inactive record changes nothing.
     *
     * @throws PractitionerNotFoundException if the active clinic has no record with {@code id}
     * @throws PractitionerConflictException if the record changed since the client read {@code version}
     */
    @PreAuthorize(ADMIN)
    @Transactional
    public PractitionerDetails deactivate(UUID id, long version) {
        PractitionerRole practitioner = find(id);
        requireVersion(practitioner, version);
        practitioner.setStatus(PractitionerRoleStatus.INACTIVE);
        flush();
        return PractitionerDetails.of(practitioner);
    }

    /**
     * Offers the practitioner for new appointments again. A linked record needs its user to be eligible again:
     * reactivating a membership never reactivates the record by itself. Activating an active record changes nothing.
     *
     * @throws PractitionerNotFoundException if the active clinic has no record with {@code id}
     * @throws PractitionerConflictException if the record changed since the client read {@code version}, its user is
     *     not eligible or their role does not suit its area, or an active record has taken its licence number
     */
    @PreAuthorize(ADMIN)
    @Transactional
    public PractitionerDetails activate(UUID id, long version) {
        PractitionerRole practitioner = find(id);
        requireVersion(practitioner, version);
        if (!practitioner.isActive()) {
            UUID user = practitioner.getUserId();
            if (user != null) {
                requireEligible(user, practitioner.getSpecialty());
            }
            String province = practitioner.getLicenseProvince();
            String number = practitioner.getLicenseNumber();
            if (province != null
                    && number != null
                    && practitioners.existsOtherActiveWithLicense(province, number, id)) {
                throw new PractitionerConflictException(Reason.LICENSE_TAKEN);
            }
        }
        practitioner.setStatus(PractitionerRoleStatus.ACTIVE);
        flushCheckingKeys();
        return PractitionerDetails.of(practitioner);
    }

    @Override
    @PreAuthorize(MEMBER)
    @Transactional(readOnly = true)
    public List<PractitionerSummary> list() {
        return practitioners.findAllByStatusOrderByDisplayNameAscIdAsc(PractitionerRoleStatus.ACTIVE).stream()
                .map(PractitionerRoles::summary)
                .toList();
    }

    @Override
    @PreAuthorize(MEMBER)
    @Transactional(readOnly = true)
    public PractitionerSummary requireActive(UUID id) {
        return practitioners
                .findById(id)
                .filter(PractitionerRole::isActive)
                .map(PractitionerRoles::summary)
                .orElseThrow(PractitionerUnavailableException::new);
    }

    @Override
    @PreAuthorize(MEMBER)
    @Transactional(readOnly = true)
    public Map<UUID, PractitionerSummary> describe(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return practitioners.findAllByIdIn(ids).stream()
                .map(PractitionerRoles::summary)
                .collect(Collectors.toUnmodifiableMap(PractitionerSummary::id, Function.identity()));
    }

    /**
     * Deactivates the record of the membership's user when the membership no longer allows it: inactive, or with a
     * role that does not suit the record's area. Called by {@link ClinicMemberships} in its own transaction, after
     * changing the membership; never reactivates anything.
     */
    static void followMembership(PractitionerRoleRepository practitioners, ClinicMembership membership) {
        practitioners
                .findByUserId(membership.getUserId())
                .filter(PractitionerRole::isActive)
                .filter(practitioner ->
                        !membership.isActive() || !practitioner.getSpecialty().suits(membership.getRole()))
                .ifPresent(practitioner -> practitioner.setStatus(PractitionerRoleStatus.INACTIVE));
    }

    /**
     * Requires an active membership of the clinic as {@code ODONTOLOGO} or {@code HIGIENISTA} whose role suits {@code
     * specialty}, pending invitations included, so a record can be prepared before the person accepts. Locks the
     * membership until the transaction ends.
     */
    private void requireEligible(UUID user, PractitionerSpecialty specialty) {
        ClinicMembership membership = memberships
                .lockSharedByUserId(user)
                .filter(ClinicMembership::isActive)
                .filter(found -> isClinical(found.getRole()))
                .orElseThrow(() -> new PractitionerConflictException(Reason.USER_NOT_ELIGIBLE));
        if (!specialty.suits(membership.getRole())) {
            throw new PractitionerConflictException(Reason.SPECIALTY_MISMATCH);
        }
    }

    private static boolean isClinical(ClinicRole role) {
        return role == ClinicRole.ODONTOLOGO || role == ClinicRole.HIGIENISTA;
    }

    private static void requireVersion(PractitionerRole practitioner, long expected) {
        Long current = practitioner.getVersion();
        if (current == null || current != expected) {
            throw new PractitionerConflictException(Reason.VERSION);
        }
    }

    /** Hibernate only finds the active clinic's records: another clinic's id is not found either. */
    private PractitionerRole find(UUID id) {
        return practitioners.findById(id).orElseThrow(PractitionerNotFoundException::new);
    }

    /** Writes now, so a concurrent change becomes a 409 here instead of a 500 at commit. */
    private void flush() {
        try {
            practitioners.flush();
        } catch (OptimisticLockingFailureException concurrentChange) {
            throw new PractitionerConflictException(Reason.VERSION);
        }
    }

    /**
     * Writes now. Two requests that check the same free licence number, or link the same user, at once both pass the
     * check; the unique keys stop the second, which must be a 409 like the check's, not a 500.
     */
    private void flushCheckingKeys() {
        try {
            flush();
        } catch (DataIntegrityViolationException violation) {
            String constraint = violatedUniqueKey(violation);
            if (PractitionerRole.ACTIVE_LICENSE_KEY.equals(constraint)) {
                throw new PractitionerConflictException(Reason.LICENSE_TAKEN);
            }
            if (PractitionerRole.USER_KEY.equals(constraint)) {
                throw new PractitionerConflictException(Reason.USER_TAKEN);
            }
            throw violation;
        }
    }

    /**
     * The unique key PostgreSQL refused the write for, if that was the reason. Read from the SQLSTATE and the
     * constraint field of the server's error, not from its message, which is in the server's language.
     */
    static @Nullable String violatedUniqueKey(Throwable violation) {
        for (Throwable cause = violation; cause != null; cause = cause.getCause()) {
            // A batched write reports the statement's own error as the next exception.
            for (Throwable error = cause; error instanceof SQLException sql; error = sql.getNextException()) {
                if (error instanceof PSQLException psql
                        && PSQLState.UNIQUE_VIOLATION.getState().equals(psql.getSQLState())) {
                    ServerErrorMessage server = psql.getServerErrorMessage();
                    return server == null ? null : server.getConstraint();
                }
            }
        }
        return null;
    }

    private static PractitionerSummary summary(PractitionerRole practitioner) {
        return new PractitionerSummary(
                practitioner.getId(),
                practitioner.getDisplayName(),
                practitioner.getSpecialty(),
                practitioner.getAgendaColor(),
                practitioner.isActive());
    }

    /**
     * What a practitioner record is made of, when created or replaced; already validated by the web layer.
     *
     * @param licenseProvince INE code of the college's province; present exactly when {@code licenseNumber} is
     */
    public record PractitionerData(
            @Nullable UUID userId,
            String displayName,
            PractitionerSpecialty specialty,
            @Nullable String licenseProvince,
            @Nullable String licenseNumber,
            String agendaColor) {

        boolean hasLicense() {
            return licenseProvince != null && licenseNumber != null;
        }
    }

    /** A practitioner record as the API returns it. */
    public record PractitionerDetails(
            UUID id,
            @Nullable UUID userId,
            String displayName,
            PractitionerSpecialty specialty,
            @Nullable String licenseProvince,
            @Nullable String licenseNumber,
            String agendaColor,
            PractitionerRoleStatus status,
            long version) {

        static PractitionerDetails of(PractitionerRole practitioner) {
            return new PractitionerDetails(
                    practitioner.getId(),
                    practitioner.getUserId(),
                    practitioner.getDisplayName(),
                    practitioner.getSpecialty(),
                    practitioner.getLicenseProvince(),
                    practitioner.getLicenseNumber(),
                    practitioner.getAgendaColor(),
                    practitioner.getStatus(),
                    practitioner.getVersion());
        }
    }
}
