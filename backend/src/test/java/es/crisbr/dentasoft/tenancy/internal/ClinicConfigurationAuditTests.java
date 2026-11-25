package es.crisbr.dentasoft.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import es.crisbr.dentasoft.audit.AuditLogDatabase;
import es.crisbr.dentasoft.audit.AuditLogDatabase.StoredEntry;
import es.crisbr.dentasoft.tenancy.TenancyTestDatabase;
import es.crisbr.dentasoft.tenancy.internal.ClinicConfiguration.ClinicChanges;
import es.crisbr.dentasoft.tenancy.internal.ClinicOpeningHours.Slot;
import es.crisbr.dentasoft.tenancy.internal.Locations.LocationData;
import es.crisbr.dentasoft.tenancy.internal.Locations.LocationDetails;
import es.crisbr.dentasoft.tenancy.persistence.HoursOfOperation;
import es.crisbr.dentasoft.tenancy.persistence.HoursOfOperationRepository;
import es.crisbr.dentasoft.tenancy.persistence.LocationRepository;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The clinic's configuration (M01-T06) is audited (M01-T07): every change of the clinic, its rooms and its opening
 * hours leaves an entry in {@code audit_log}, in the same transaction, and a change whose entry cannot be written does
 * not happen.
 */
class ClinicConfigurationAuditTests extends ClinicConfigurationTestBase {

    @Autowired
    private Locations locations;

    @Autowired
    private ClinicConfiguration configuration;

    @Autowired
    private ClinicOpeningHours openingHours;

    @Autowired
    private HoursOfOperationRepository hours;

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcClient jdbc;

    @Test
    void everyChangeOfARoomIsRecordedUnderItsClinic() throws SQLException {
        LocationDetails created = asClinicA(() -> locations.create(new LocationData("Gabinete 1", null)));
        LocationDetails renamed = asClinicA(
                () -> locations.update(created.id(), new LocationData("Gabinete 1 bis", null), created.version()));
        asClinicA(() -> locations.deactivate(created.id(), renamed.version()));

        List<StoredEntry> entries = AuditLogDatabase.entries(CLINIC_A, created.id());
        assertThat(entries).extracting(StoredEntry::action).containsExactly("CREATE", "UPDATE", "UPDATE");
        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry.tenantId()).isEqualTo(CLINIC_A);
            assertThat(entry.entityType()).isEqualTo("LOCATION");
            assertThat(entry.entityId()).isEqualTo(created.id());
            assertThat(entry.outcome()).isEqualTo("SUCCESS");
            // No users until M02: the system acts, without an id. No request here, so no request id either.
            assertThat(entry.actorType()).isEqualTo("SYSTEM");
            assertThat(entry.actorId()).isNull();
            assertThat(entry.requestId()).isNull();
            assertThat(entry.details()).isNull();
        });
        assertThat(AuditLogDatabase.entries(CLINIC_B, created.id())).isEmpty();
    }

    @Test
    void changingTheClinicIsRecorded() throws SQLException {
        // The clinics of these tests keep their ids, and entries are never deleted: compare with what was there.
        int before = AuditLogDatabase.entries(CLINIC_A, CLINIC_A).size();
        long version = asClinicA(() -> configuration.clinic().version());

        asClinicA(() ->
                configuration.updateClinic(new ClinicChanges("Clínica A renombrada", "Atlantic/Canary", version)));

        List<StoredEntry> entries = AuditLogDatabase.entries(CLINIC_A, CLINIC_A);
        assertThat(entries.subList(before, entries.size())).singleElement().satisfies(entry -> {
            assertThat(entry.action()).isEqualTo("UPDATE");
            assertThat(entry.entityType()).isEqualTo("CLINIC");
        });
    }

    /** Replacing the week deletes the old slots one by one, so each deletion is recorded too. */
    @Test
    void replacingTheOpeningHoursRecordsEverySlotCreatedAndDeleted() throws SQLException {
        asClinicA(() -> openingHours.replaceWeek(List.of(
                new Slot(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(14, 0)),
                new Slot(DayOfWeek.MONDAY, LocalTime.of(16, 0), LocalTime.of(20, 0)))));
        List<UUID> first = slotIds();

        asClinicA(() -> openingHours.replaceWeek(
                List.of(new Slot(DayOfWeek.TUESDAY, LocalTime.of(9, 0), LocalTime.of(14, 0)))));
        List<UUID> second = slotIds();

        assertThat(first).hasSize(2);
        for (UUID slot : first) {
            assertThat(AuditLogDatabase.entries(CLINIC_A, slot))
                    .extracting(StoredEntry::action, StoredEntry::entityType)
                    .containsExactly(tuple("CREATE", "HOURS_OF_OPERATION"), tuple("DELETE", "HOURS_OF_OPERATION"));
        }
        assertThat(second)
                .singleElement()
                .satisfies(slot ->
                        assertThat(AuditLogDatabase.actions(CLINIC_A, slot)).containsExactly("CREATE"));
    }

    /** The entry is written in the change's transaction: when the change rolls back, its entry goes with it. */
    @Test
    void aRolledBackChangeLeavesNoEntry() throws SQLException {
        UUID id = asClinicA(() -> transaction.execute(status -> {
            UUID created =
                    locations.create(new LocationData("Gabinete efímero", null)).id();
            locationRepository.flush();
            // The entry was written inside this transaction, which is about to roll back.
            assertThat(auditInsertsInThisTransaction()).isPositive();
            status.setRollbackOnly();
            return created;
        }));

        assertThat(AuditLogDatabase.entries(CLINIC_A, id)).isEmpty();
        assertThat(TenancyTestDatabase.countRows("location", CLINIC_A)).isZero();
    }

    /** No change goes unrecorded: if the entry cannot be written, the change fails and nothing is stored. */
    @Test
    void aChangeWhoseEntryCannotBeWrittenFails() throws SQLException {
        AuditLogDatabase.withoutApplicationInsert(() -> assertThatThrownBy(
                        () -> asClinicA(() -> locations.create(new LocationData("Gabinete sin registro", null))))
                .isInstanceOf(RuntimeException.class));

        assertThat(TenancyTestDatabase.countRows("location", CLINIC_A)).isZero();
    }

    private List<UUID> slotIds() {
        return asClinicA(() -> transaction.execute(
                status -> hours.findAll().stream().map(HoursOfOperation::getId).toList()));
    }

    /** Rows inserted into {@code audit_log} by the current transaction, which only that transaction can see. */
    private long auditInsertsInThisTransaction() {
        return jdbc.sql("SELECT n_tup_ins FROM pg_stat_xact_user_tables WHERE relname = 'audit_log'")
                .query(Long.class)
                .single();
    }
}
