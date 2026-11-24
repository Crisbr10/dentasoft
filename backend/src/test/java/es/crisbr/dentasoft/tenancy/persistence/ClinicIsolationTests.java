package es.crisbr.dentasoft.tenancy.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.TestData;
import es.crisbr.dentasoft.tenancy.TenancyTestDatabase;
import es.crisbr.dentasoft.tenancy.TenantContext;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Clinic A never reads nor changes clinic B itself. {@link Clinic} is not a {@code TenantScopedEntity}: its policy
 * compares {@code id}, Hibernate adds no filter and {@code TenantIsolationTest} does not apply, so Row Level Security is
 * the only layer and this test checks it on its own, through JPA and through native SQL.
 *
 * <p>After every attempt, clinic B's row is read back as the owner and compared column by column.
 */
@IntegrationTest
class ClinicIsolationTests {

    private static final UUID CLINIC_A = TestData.CLINIC_A.id();
    private static final UUID CLINIC_B = TestData.CLINIC_B.id();

    @Autowired
    private ClinicRepository clinics;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcClient jdbc;

    private String clinicBBefore;

    @BeforeEach
    void createClinics() throws SQLException {
        TenancyTestDatabase.insertClinics();
        clinicBBefore = storedClinicB();
    }

    @AfterEach
    void deleteClinics() throws SQLException {
        TenancyTestDatabase.deleteClinicsAnd(List.of());
    }

    @Test
    void clinicASeesOnlyItself() {
        TenantContext.runAs(
                CLINIC_A,
                () -> transaction.executeWithoutResult(status -> {
                    assertThat(clinics.findAll()).extracting(Clinic::getId).containsExactly(CLINIC_A);
                    assertThat(clinics.findById(CLINIC_B)).isEmpty();
                    assertThat(clinics.lockById(CLINIC_B)).isEmpty();
                    assertThat(jdbc.sql("SELECT count(*) FROM clinic WHERE id = ?")
                                    .param(CLINIC_B)
                                    .query(Long.class)
                                    .single())
                            .isZero();
                }));
    }

    /** Clinic B's entity, loaded by clinic B and saved by clinic A, never reaches clinic B's row. */
    @Test
    void clinicACannotChangeClinicBThroughJpa() throws SQLException {
        Clinic clinicB = TenantContext.callAs(
                CLINIC_B,
                () -> transaction.execute(status -> clinics.findById(CLINIC_B).orElseThrow()));
        clinicB.setName("Clínica Prueba Cambiada");
        clinicB.setTimezone("Atlantic/Canary");

        catchThrowable(() -> TenantContext.runAs(
                CLINIC_A,
                () -> transaction.executeWithoutResult(status -> {
                    clinics.save(clinicB);
                    clinics.flush();
                })));

        assertThat(storedClinicB()).isEqualTo(clinicBBefore);
    }

    @Test
    void clinicACannotChangeClinicBThroughSql() throws SQLException {
        Integer changed = TenantContext.callAs(
                CLINIC_A,
                () -> transaction.execute(status -> jdbc.sql(
                                "UPDATE clinic SET name = 'Clínica Prueba Cambiada', timezone = 'Atlantic/Canary'"
                                        + " WHERE id = ?")
                        .param(CLINIC_B)
                        .update()));

        assertThat(changed).isZero();
        assertThat(storedClinicB()).isEqualTo(clinicBBefore);
    }

    private static String storedClinicB() throws SQLException {
        return TenancyTestDatabase.storedRow("clinic", CLINIC_B, CLINIC_B).orElseThrow();
    }
}
