package es.crisbr.dentasoft.platform.internal;

import es.crisbr.dentasoft.platform.ClinicCreated;
import es.crisbr.dentasoft.platform.persistence.ClinicStatus;
import es.crisbr.dentasoft.platform.persistence.PlatformClinic;
import es.crisbr.dentasoft.platform.persistence.PlatformClinicSettings;
import es.crisbr.dentasoft.platform.persistence.PlatformClinics;
import es.crisbr.dentasoft.shared.PageLimits;
import es.crisbr.dentasoft.tenancy.TenantContext;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Clinic administration for {@code PLATFORM_ADMIN}: create, list, suspend and reactivate clinics (M01-T05). Every
 * method runs in the platform unit, as {@code dentasoft_platform} (ADR 0011), and without an active clinic.
 */
@Service
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
public class ClinicAdministration {

    private final PlatformClinics clinics;
    private final ApplicationEventPublisher events;

    ClinicAdministration(PlatformClinics clinics, ApplicationEventPublisher events) {
        this.clinics = clinics;
        this.events = events;
    }

    /** A clinic and its initial settings, in one transaction, then {@link ClinicCreated}. */
    @Transactional(PlatformPersistenceConfiguration.TRANSACTION_MANAGER)
    public ClinicDetails create(NewClinic newClinic) {
        requireNoActiveClinic();
        PlatformClinic clinic = new PlatformClinic(
                newClinic.name(),
                newClinic.legalName(),
                newClinic.taxId(),
                orDefault(newClinic.timezone(), PlatformClinic.DEFAULT_TIMEZONE),
                orDefault(newClinic.locale(), PlatformClinic.DEFAULT_LOCALE));
        // The id is generated on persist, and the settings need it.
        clinics.add(clinic);
        clinics.add(new PlatformClinicSettings(clinic.getId()));
        // Both rows are written before anyone hears of the clinic.
        clinics.flush();
        events.publishEvent(new ClinicCreated(clinic.getId()));
        return ClinicDetails.of(clinic);
    }

    @Transactional(value = PlatformPersistenceConfiguration.TRANSACTION_MANAGER, readOnly = true)
    public Page<ClinicDetails> list(Pageable pageable) {
        requireNoActiveClinic();
        Pageable bounded = PageLimits.bounded(pageable, PlatformClinics.SORTABLE.keySet());
        return clinics.page(bounded).map(ClinicDetails::of);
    }

    @Transactional(PlatformPersistenceConfiguration.TRANSACTION_MANAGER)
    public ClinicDetails suspend(UUID clinicId) {
        return changeStatus(clinicId, ClinicStatus.ACTIVE, ClinicStatus.SUSPENDED);
    }

    @Transactional(PlatformPersistenceConfiguration.TRANSACTION_MANAGER)
    public ClinicDetails reactivate(UUID clinicId) {
        return changeStatus(clinicId, ClinicStatus.SUSPENDED, ClinicStatus.ACTIVE);
    }

    private ClinicDetails changeStatus(UUID clinicId, ClinicStatus from, ClinicStatus to) {
        requireNoActiveClinic();
        PlatformClinic clinic = clinics.find(clinicId).orElseThrow(ClinicNotFoundException::new);
        if (clinic.getStatus() != from) {
            throw new ClinicStatusConflictException(to);
        }
        clinic.setStatus(to);
        try {
            clinics.flush();
        } catch (OptimisticLockingFailureException concurrentChange) {
            // Another request changed the clinic between the read and this write: it already has another status.
            throw new ClinicStatusConflictException(to);
        }
        return ClinicDetails.of(clinic);
    }

    /**
     * Platform administration works across clinics: mixing it with a clinic's work would be a bug. The transaction
     * has already begun, so it rolls back.
     */
    private static void requireNoActiveClinic() {
        if (TenantContext.isBound()) {
            throw new IllegalStateException("Platform administration cannot run with an active clinic");
        }
    }

    private static String orDefault(@Nullable String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    /** What is needed to create a clinic; already validated by the web layer. */
    public record NewClinic(
            String name,
            String legalName,
            String taxId,
            @Nullable String timezone,
            @Nullable String locale) {}

    /** A clinic as the platform API returns it. */
    public record ClinicDetails(
            UUID id,
            String name,
            String legalName,
            String taxId,
            String timezone,
            String locale,
            ClinicStatus status,
            Instant createdAt,
            Instant updatedAt) {

        static ClinicDetails of(PlatformClinic clinic) {
            return new ClinicDetails(
                    clinic.getId(),
                    clinic.getName(),
                    clinic.getLegalName(),
                    clinic.getTaxId(),
                    clinic.getTimezone(),
                    clinic.getLocale(),
                    clinic.getStatus(),
                    clinic.getCreatedAt(),
                    clinic.getUpdatedAt());
        }
    }
}
