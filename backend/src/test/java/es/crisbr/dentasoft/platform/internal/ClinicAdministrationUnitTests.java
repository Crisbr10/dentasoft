package es.crisbr.dentasoft.platform.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import es.crisbr.dentasoft.platform.persistence.ClinicStatus;
import es.crisbr.dentasoft.platform.persistence.PlatformClinic;
import es.crisbr.dentasoft.platform.persistence.PlatformClinics;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/** Paths that need a race to happen, so they are forced here without a database. */
class ClinicAdministrationUnitTests {

    private final PlatformClinics clinics = mock(PlatformClinics.class);

    private final ClinicAdministration administration =
            new ClinicAdministration(clinics, mock(ApplicationEventPublisher.class));

    /** Two requests suspend the same clinic at once: the one whose write loses gets 409, not a 500. */
    @Test
    void aConcurrentStatusChangeIsAConflict() {
        UUID id = UUID.randomUUID();
        PlatformClinic clinic = new PlatformClinic("Clínica", "Clínica S.L.", "B1", "Europe/Madrid", "es-ES");
        when(clinics.find(id)).thenReturn(Optional.of(clinic));
        doThrow(new ObjectOptimisticLockingFailureException(PlatformClinic.class, id))
                .when(clinics)
                .flush();

        assertThatThrownBy(() -> administration.suspend(id)).isInstanceOf(ClinicStatusConflictException.class);
        assertThat(clinic.getStatus()).isEqualTo(ClinicStatus.SUSPENDED);
    }
}
