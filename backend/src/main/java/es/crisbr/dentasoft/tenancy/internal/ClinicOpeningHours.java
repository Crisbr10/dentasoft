package es.crisbr.dentasoft.tenancy.internal;

import es.crisbr.dentasoft.tenancy.TenantContext;
import es.crisbr.dentasoft.tenancy.persistence.ClinicRepository;
import es.crisbr.dentasoft.tenancy.persistence.HoursOfOperation;
import es.crisbr.dentasoft.tenancy.persistence.HoursOfOperationRepository;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The active clinic's opening hours, which {@code ADMIN_CLINICA} edits and every other role of the clinic only reads
 * (M01-T06, M01-T10; see {@link ConfigurationRoles}): its week, as slots of wall-clock time in the clinic's time zone,
 * several per day. The week is always replaced whole.
 */
@Service
@PreAuthorize(ConfigurationRoles.ADMIN)
public class ClinicOpeningHours {

    /** In order: by day, then by opening time. */
    private static final Comparator<Slot> WEEK_ORDER =
            Comparator.comparing(Slot::dayOfWeek).thenComparing(Slot::openingTime);

    private final ClinicRepository clinics;
    private final HoursOfOperationRepository hours;

    ClinicOpeningHours(ClinicRepository clinics, HoursOfOperationRepository hours) {
        this.clinics = clinics;
        this.hours = hours;
    }

    /** The week in order; empty while the clinic has not set its hours. */
    @PreAuthorize(ConfigurationRoles.MEMBER)
    @Transactional(readOnly = true)
    public List<Slot> week() {
        return hours.findAllByOrderByDayOfWeekAscOpeningTimeAsc().stream()
                .map(Slot::of)
                .toList();
    }

    /**
     * Replaces the whole week with {@code slots}, which may be empty (closed every day).
     *
     * <p>The clinic's row is locked first, so two replacements of the same week run one after the other: checked
     * separately, each could pass and together leave overlapping slots.
     *
     * @throws OverlappingHoursException if two slots of a day overlap or one does not close after it opens
     */
    @Transactional
    public List<Slot> replaceWeek(List<Slot> slots) {
        List<Slot> week = slots.stream().sorted(WEEK_ORDER).toList();
        requireNoOverlaps(week);
        clinics.lockById(TenantContext.current())
                .orElseThrow(() -> new IllegalStateException("The active clinic does not exist"));
        // One by one, not a bulk JPQL delete: each slot is an audited entity, and a bulk delete would bypass the
        // audit log. Flushed before the new slots go in, as Hibernate otherwise inserts before it deletes.
        hours.deleteAll();
        hours.flush();
        hours.saveAll(week.stream()
                .map(slot -> new HoursOfOperation(slot.dayOfWeek(), slot.openingTime(), slot.closingTime()))
                .toList());
        hours.flush();
        return week;
    }

    /**
     * {@code week} sorted by day and opening time: each slot must close after it opens, and open no earlier than every
     * slot of its day that opened before it has closed.
     */
    static void requireNoOverlaps(List<Slot> week) {
        DayOfWeek day = null;
        LocalTime latestClosing = null;
        for (Slot slot : week) {
            if (!slot.openingTime().isBefore(slot.closingTime())) {
                throw new OverlappingHoursException();
            }
            if (slot.dayOfWeek() != day) {
                day = slot.dayOfWeek();
                latestClosing = slot.closingTime();
                continue;
            }
            if (slot.openingTime().isBefore(latestClosing)) {
                throw new OverlappingHoursException();
            }
            latestClosing = slot.closingTime();
        }
    }

    /** One opening slot; already validated by the web layer, except for overlaps. */
    public record Slot(DayOfWeek dayOfWeek, LocalTime openingTime, LocalTime closingTime) {

        static Slot of(HoursOfOperation hours) {
            return new Slot(hours.getDayOfWeek(), hours.getOpeningTime(), hours.getClosingTime());
        }
    }
}
