package es.crisbr.dentasoft.tenancy.internal;

import static java.time.DayOfWeek.FRIDAY;
import static java.time.DayOfWeek.MONDAY;
import static java.time.DayOfWeek.SATURDAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.crisbr.dentasoft.tenancy.TenancyTestDatabase;
import es.crisbr.dentasoft.tenancy.internal.ClinicOpeningHours.Slot;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** {@code ADMIN_CLINICA} replaces its clinic's whole week, several slots a day, never overlapping (M01-T06). */
class ClinicOpeningHoursTests extends ClinicConfigurationTestBase {

    private static final String TABLE = "clinic_hours_of_operation";

    @Autowired
    private ClinicOpeningHours openingHours;

    @Test
    void aNewClinicHasNoHours() {
        assertThat(asClinicA(openingHours::week)).isEmpty();
    }

    /** Morning and afternoon on Monday, the slots that only touch on Friday: returned in week order. */
    @Test
    void replacesTheWeekWithSeveralSlotsPerDay() {
        List<Slot> week = List.of(
                slot(SATURDAY, "10:00", "13:00"),
                slot(MONDAY, "16:00", "20:00"),
                slot(FRIDAY, "14:00", "18:00"),
                slot(MONDAY, "09:00", "14:00"),
                slot(FRIDAY, "09:00", "14:00"));
        List<Slot> inOrder = List.of(
                slot(MONDAY, "09:00", "14:00"),
                slot(MONDAY, "16:00", "20:00"),
                slot(FRIDAY, "09:00", "14:00"),
                slot(FRIDAY, "14:00", "18:00"),
                slot(SATURDAY, "10:00", "13:00"));

        assertThat(asClinicA(() -> openingHours.replaceWeek(week))).isEqualTo(inOrder);
        assertThat(asClinicA(openingHours::week)).isEqualTo(inOrder);
    }

    @Test
    void replacingTheWeekRemovesTheOldSlots() {
        asClinicA(() ->
                openingHours.replaceWeek(List.of(slot(MONDAY, "09:00", "14:00"), slot(FRIDAY, "09:00", "14:00"))));

        asClinicA(() -> openingHours.replaceWeek(List.of(slot(SATURDAY, "10:00", "13:00"))));

        assertThat(asClinicA(openingHours::week)).containsExactly(slot(SATURDAY, "10:00", "13:00"));
    }

    @Test
    void anEmptyWeekClosesTheClinicEveryDay() {
        asClinicA(() -> openingHours.replaceWeek(List.of(slot(MONDAY, "09:00", "14:00"))));

        assertThat(asClinicA(() -> openingHours.replaceWeek(List.of()))).isEmpty();
        assertThat(asClinicA(openingHours::week)).isEmpty();
    }

    /** The old slots are deleted: clinic B's, which the same statement could reach without isolation, stay. */
    @Test
    void replacingClinicAsWeekLeavesClinicBsAlone() throws SQLException {
        asClinicB(() -> openingHours.replaceWeek(List.of(slot(MONDAY, "08:00", "15:00"))));

        asClinicA(() -> openingHours.replaceWeek(List.of(slot(FRIDAY, "09:00", "14:00"))));
        asClinicA(() -> openingHours.replaceWeek(List.of()));

        assertThat(asClinicB(openingHours::week)).containsExactly(slot(MONDAY, "08:00", "15:00"));
        assertThat(TenancyTestDatabase.countRows(TABLE, CLINIC_B)).isOne();
    }

    @Test
    void refusesOverlappingSlotsAndKeepsTheWeekItHad() throws SQLException {
        List<Slot> week = List.of(slot(MONDAY, "09:00", "14:00"));
        asClinicA(() -> openingHours.replaceWeek(week));

        assertThatThrownBy(() -> asClinicA(() -> openingHours.replaceWeek(
                        List.of(slot(MONDAY, "09:00", "14:00"), slot(MONDAY, "13:00", "18:00")))))
                .isInstanceOf(OverlappingHoursException.class);

        assertThat(asClinicA(openingHours::week)).isEqualTo(week);
        assertThat(TenancyTestDatabase.countRows(TABLE, CLINIC_A)).isOne();
    }

    @Test
    void refusesASlotThatDoesNotCloseAfterItOpens() {
        assertThatThrownBy(() -> asClinicA(() -> openingHours.replaceWeek(List.of(slot(MONDAY, "14:00", "14:00")))))
                .isInstanceOf(OverlappingHoursException.class);
        assertThatThrownBy(() -> asClinicA(() -> openingHours.replaceWeek(List.of(slot(MONDAY, "18:00", "09:00")))))
                .isInstanceOf(OverlappingHoursException.class);
        assertThat(asClinicA(openingHours::week)).isEmpty();
    }

    static Slot slot(DayOfWeek day, String opening, String closing) {
        return new Slot(day, LocalTime.parse(opening), LocalTime.parse(closing));
    }
}
