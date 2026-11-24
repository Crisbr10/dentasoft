package es.crisbr.dentasoft.tenancy.internal;

import es.crisbr.dentasoft.tenancy.TenantContext;
import es.crisbr.dentasoft.tenancy.persistence.Clinic;
import es.crisbr.dentasoft.tenancy.persistence.ClinicRepository;
import es.crisbr.dentasoft.tenancy.persistence.ClinicSettings;
import es.crisbr.dentasoft.tenancy.persistence.ClinicSettingsRepository;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The active clinic's own data and settings, which {@code ADMIN_CLINICA} edits (M01-T06). Always the clinic of {@link
 * TenantContext}: no method takes a clinic id.
 *
 * <p>The clinic edits its name and time zone; its legal name and tax id are shown but only the platform corrects them
 * (M01-T11).
 */
@Service
@PreAuthorize("hasRole('ADMIN_CLINICA')")
public class ClinicConfiguration {

    private final ClinicRepository clinics;
    private final ClinicSettingsRepository settings;

    ClinicConfiguration(ClinicRepository clinics, ClinicSettingsRepository settings) {
        this.clinics = clinics;
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public ClinicDetails clinic() {
        return ClinicDetails.of(activeClinic());
    }

    /** @throws VersionConflictException if the clinic changed since the client read {@code changes.version()} */
    @Transactional
    public ClinicDetails updateClinic(ClinicChanges changes) {
        Clinic clinic = activeClinic();
        Versions.require(clinic, changes.version());
        clinic.setName(changes.name());
        clinic.setTimezone(changes.timezone());
        Versions.flush(clinics);
        return ClinicDetails.of(clinic);
    }

    @Transactional(readOnly = true)
    public SettingsDetails settings() {
        return SettingsDetails.of(activeSettings());
    }

    /** @throws VersionConflictException if the settings changed since the client read {@code changes.version()} */
    @Transactional
    public SettingsDetails updateSettings(SettingsChanges changes) {
        ClinicSettings current = activeSettings();
        Versions.require(current, changes.version());
        current.setDefaultAppointmentMinutes(changes.defaultAppointmentMinutes());
        current.setContactEmail(changes.contactEmail());
        current.setContactPhone(changes.contactPhone());
        Versions.flush(settings);
        return SettingsDetails.of(current);
    }

    /** Row Level Security shows only the active clinic's row, so a missing one is a bug, never another clinic's. */
    private Clinic activeClinic() {
        UUID id = TenantContext.current();
        return clinics.findById(id).orElseThrow(() -> new IllegalStateException("The active clinic does not exist"));
    }

    /** Created with the clinic by the platform (M01-T05), one per clinic. */
    private ClinicSettings activeSettings() {
        return settings.findAll().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("The active clinic has no settings"));
    }

    /** The clinic as its configuration API returns it. */
    public record ClinicDetails(
            UUID id, String name, String legalName, String taxId, String timezone, String locale, long version) {

        static ClinicDetails of(Clinic clinic) {
            return new ClinicDetails(
                    clinic.getId(),
                    clinic.getName(),
                    clinic.getLegalName(),
                    clinic.getTaxId(),
                    clinic.getTimezone(),
                    clinic.getLocale(),
                    clinic.getVersion());
        }
    }

    /** What the clinic changes of itself; already validated by the web layer. */
    public record ClinicChanges(String name, String timezone, long version) {}

    public record SettingsDetails(
            int defaultAppointmentMinutes,
            @Nullable String contactEmail,
            @Nullable String contactPhone,
            long version) {

        static SettingsDetails of(ClinicSettings settings) {
            return new SettingsDetails(
                    settings.getDefaultAppointmentMinutes(),
                    settings.getContactEmail(),
                    settings.getContactPhone(),
                    settings.getVersion());
        }
    }

    /** The settings the clinic changes; already validated by the web layer. */
    public record SettingsChanges(
            int defaultAppointmentMinutes,
            @Nullable String contactEmail,
            @Nullable String contactPhone,
            long version) {}
}
