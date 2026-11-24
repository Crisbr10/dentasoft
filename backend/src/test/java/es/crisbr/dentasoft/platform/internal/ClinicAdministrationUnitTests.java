package es.crisbr.dentasoft.platform.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** Paths that need a race or a misconfiguration to happen, so they are forced here without a database. */
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

    /** If no MVC resolver handles the denial, the response is still a 403, never an empty 200. */
    @Test
    void aDenialNoResolverHandlesIsStillForbidden() throws Exception {
        HandlerExceptionResolver noResolver = mock(HandlerExceptionResolver.class);
        when(noResolver.resolveException(any(), any(), any(), any())).thenReturn(null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        PlatformSecurityConfiguration.deny(
                noResolver, new MockHttpServletRequest(), response, new AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(403);
    }
}
