package es.crisbr.dentasoft.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.crisbr.dentasoft.shared.PageLimits;
import es.crisbr.dentasoft.shared.UnsupportedSortException;
import es.crisbr.dentasoft.tenancy.TenancyTestDatabase;
import es.crisbr.dentasoft.tenancy.internal.Locations.LocationData;
import es.crisbr.dentasoft.tenancy.internal.Locations.LocationDetails;
import es.crisbr.dentasoft.tenancy.persistence.LocationStatus;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * {@code ADMIN_CLINICA} manages its clinic's treatment rooms (M01-T06): rooms are deactivated, never deleted, and two
 * active rooms of a clinic never share a name, whatever the case.
 */
class LocationsTests extends ClinicConfigurationTestBase {

    private static final PageRequest BY_NAME = PageRequest.of(0, 20, Sort.by("name"));

    @Autowired
    private Locations locations;

    @Test
    void createsARoomThatIsActive() {
        LocationDetails room = create("Gabinete 1", "#1E88E5");

        assertThat(room.name()).isEqualTo("Gabinete 1");
        assertThat(room.color()).isEqualTo("#1E88E5");
        assertThat(room.status()).isEqualTo(LocationStatus.ACTIVE);
        assertThat(room.version()).isZero();
        assertThat(asClinicA(() -> locations.get(room.id()))).isEqualTo(room);
    }

    @Test
    void refusesASecondActiveRoomWithTheSameNameWhateverTheCase() {
        create("Gabinete 1", null);

        assertThatThrownBy(() -> create("GABINETE 1", null)).isInstanceOf(LocationNameTakenException.class);
        assertThat(asClinicA(() -> locations.list(null, BY_NAME)).getTotalElements())
                .isOne();
    }

    @Test
    void anotherClinicMayUseTheSameName() {
        create("Gabinete 1", null);

        LocationDetails clinicBRoom = asClinicB(() -> locations.create(new LocationData("Gabinete 1", null)));

        assertThat(clinicBRoom.status()).isEqualTo(LocationStatus.ACTIVE);
    }

    @Test
    void anInactiveRoomFreesItsName() {
        LocationDetails old = create("Gabinete 1", null);
        asClinicA(() -> locations.deactivate(old.id(), old.version()));

        LocationDetails replacement = create("Gabinete 1", "#43A047");

        assertThat(replacement.id()).isNotEqualTo(old.id());
        assertThat(asClinicA(() -> locations.list(LocationStatus.ACTIVE, BY_NAME))
                        .getContent())
                .containsExactly(replacement);
    }

    @Test
    void renamesAndRecoloursARoom() {
        LocationDetails room = create("Gabinete 1", "#1E88E5");

        LocationDetails changed =
                asClinicA(() -> locations.update(room.id(), new LocationData("Gabinete Norte", null), room.version()));

        assertThat(changed.name()).isEqualTo("Gabinete Norte");
        assertThat(changed.color()).isNull();
        assertThat(changed.version()).isEqualTo(room.version() + 1);
        assertThat(asClinicA(() -> locations.get(room.id()))).isEqualTo(changed);
    }

    @Test
    void aRoomKeepsItsOwnNameInAnotherCase() {
        LocationDetails room = create("Gabinete 1", null);

        LocationDetails changed =
                asClinicA(() -> locations.update(room.id(), new LocationData("GABINETE 1", null), room.version()));

        assertThat(changed.name()).isEqualTo("GABINETE 1");
    }

    @Test
    void refusesRenamingARoomToTheNameOfAnotherActiveOne() {
        create("Gabinete 1", null);
        LocationDetails second = create("Gabinete 2", null);

        assertThatThrownBy(() -> asClinicA(
                        () -> locations.update(second.id(), new LocationData("gabinete 1", null), second.version())))
                .isInstanceOf(LocationNameTakenException.class);
        assertThat(asClinicA(() -> locations.get(second.id())).name()).isEqualTo("Gabinete 2");
    }

    @Test
    void refusesAChangeMadeOnAnOldVersion() {
        LocationDetails room = create("Gabinete 1", null);
        asClinicA(() -> locations.update(room.id(), new LocationData("Gabinete Norte", null), room.version()));

        assertThatThrownBy(() -> asClinicA(
                        () -> locations.update(room.id(), new LocationData("Gabinete Sur", null), room.version())))
                .isInstanceOf(VersionConflictException.class);
        assertThatThrownBy(() -> asClinicA(() -> locations.deactivate(room.id(), room.version())))
                .isInstanceOf(VersionConflictException.class);
        assertThat(asClinicA(() -> locations.get(room.id())))
                .extracting(LocationDetails::name, LocationDetails::status)
                .containsExactly("Gabinete Norte", LocationStatus.ACTIVE);
    }

