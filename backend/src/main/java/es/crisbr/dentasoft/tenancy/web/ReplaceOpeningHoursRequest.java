package es.crisbr.dentasoft.tenancy.web;

import es.crisbr.dentasoft.tenancy.internal.ClinicOpeningHours.Slot;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * The clinic's whole week, which replaces the one it had. An empty list closes the clinic every day.
 *
 * @param slots at most {@value #MAX_SLOTS}; overlaps are checked by the service, which answers 400
 */
public record ReplaceOpeningHoursRequest(
        @NotNull @Size(max = MAX_SLOTS) List<@Valid @NotNull SlotRequest> slots) {

    /** Far more than any real week: four slots a day. */
    public static final int MAX_SLOTS = 28;

    List<Slot> toSlots() {
        return slots.stream().map(SlotRequest::toSlot).toList();
    }

    /**
     * One opening slot, as wall-clock times in the clinic's time zone. Times count in whole minutes: seconds and
     * fractions are dropped before anything is checked, so what is stored, and returned, is what was validated.
     *
     * @param dayOfWeek {@code MONDAY} to {@code SUNDAY}
     * @param openingTime as {@code HH:mm}, before {@code closingTime}
     */
    public record SlotRequest(
            @NotNull DayOfWeek dayOfWeek,
            @NotNull LocalTime openingTime,
            @NotNull LocalTime closingTime) {

        Slot toSlot() {
            return new Slot(
                    dayOfWeek,
                    openingTime.truncatedTo(ChronoUnit.MINUTES),
                    closingTime.truncatedTo(ChronoUnit.MINUTES));
        }
    }
}
