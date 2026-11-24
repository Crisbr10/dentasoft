package es.crisbr.dentasoft.tenancy.persistence;

import es.crisbr.dentasoft.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.DayOfWeek;
import java.time.LocalTime;

/**
 * One opening slot of the active clinic ({@code clinic_hours_of_operation}, V003), as FHIR {@code
 * Location.hoursOfOperation}: a day of the week may have several, which never overlap.
 *
 * <p>The times are wall-clock times of the clinic in its own time zone ({@link Clinic#getTimezone()}), so they are
 * {@link LocalTime}, stored as {@code time}: the exception to the rule that dates are {@code timestamptz} (V003).
 *
 * <p>Slots are not edited one by one: the clinic replaces its whole week, which deletes the old ones.
 */
@Entity
@Table(name = "clinic_hours_of_operation")
public class HoursOfOperation extends TenantScopedEntity {

    @Convert(converter = IsoDayOfWeekConverter.class)
    @Column(name = "day_of_week", nullable = false)
    private DayOfWeek dayOfWeek;

    @Column(name = "opening_time", nullable = false)
    private LocalTime openingTime;

    @Column(name = "closing_time", nullable = false)
    private LocalTime closingTime;

    protected HoursOfOperation() {}

    public HoursOfOperation(DayOfWeek dayOfWeek, LocalTime openingTime, LocalTime closingTime) {
        this.dayOfWeek = dayOfWeek;
        this.openingTime = openingTime;
        this.closingTime = closingTime;
    }

    public DayOfWeek getDayOfWeek() {
        return dayOfWeek;
    }

    public LocalTime getOpeningTime() {
        return openingTime;
    }

    public LocalTime getClosingTime() {
        return closingTime;
    }

    /** Only for tests that must change a stored slot: the application replaces slots, it never edits them. */
    void setClosingTime(LocalTime closingTime) {
        this.closingTime = closingTime;
    }
}