    /** Deactivating is the API's delete: the row stays, inactive. */
    @Test
    void deactivatesAndActivatesARoomWithoutDeletingIt() throws SQLException {
        LocationDetails room = create("Gabinete 1", null);

        LocationDetails inactive = asClinicA(() -> locations.deactivate(room.id(), room.version()));
        assertThat(inactive.status()).isEqualTo(LocationStatus.INACTIVE);
        assertThat(TenancyTestDatabase.countRows("location", CLINIC_A)).isOne();

        LocationDetails active = asClinicA(() -> locations.activate(room.id(), inactive.version()));
        assertThat(active.status()).isEqualTo(LocationStatus.ACTIVE);
    }

    @Test
    void refusesActivatingARoomWhoseNameAnActiveOneHasTaken() {
        LocationDetails old = create("Gabinete 1", null);
        LocationDetails inactive = asClinicA(() -> locations.deactivate(old.id(), old.version()));
        create("gabinete 1", null);

        assertThatThrownBy(() -> asClinicA(() -> locations.activate(old.id(), inactive.version())))
                .isInstanceOf(LocationNameTakenException.class);
        assertThat(asClinicA(() -> locations.get(old.id())).status()).isEqualTo(LocationStatus.INACTIVE);
    }

    @Test
    void listsAllRoomsOrOnlyThoseWithAStatus() {
        LocationDetails first = create("Gabinete 1", null);
        LocationDetails second = create("Gabinete 2", null);
        LocationDetails inactive = asClinicA(() -> locations.deactivate(second.id(), second.version()));

        assertThat(asClinicA(() -> locations.list(null, BY_NAME)).getContent()).containsExactly(first, inactive);
        assertThat(asClinicA(() -> locations.list(LocationStatus.ACTIVE, BY_NAME))
                        .getContent())
                .containsExactly(first);
        assertThat(asClinicA(() -> locations.list(LocationStatus.INACTIVE, BY_NAME))
                        .getContent())
                .containsExactly(inactive);
    }

    /** Inactive rooms may share a name: the id breaks the tie, so pages neither repeat nor skip a room. */
    @Test
    void pagesThroughRoomsWithTheSameNameWithoutRepeatingAny() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            LocationDetails room = create("Gabinete 1", null);
            asClinicA(() -> locations.deactivate(room.id(), room.version()));
            ids.add(room.id());
        }

        List<UUID> paged = new ArrayList<>();
        for (int page = 0; page < ids.size(); page++) {
            PageRequest onePerPage = PageRequest.of(page, 1, Sort.by("name"));
            paged.addAll(asClinicA(() -> locations.list(null, onePerPage))
                    .map(LocationDetails::id)
                    .getContent());
        }

        assertThat(paged).containsExactlyElementsOf(ids.stream().sorted().toList());
    }

    @Test
    void listsOnlyTheActiveClinicsRooms() {
        create("Gabinete 1", null);
        asClinicB(() -> locations.create(new LocationData("Gabinete Sur", null)));

        assertThat(asClinicA(() -> locations.list(null, BY_NAME)).getContent())
                .extracting(LocationDetails::name)
                .containsExactly("Gabinete 1");
    }

    @Test
    void cutsLargePagesAndRefusesUnknownSorts() {
        assertThat(asClinicA(() -> locations.list(null, PageRequest.of(0, 1000)))
                        .getPageable()
                        .getPageSize())
                .isEqualTo(PageLimits.MAX_PAGE_SIZE);
        assertThatThrownBy(() -> asClinicA(() -> locations.list(null, PageRequest.of(0, 20, Sort.by("tenantId")))))
                .isInstanceOf(UnsupportedSortException.class);
    }

    /** Another clinic's room, or one that does not exist: the same 404, so ids of other clinics reveal nothing. */
    @Test
    void anotherClinicsRoomIsNotFound() {
        LocationDetails clinicBRoom = asClinicB(() -> locations.create(new LocationData("Gabinete Sur", null)));

        for (UUID id : new UUID[] {clinicBRoom.id(), UUID.randomUUID()}) {
            assertThatThrownBy(() -> asClinicA(() -> locations.get(id))).isInstanceOf(LocationNotFoundException.class);
            assertThatThrownBy(() -> asClinicA(() -> locations.update(id, new LocationData("Intruso", null), 0)))
                    .isInstanceOf(LocationNotFoundException.class);
            assertThatThrownBy(() -> asClinicA(() -> locations.deactivate(id, 0)))
                    .isInstanceOf(LocationNotFoundException.class);
            assertThatThrownBy(() -> asClinicA(() -> locations.activate(id, 0)))
                    .isInstanceOf(LocationNotFoundException.class);
        }
        assertThat(asClinicB(() -> locations.get(clinicBRoom.id()))).isEqualTo(clinicBRoom);
    }

    private LocationDetails create(String name, String color) {
        return asClinicA(() -> locations.create(new LocationData(name, color)));
    }
}
