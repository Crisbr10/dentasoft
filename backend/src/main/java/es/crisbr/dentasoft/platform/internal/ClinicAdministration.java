package es.crisbr.dentasoft.platform.internal;

import es.crisbr.dentasoft.audit.AuditAction;
import es.crisbr.dentasoft.audit.AuditDetails;
import es.crisbr.dentasoft.audit.AuditSubtype;
import es.crisbr.dentasoft.platform.ClinicCreated;
import es.crisbr.dentasoft.platform.persistence.ClinicStatus;
import es.crisbr.dentasoft.platform.persistence.PlatformAuditRecord;
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
 * Clinic administration for {@code PLATFORM_ADMIN}: create, list, suspend and reactivate clinics (M01-T05), and
 * correct their fiscal data (M01-T11). Every
 * method runs in the platform unit, as {@code dentasoft_platform} (ADR 0011), and without an active clinic.
 *
 * <p>Every change is recorded in {@code audit_log} under the affected clinic, in the same transaction (ADR 0012): if the
 * entry cannot be written, the change fails too.
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
        clinics.add(PlatformAuditRecord.ofClinic(clinic.getId(), AuditAction.CREATE, null));
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
        return changeStatus(clinicId, ClinicStatus.ACTIVE, ClinicStatus.SUSPENDED, AuditSubtype.CLINIC_SUSPENDED);
    }

    @Transactional(PlatformPersistenceConfiguration.TRANSACTION_MANAGER)
    public ClinicDetails reactivate(UUID clinicId) {
        return changeStatus(clinicId, ClinicStatus.SUSPENDED, ClinicStatus.ACTIVE, AuditSubtype.CLINIC_REACTIVATED);
    }

    /**
     * Corrects the clinic's legal name and tax identifier, which the clinic itself cannot change. The values are not
     * recorded in the audit log: only that they were corrected. Sending the values the clinic already has changes
     * nothing and records nothing, whatever the version, so retrying a correction already made is safe.
     *
     * @throws ClinicVersionConflictException if the values differ from the clinic's and the clinic changed since the
     *     client read {@code correction.version()}
     */
    @Transactional(PlatformPersistenceConfiguration.TRANSACTION_MANAGER)
    public ClinicDetails correctFiscalData(UUID clinicId, FiscalDataCorrection correction) {
        requireNoActiveClinic();
        PlatformClinic clinic = clinics.find(clinicId).orElseThrow(ClinicNotFoundException::new);
        // Checked before the version, so a client that retries a correction already made is not told it conflicts.
        if (clinic.hasFiscalData(correction.legalName(), correction.taxId())) {
            return ClinicDetails.of(clinic);
        }
        // @Version only compares the version loaded in this transaction: the one the client read is compared here.
        Long current = clinic.getVersion();
        if (current == null || current != correction.version()) {
            throw new ClinicVersionConflictException();
        }
        clinic.correctFiscalData(correction.legalName(), correction.taxId());
        clinics.add(PlatformAuditRecord.ofClinic(
                clinicId, AuditAction.UPDATE, AuditDetails.of(AuditSubtype.CLINIC_FISCAL_DATA_CORRECTED)));
        try {
            clinics.flush();
        } catch (OptimisticLockingFailureException concurrentChange) {
            // Another request changed the clinic between the read and this write.
            throw new ClinicVersionConflictException();
        }
        return ClinicDetails.of(clinic);
    }

    private ClinicDetails changeStatus(UUID clinicId, ClinicStatus from, ClinicStatus to, AuditSubtype subtype) {
        requireNoActiveClinic();
        PlatformClinic clinic = clinics.find(clinicId).orElseThrow(ClinicNotFoundException::new);
        if (clinic.getStatus() != from) {
            throw new ClinicStatusConflictException(to);
        }
        clinic.setStatus(to);
        clinics.add(PlatformAuditRecord.ofClinic(clinicId, AuditAction.UPDATE, AuditDetails.of(subtype)));
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

    /** A correction of the clinic's fiscal data, made on {@code version}; already validated by the web layer. */
    public record FiscalDataCorrection(String legalName, String taxId, long version) {}

    /** A clinic as the platform API returns it; {@code version} is what a correction sends back. */
    public record ClinicDetails(
            UUID id,
            String name,
            String legalName,
            String taxId,
            String timezone,
            String locale,
            ClinicStatus status,
            Instant createdAt,
            Instant updatedAt,
            long version) {

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
                    clinic.getUpdatedAt(),
                    clinic.getVersion());
        }
    }
}
