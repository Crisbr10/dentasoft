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
 * <p>For any member of the clinic: reception books appointments too.
 */
public interface ActivePractitioners {

    /** The clinic's active practitioners, by name: those that may be chosen for a new appointment. */
    List<PractitionerSummary> list();

    /**
     * The practitioner with {@code id}, to give them a new appointment.
     *
     * @throws PractitionerUnavailableException if the clinic has no practitioner with {@code id}, or it is inactive
     */
    PractitionerSummary requireActive(UUID id);

    /** The practitioners among {@code ids}, inactive ones included, by id; ids the clinic does not have are left out. */
    Map<UUID, PractitionerSummary> describe(Collection<UUID> ids);
}
