package es.crisbr.dentasoft.tenancy.internal;

import static es.crisbr.dentasoft.tenancy.internal.ClinicOpeningHoursTests.slot;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.TUESDAY;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.crisbr.dentasoft.tenancy.internal.ClinicOpeningHours.Slot;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The overlap rule on its own, on weeks already sorted by day and opening time as the service sorts them. */
class ClinicOpeningHoursUnitTests {

    @Test
    void slotsThatOnlyTouchDoNotOverlap() {
        assertThatCode(() -> check(slot(MONDAY, "09:00", "14:00"), slot(MONDAY, "14:00", "18:00")))
                .doesNotThrowAnyException();
    }

    @Test
    void theSameHoursOnDifferentDaysDoNotOverlap() {
        assertThatCode(() -> check(slot(MONDAY, "09:00", "14:00"), slot(TUESDAY, "09:00", "14:00")))
                .doesNotThrowAnyException();
    }

    @Test
    void aSlotThatOpensBeforeThePreviousClosesOverlaps() {
        assertThatThrownBy(() -> check(slot(MONDAY, "09:00", "14:00"), slot(MONDAY, "13:59", "18:00")))
                .isInstanceOf(OverlappingHoursException.class);
    }

    /** The third slot does not overlap the second, but the first still covers it. */
    @Test
    void aSlotInsideAnEarlierLongerOneOverlaps() {
        assertThatThrownBy(() -> check(
                        slot(MONDAY, "09:00", "20:00"), slot(MONDAY, "10:00", "11:00"), slot(MONDAY, "12:00", "13:00")))
                .isInstanceOf(OverlappingHoursException.class);
        assertThatThrownBy(() -> check(slot(MONDAY, "09:00", "20:00"), slot(MONDAY, "12:00", "13:00")))
                .isInstanceOf(OverlappingHoursException.class);
    }

    @Test
    void twoIdenticalSlotsOverlap() {
        assertThatThrownBy(() -> check(slot(MONDAY, "09:00", "14:00"), slot(MONDAY, "09:00", "14:00")))
                .isInstanceOf(OverlappingHoursException.class);
    }

    private static void check(Slot... week) {
        ClinicOpeningHours.requireNoOverlaps(List.of(week));
    }
}
