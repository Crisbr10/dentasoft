package es.crisbr.dentasoft.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.crisbr.dentasoft.TestData;
import es.crisbr.dentasoft.tenancy.TenancyTestDatabase;
import es.crisbr.dentasoft.tenancy.internal.ClinicConfiguration.ClinicChanges;
import es.crisbr.dentasoft.tenancy.internal.ClinicConfiguration.ClinicDetails;
import es.crisbr.dentasoft.tenancy.internal.ClinicConfiguration.SettingsChanges;
import es.crisbr.dentasoft.tenancy.internal.ClinicConfiguration.SettingsDetails;
import es.crisbr.dentasoft.tenancy.persistence.Clinic;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** {@code ADMIN_CLINICA} reads and edits its own clinic's data and settings, and nothing else (M01-T06). */
class ClinicConfigurationTests extends ClinicConfigurationTestBase {

    @Autowired
    private ClinicConfiguration configuration;

    /** A clinic created without a time zone (TenancyTestDatabase sets none) is in Europe/Madrid. */
    @Test
    void readsTheActiveClinicWithEuropeMadridByDefault() {
        ClinicDetails clinic = asClinicA(configuration::clinic);

        assertThat(clinic.id()).isEqualTo(CLINIC_A);
        assertThat(clinic.name()).isEqualTo(TestData.CLINIC_A.name());
        assertThat(clinic.legalName()).isEqualTo(TestData.CLINIC_A.name() + " S.L.");
        assertThat(clinic.timezone()).isEqualTo(Clinic.DEFAULT_TIMEZONE).isEqualTo("Europe/Madrid");
        assertThat(clinic.locale()).isEqualTo("es-ES");
        assertThat(asClinicB(configuration::clinic).id()).isEqualTo(CLINIC_B);
    }

    @Test
    void renamesTheClinicAndChangesItsTimeZoneOnly() throws SQLException {
        ClinicDetails before = asClinicA(configuration::clinic);
        String clinicB =
                TenancyTestDatabase.storedRow("clinic", CLINIC_B, CLINIC_B).orElseThrow();

        ClinicDetails after = asClinicA(() -> configuration.updateClinic(
                new ClinicChanges("Clínica Prueba Renombrada", "Atlantic/Canary", before.version())));

        assertThat(after.name()).isEqualTo("Clínica Prueba Renombrada");
        assertThat(after.timezone()).isEqualTo("Atlantic/Canary");
        assertThat(after.version()).isEqualTo(before.version() + 1);
        assertThat(after.legalName()).isEqualTo(before.legalName());
        assertThat(after.taxId()).isEqualTo(before.taxId());
        assertThat(asClinicA(configuration::clinic)).isEqualTo(after);
        assertThat(TenancyTestDatabase.storedRow("clinic", CLINIC_B, CLINIC_B)).contains(clinicB);
    }

    @Test
    void refusesAChangeMadeOnAnOldVersion() {
        ClinicDetails before = asClinicA(configuration::clinic);
        asClinicA(() -> configuration.updateClinic(
                new ClinicChanges("Clínica Prueba Primera", "Europe/Madrid", before.version())));

        assertThatThrownBy(() -> asClinicA(() -> configuration.updateClinic(
                        new ClinicChanges("Clínica Prueba Segunda", "Europe/Madrid", before.version()))))
                .isInstanceOf(VersionConflictException.class);
        assertThat(asClinicA(configuration::clinic).name()).isEqualTo("Clínica Prueba Primera");
    }

    @Test
    void readsAndChangesTheSettingsOfTheActiveClinicOnly() {
        SettingsDetails before = asClinicA(configuration::settings);
        SettingsDetails clinicB = asClinicB(configuration::settings);

        SettingsDetails after = asClinicA(() -> configuration.updateSettings(
                new SettingsChanges(45, "recepcion@clinica-norte.test", "+34 600 000 000", before.version())));

        assertThat(after.defaultAppointmentMinutes()).isEqualTo(45);
        assertThat(after.contactEmail()).isEqualTo("recepcion@clinica-norte.test");
        assertThat(after.contactPhone()).isEqualTo("+34 600 000 000");
        assertThat(after.version()).isEqualTo(before.version() + 1);
        assertThat(asClinicA(configuration::settings)).isEqualTo(after);
        assertThat(asClinicB(configuration::settings)).isEqualTo(clinicB);
    }

    @Test
    void refusesASettingsChangeMadeOnAnOldVersion() {
        SettingsDetails before = asClinicA(configuration::settings);
        asClinicA(() -> configuration.updateSettings(new SettingsChanges(40, null, null, before.version())));

        assertThatThrownBy(() -> asClinicA(
                        () -> configuration.updateSettings(new SettingsChanges(60, null, null, before.version()))))
                .isInstanceOf(VersionConflictException.class);
        assertThat(asClinicA(configuration::settings).defaultAppointmentMinutes())
                .isEqualTo(40);
    }
}
