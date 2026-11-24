package es.crisbr.dentasoft.platform.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.platform.ClinicCreated;
import es.crisbr.dentasoft.platform.PlatformTestDatabase;
import es.crisbr.dentasoft.platform.internal.ClinicAdministration.ClinicDetails;
import es.crisbr.dentasoft.platform.internal.ClinicAdministration.NewClinic;
import es.crisbr.dentasoft.platform.persistence.ClinicStatus;
import es.crisbr.dentasoft.tenancy.TenantContext;
import es.crisbr.dentasoft.tenancy.persistence.ClinicSettings;
import es.crisbr.dentasoft.tenancy.persistence.ClinicSettingsRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.engine.spi.SessionImplementor;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.support.TransactionTemplate;

/** What {@code PLATFORM_ADMIN} can do with clinics (M01-T05), through the service and against the database. */
@IntegrationTest
@RecordApplicationEvents
@WithMockUser(roles = "PLATFORM_ADMIN")
class ClinicAdministrationTests {

    /** Names unique to this run, so the assertions only look at the clinics this class creates. */
    private static final String PREFIX = "Clínica de prueba " + UUID.randomUUID() + " ";

    @Autowired
    private ClinicAdministration administration;

    @Autowired
    private ClinicCreatedProbe probe;

    @Autowired
    private ClinicSettingsRepository settings;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private ApplicationEvents events;

    private final List<UUID> created = new ArrayList<>();

    @BeforeEach
    void resetProbe() {
        probe.reset();
    }

    @AfterEach
    void deleteCreatedClinics() throws SQLException {
        if (probe.clinic != null) {
            created.add(probe.clinic);
        }
        PlatformTestDatabase.deleteClinics(created);
    }

    @Test
    void createsAnActiveClinicWithDefaultsAndItsSettings() throws SQLException {
        ClinicDetails clinic = create("Norte", null, null);

        assertThat(clinic.id().version()).isEqualTo(7);
        assertThat(clinic.name()).isEqualTo(PREFIX + "Norte");
        assertThat(clinic.legalName()).isEqualTo(PREFIX + "Norte S.L.");
        assertThat(clinic.taxId()).isEqualTo("B12345678");
        assertThat(clinic.timezone()).isEqualTo("Europe/Madrid");
        assertThat(clinic.locale()).isEqualTo("es-ES");
        assertThat(clinic.status()).isEqualTo(ClinicStatus.ACTIVE);
        assertThat(clinic.createdAt()).isNotNull();
        assertThat(PlatformTestDatabase.status(clinic.id())).contains("ACTIVE");
        assertThat(PlatformTestDatabase.defaultAppointmentMinutes(clinic.id()))
                .contains(ClinicSettings.DEFAULT_APPOINTMENT_MINUTES);
    }

    @Test
    void keepsTheGivenTimezoneAndLocale() {
        ClinicDetails clinic = create("Canarias", "Atlantic/Canary", "ca-ES");

        assertThat(clinic.timezone()).isEqualTo("Atlantic/Canary");
        assertThat(clinic.locale()).isEqualTo("ca-ES");
    }

    @Test
    void theNewClinicReadsItsOwnSettingsAsTheApplication() {
        ClinicDetails clinic = create("Sur", null, null);

        List<ClinicSettings> own =
                TenantContext.callAs(clinic.id(), () -> transaction.execute(status -> settings.findAll()));

        assertThat(own).singleElement().satisfies(row -> {
            assertThat(row.getTenantId()).isEqualTo(clinic.id());
            assertThat(row.getId().version()).isEqualTo(7);
            assertThat(row.getDefaultAppointmentMinutes()).isEqualTo(ClinicSettings.DEFAULT_APPOINTMENT_MINUTES);
            assertThat(row.getContactEmail()).isNull();
        });
    }

    @Test
    void publishesClinicCreatedOnceBothRowsAreWritten() {
        ClinicDetails clinic = create("Este", null, null);

        assertThat(events.stream(ClinicCreated.class)).containsExactly(new ClinicCreated(clinic.id()));
        assertThat(probe.clinic).isEqualTo(clinic.id());
        assertThat(probe.clinicRowsWhenPublished).isEqualTo(1);
        assertThat(probe.pendingWritesWhenPublished)
                .as("inserts not yet sent when published")
                .isFalse();
    }

    @Test
    void createsTheClinicAndItsSettingsInOneTransaction() throws SQLException {
        probe.failWith = new IllegalStateException("listener failure");

        assertThatThrownBy(() -> create("Oeste", null, null)).isSameAs(probe.failWith);

        UUID clinic = probe.clinic;
        assertThat(clinic).isNotNull();
        assertThat(PlatformTestDatabase.status(clinic)).isEmpty();
        assertThat(PlatformTestDatabase.defaultAppointmentMinutes(clinic)).isEmpty();
    }

    @Test
    void listsClinicsInPagesSortedAsAsked() {
        UUID beta = create("Beta", null, null).id();
        UUID alfa = create("Alfa", null, null).id();
        UUID gamma = create("Gamma", null, null).id();

        List<UUID> byName = administration.list(PageRequest.of(0, 100, Sort.by("name"))).stream()
                .map(ClinicDetails::id)
                .filter(created::contains)
                .toList();
        List<UUID> byNameDescending = administration
                .list(PageRequest.of(0, 100, Sort.by("name").descending()))
                .stream()
                .map(ClinicDetails::id)
                .filter(created::contains)
                .toList();

        assertThat(byName).containsExactly(alfa, beta, gamma);
        assertThat(byNameDescending).containsExactly(gamma, beta, alfa);
    }

