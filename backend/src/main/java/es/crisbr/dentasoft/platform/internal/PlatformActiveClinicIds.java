package es.crisbr.dentasoft.platform.internal;

import es.crisbr.dentasoft.platform.persistence.ClinicStatus;
import es.crisbr.dentasoft.platform.persistence.PlatformClinics;
import es.crisbr.dentasoft.tenancy.ActiveClinicIds;
import es.crisbr.dentasoft.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The active clinics for processes without a user (M01-T08), read in the platform unit as {@code dentasoft_platform},
 * which sees every clinic (ADR 0011). Only their ids leave this module, and suspended clinics are left out.
 *
 * <p>No {@code @PreAuthorize}: it is called by scheduled tasks, which have no user, and it returns nothing but ids.
 */
@Component
class PlatformActiveClinicIds implements ActiveClinicIds {

    private final PlatformClinics clinics;

    PlatformActiveClinicIds(PlatformClinics clinics) {
        this.clinics = clinics;
    }

    @Override
    @Transactional(value = PlatformPersistenceConfiguration.TRANSACTION_MANAGER, readOnly = true)
    public List<UUID> activeClinicIds() {
        if (TenantContext.isBound()) {
            throw new IllegalStateException("The active clinics are listed across clinics, without an active clinic");
        }
        return clinics.idsWithStatus(ClinicStatus.ACTIVE);
    }
}
