package es.crisbr.dentasoft.tenancy.internal;

import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.TestData;
import es.crisbr.dentasoft.WithJwtUser;
import es.crisbr.dentasoft.tenancy.TenancyTestDatabase;
import es.crisbr.dentasoft.tenancy.TenantContext;
import es.crisbr.dentasoft.tenancy.persistence.ClinicSettings;
import es.crisbr.dentasoft.tenancy.persistence.ClinicSettingsRepository;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Clinics A and B, each with its settings as the platform creates them (M01-T05), and a clinic administrator calling
 * the services. Every call goes through the real path: {@link TenantContext#callAs} and the service's own transaction.
 */
@IntegrationTest
@WithJwtUser(roles = "ADMIN_CLINICA")
public abstract class ClinicConfigurationTestBase {

    public static final UUID CLINIC_A = TestData.CLINIC_A.id();
    public static final UUID CLINIC_B = TestData.CLINIC_B.id();

    /** Children first: they refer to the clinic. */
    private static final List<String> TABLES = List.of("clinic_settings", "clinic_hours_of_operation", "location");

    @Autowired
    private ClinicSettingsRepository settings;

    @Autowired
    private TransactionTemplate transaction;

    @BeforeEach
    protected final void createClinics() throws SQLException {
        TenancyTestDatabase.insertClinics();
        for (UUID clinic : List.of(CLINIC_A, CLINIC_B)) {
            TenantContext.runAs(
                    clinic,
                    () -> transaction.executeWithoutResult(
                            status -> settings.save(new ClinicSettings(ClinicSettings.DEFAULT_APPOINTMENT_MINUTES))));
        }
    }

    @AfterEach
    protected final void deleteClinics() throws SQLException {
        TenancyTestDatabase.deleteClinicsAnd(TABLES);
    }

    public static <T> T asClinicA(ScopedValue.CallableOp<T, RuntimeException> operation) {
        return TenantContext.callAs(CLINIC_A, operation);
    }

    public static <T> T asClinicB(ScopedValue.CallableOp<T, RuntimeException> operation) {
        return TenantContext.callAs(CLINIC_B, operation);
    }
}
