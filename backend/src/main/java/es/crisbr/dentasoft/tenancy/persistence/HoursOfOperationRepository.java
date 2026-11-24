package es.crisbr.dentasoft.tenancy.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Opening slots of the active clinic: Hibernate and Row Level Security only ever show its own. */
public interface HoursOfOperationRepository extends JpaRepository<HoursOfOperation, UUID> {

    /** The week in order: by day, then by opening time. */
    List<HoursOfOperation> findAllByOrderByDayOfWeekAscOpeningTimeAsc();
}
