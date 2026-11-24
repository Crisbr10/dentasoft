package es.crisbr.dentasoft.tenancy.persistence;

import es.crisbr.dentasoft.tenancy.TenantIsolationTest;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.JpaRepository;

/** A clinic never reads nor changes another clinic's settings (V002, M01-T05). */
class ClinicSettingsIsolationTests extends TenantIsolationTest<ClinicSettings> {

    @Autowired
    private ClinicSettingsRepository settings;

    @Override
    protected JpaRepository<ClinicSettings, UUID> repository() {
        return settings;
    }

    @Override
    protected String table() {
        return "clinic_settings";
    }

    @Override
    protected ClinicSettings newEntity(int n) {
        return new ClinicSettings(ClinicSettings.DEFAULT_APPOINTMENT_MINUTES + 15 * n);
    }

    @Override
    protected void modify(ClinicSettings clinicSettings) {
        clinicSettings.setDefaultAppointmentMinutes(clinicSettings.getDefaultAppointmentMinutes() + 5);
    }
}
