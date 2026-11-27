package es.crisbr.dentasoft.identity;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The active clinic's practitioners, for other modules (M06 and later). A deactivated practitioner is never offered
 * for new appointments ({@link #list()}, {@link #requireActive}), but what was already given with them keeps showing
 * them ({@link #describe}): practitioner records are never deleted.
 *
 * <p>For any member of the clinic: reception books appointments too. A signed-in member is required, so processes
 * without a user (M07 reminders) cannot call it yet: the task that first needs it decides how they may.
 */
public interface ActivePractitioners {

    /** The clinic's active practitioners, by name: those that may be chosen for a new appointment. */
    List<PractitionerSummary> list();

    /**
     * The practitioner with {@code id}, to give them a new appointment. Locks the record until the caller's
     * transaction ends: call it in the transaction that stores the appointment, so the practitioner cannot be
     * deactivated between this check and the commit. That transaction must not be read-only (PostgreSQL takes no lock
     * in one), and should call this before reading the record any other way, or it may get the copy already read.
     * Let the exception propagate: it marks the caller's transaction for rollback, so catching it and committing ends
     * in {@code UnexpectedRollbackException}.
     *
     * @throws PractitionerUnavailableException if the clinic has no practitioner with {@code id}, or it is inactive
     */
    PractitionerSummary requireActive(UUID id);

    /** The practitioners among {@code ids}, inactive ones included, by id; ids the clinic does not have are left out. */
    Map<UUID, PractitionerSummary> describe(Collection<UUID> ids);
}
