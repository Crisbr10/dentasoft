package es.crisbr.dentasoft.tenancy.persistence;

import es.crisbr.dentasoft.tenancy.TenantIsolationTest;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.JpaRepository;

/** A clinic never reads nor changes another clinic's treatment rooms (V003, M01-T06). */
class LocationIsolationTests extends TenantIsolationTest<Location> {

    @Autowired
    private LocationRepository locations;

    @Override
    protected JpaRepository<Location, UUID> repository() {
        return locations;
    }

    @Override
    protected String table() {
        return "location";
    }

    @Override
    protected Location newEntity(int n) {
        return new Location("Gabinete " + (n + 1), "#1E88E5");
    }

    @Override
    protected void modify(Location location) {
        location.setName(location.getName() + " (cambiado)");
    }
}
