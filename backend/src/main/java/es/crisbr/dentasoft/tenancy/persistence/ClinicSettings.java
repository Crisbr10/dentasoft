package es.crisbr.dentasoft.tenancy.persistence;

import es.crisbr.dentasoft.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

/**
 * Settings of the active clinic, one row per clinic ({@code clinic_settings}, V002). The platform creates the row
 * with the clinic (M01-T05); the clinic edits it (M01-T06).
 *
 * <p>Never holds secrets: channel credentials (SMTP, WhatsApp) go to their own encrypted table (M07-T07).
 */
@Entity
@Table(name = "clinic_settings")
public class ClinicSettings extends TenantScopedEntity {

    /** Default length of a new appointment, as in the column's DEFAULT. */
    public static final int DEFAULT_APPOINTMENT_MINUTES = 30;

    @Column(name = "default_appointment_minutes", nullable = false)
    private int defaultAppointmentMinutes = DEFAULT_APPOINTMENT_MINUTES;

    @Column(name = "contact_email", length = 254)
    private @Nullable String contactEmail;

    @Column(name = "contact_phone", length = 32)
    private @Nullable String contactPhone;

    protected ClinicSettings() {}

    public ClinicSettings(int defaultAppointmentMinutes) {
        this.defaultAppointmentMinutes = defaultAppointmentMinutes;
    }

    public int getDefaultAppointmentMinutes() {
        return defaultAppointmentMinutes;
    }

    public void setDefaultAppointmentMinutes(int defaultAppointmentMinutes) {
        this.defaultAppointmentMinutes = defaultAppointmentMinutes;
    }

    public @Nullable String getContactEmail() {
        return contactEmail;
    }

    public void setContactEmail(@Nullable String contactEmail) {
        this.contactEmail = contactEmail;
    }

    public @Nullable String getContactPhone() {
        return contactPhone;
    }

    public void setContactPhone(@Nullable String contactPhone) {
        this.contactPhone = contactPhone;
    }
}