    @Test
    void pagesDoNotOverlapAndReportTheTotal() {
        create("Uno", null, null);
        create("Dos", null, null);
        create("Tres", null, null);

        var first = administration.list(PageRequest.of(0, 2, Sort.by("createdAt")));
        var second = administration.list(PageRequest.of(1, 2, Sort.by("createdAt")));

        assertThat(first.getContent()).hasSize(2);
        assertThat(first.getTotalElements()).isGreaterThanOrEqualTo(3);
        assertThat(second.getContent()).doesNotContainAnyElementsOf(first.getContent());
    }

    @Test
    void cutsPagesLargerThanTheMaximum() {
        create("Grande", null, null);

        assertThat(administration.list(PageRequest.of(0, 1_000)).getSize())
                .isEqualTo(ClinicAdministration.MAX_PAGE_SIZE);
    }

    @Test
    void rejectsSortingByAPropertyThatIsNotSortable() {
        assertThatThrownBy(() -> administration.list(PageRequest.of(0, 10, Sort.by("keycloakOrgId"))))
                .isInstanceOf(UnsupportedSortException.class);
        assertThatThrownBy(() -> administration.list(PageRequest.of(0, 10, Sort.by("name; drop table clinic"))))
                .isInstanceOf(UnsupportedSortException.class);
    }

    @Test
    void suspendsAndReactivatesAClinic() throws SQLException {
        ClinicDetails clinic = create("Centro", null, null);

        ClinicDetails suspended = administration.suspend(clinic.id());
        assertThat(suspended.status()).isEqualTo(ClinicStatus.SUSPENDED);
        assertThat(PlatformTestDatabase.status(clinic.id())).contains("SUSPENDED");
        assertThat(suspended.name()).isEqualTo(clinic.name());

        ClinicDetails reactivated = administration.reactivate(clinic.id());
        assertThat(reactivated.status()).isEqualTo(ClinicStatus.ACTIVE);
        assertThat(PlatformTestDatabase.status(clinic.id())).contains("ACTIVE");
        assertThat(reactivated.updatedAt()).isAfterOrEqualTo(suspended.updatedAt());
    }

    @Test
    void refusesToSuspendASuspendedClinicOrReactivateAnActiveOne() throws SQLException {
        ClinicDetails clinic = create("Levante", null, null);

        assertThatThrownBy(() -> administration.reactivate(clinic.id()))
                .isInstanceOf(ClinicStatusConflictException.class);
        administration.suspend(clinic.id());
        assertThatThrownBy(() -> administration.suspend(clinic.id())).isInstanceOf(ClinicStatusConflictException.class);
        assertThat(PlatformTestDatabase.status(clinic.id())).contains("SUSPENDED");
    }

    @Test
    void anUnknownClinicIsNotFound() {
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> administration.suspend(unknown)).isInstanceOf(ClinicNotFoundException.class);
        assertThatThrownBy(() -> administration.reactivate(unknown)).isInstanceOf(ClinicNotFoundException.class);
    }

    @Test
    void refusesToRunWithAnActiveClinic() {
        ClinicDetails clinic = create("Interior", null, null);
        int before = events.stream(ClinicCreated.class).toList().size();

        TenantContext.runAs(clinic.id(), () -> {
            assertThatThrownBy(() -> administration.list(PageRequest.of(0, 10)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("active clinic");
            assertThatThrownBy(() -> administration.suspend(clinic.id())).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> administration.create(new NewClinic("x", "x", "B1", null, null)))
                    .isInstanceOf(IllegalStateException.class);
        });

        assertThat(events.stream(ClinicCreated.class).toList()).hasSize(before);
    }

    private ClinicDetails create(String name, @Nullable String timezone, @Nullable String locale) {
        ClinicDetails clinic = administration.create(
                new NewClinic(PREFIX + name, PREFIX + name + " S.L.", "B12345678", timezone, locale));
        created.add(clinic.id());
        return clinic;
    }

    /**
     * Listens to {@link ClinicCreated} synchronously, inside the creating transaction, to see what was written when
     * it was published; it can also fail, to roll the creation back.
     */
    static class ClinicCreatedProbe {

        @PersistenceContext(unitName = "platform")
        private EntityManager platform;

        @Nullable
        UUID clinic;

        long clinicRowsWhenPublished;
        boolean pendingWritesWhenPublished;

        @Nullable
        RuntimeException failWith;

        void reset() {
            clinic = null;
            clinicRowsWhenPublished = 0;
            pendingWritesWhenPublished = true;
            failWith = null;
        }

        @EventListener
        void on(ClinicCreated event) {
            clinic = event.clinicId();
            // Before the query below, which would flush whatever is pending.
            pendingWritesWhenPublished =
                    platform.unwrap(SessionImplementor.class).getActionQueue().hasAnyQueuedActions();
            clinicRowsWhenPublished = ((Number) platform.createNativeQuery("SELECT count(*) FROM clinic WHERE id = ?")
                            .setParameter(1, event.clinicId())
                            .getSingleResult())
                    .longValue();
            if (failWith != null) {
                throw failWith;
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {

        @Bean
        ClinicCreatedProbe clinicCreatedProbe() {
            return new ClinicCreatedProbe();
        }
    }
}
