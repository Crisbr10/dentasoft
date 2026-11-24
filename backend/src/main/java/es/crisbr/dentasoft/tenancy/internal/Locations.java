package es.crisbr.dentasoft.tenancy.internal;

import es.crisbr.dentasoft.shared.PageLimits;
import es.crisbr.dentasoft.tenancy.persistence.Location;
import es.crisbr.dentasoft.tenancy.persistence.LocationRepository;
import es.crisbr.dentasoft.tenancy.persistence.LocationStatus;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The active clinic's treatment rooms (gabinetes), which {@code ADMIN_CLINICA} manages (M01-T06). A room is never
 * deleted: it is deactivated, and may be activated again. Two active rooms of the clinic never share a name, whatever
 * the case.
 */
@Service
@PreAuthorize("hasRole('ADMIN_CLINICA')")
public class Locations {

    private final LocationRepository locations;

    Locations(LocationRepository locations) {
        this.locations = locations;
    }

    /** One page of the clinic's rooms; all of them, or only those with {@code status}. */
    @Transactional(readOnly = true)
    public Page<LocationDetails> list(@Nullable LocationStatus status, Pageable pageable) {
        Pageable bounded = PageLimits.bounded(pageable, LocationRepository.SORTABLE);
        Page<Location> page = status == null ? locations.findAll(bounded) : locations.findAllByStatus(status, bounded);
        return page.map(LocationDetails::of);
    }

    /** @throws LocationNotFoundException if the active clinic has no room with {@code id} */
    @Transactional(readOnly = true)
    public LocationDetails get(UUID id) {
        return LocationDetails.of(find(id));
    }

    /** @throws LocationNameTakenException if another active room has the name */
    @Transactional
    public LocationDetails create(LocationData data) {
        if (locations.existsActiveWithName(data.name())) {
            throw new LocationNameTakenException();
        }
        Location location = new Location(data.name(), data.color());
        locations.save(location);
        flushCheckingName();
        return LocationDetails.of(location);
    }

    /**
     * @throws LocationNotFoundException if the active clinic has no room with {@code id}
     * @throws VersionConflictException if the room changed since the client read {@code version}
     * @throws LocationNameTakenException if the room is active and another active room has the new name
     */
    @Transactional
    public LocationDetails update(UUID id, LocationData data, long version) {
        Location location = find(id);
        Versions.require(location, version);
        if (location.getStatus() == LocationStatus.ACTIVE && locations.existsOtherActiveWithName(data.name(), id)) {
            throw new LocationNameTakenException();
        }
        location.setName(data.name());
        location.setColor(data.color());
        flushCheckingName();
        return LocationDetails.of(location);
    }

    /**
     * Takes the room out of use. Deactivating an inactive room changes nothing.
     *
     * @throws LocationNotFoundException if the active clinic has no room with {@code id}
     * @throws VersionConflictException if the room changed since the client read {@code version}
     */
    @Transactional
    public LocationDetails deactivate(UUID id, long version) {
        Location location = find(id);
        Versions.require(location, version);
        location.setStatus(LocationStatus.INACTIVE);
        Versions.flush(locations);
        return LocationDetails.of(location);
    }

    /**
     * Puts the room back in use. Activating an active room changes nothing.
     *
     * @throws LocationNotFoundException if the active clinic has no room with {@code id}
     * @throws VersionConflictException if the room changed since the client read {@code version}
     * @throws LocationNameTakenException if an active room has taken its name in the meantime
     */
    @Transactional
    public LocationDetails activate(UUID id, long version) {
        Location location = find(id);
        Versions.require(location, version);
        if (location.getStatus() == LocationStatus.INACTIVE
                && locations.existsOtherActiveWithName(location.getName(), id)) {
            throw new LocationNameTakenException();
        }
        location.setStatus(LocationStatus.ACTIVE);
        flushCheckingName();
        return LocationDetails.of(location);
    }

    /** Hibernate only finds the active clinic's rooms: another clinic's id is not found either. */
    private Location find(UUID id) {
        return locations.findById(id).orElseThrow(LocationNotFoundException::new);
    }

    /**
     * Writes now. Two requests that check the same free name at once both pass the check; the unique index stops the
     * second, which must be a 409 like the check's, not a 500.
     */
    private void flushCheckingName() {
        try {
            Versions.flush(locations);
        } catch (DataIntegrityViolationException violation) {
            if (violation.getCause() instanceof ConstraintViolationException constraint
                    && Location.ACTIVE_NAME_KEY.equals(constraint.getConstraintName())) {
                throw new LocationNameTakenException();
            }
            throw violation;
        }
    }

    /** What a room is made of, when created or changed; already validated by the web layer. */
    public record LocationData(String name, @Nullable String color) {}

    /** A room as the configuration API returns it. */
    public record LocationDetails(
            UUID id, String name, @Nullable String color, LocationStatus status, long version) {

        static LocationDetails of(Location location) {
            return new LocationDetails(
                    location.getId(),
                    location.getName(),
                    location.getColor(),
                    location.getStatus(),
                    location.getVersion());
        }
    }
}
