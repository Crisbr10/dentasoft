package es.crisbr.dentasoft.tenancy.persistence;

import es.crisbr.dentasoft.tenancy.TenantIsolationTest;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * A clinic never reads, changes nor deletes another clinic's opening hours (V003, M01-T06). This is the one table where
 * the application has DELETE, so the delete checks run against a real grant: only the isolation stops them.
 */
class HoursOfOperationIsolationTests extends TenantIsolationTest<HoursOfOperation> {

    @Autowired
    private HoursOfOperationRepository hours;

    @Override
    protected JpaRepository<HoursOfOperation, UUID> repository() {
        return hours;
    }

    @Override
    protected String table() {
        return "clinic_hours_of_operation";
    }

    @Override
    protected HoursOfOperation newEntity(int n) {
        return new HoursOfOperation(DayOfWeek.MONDAY, LocalTime.of(9 + n, 0), LocalTime.of(14, 0));
    }

    @Override
    protected void modify(HoursOfOperation slot) {
        slot.setClosingTime(slot.getClosingTime().plusHours(1));
    }
}
