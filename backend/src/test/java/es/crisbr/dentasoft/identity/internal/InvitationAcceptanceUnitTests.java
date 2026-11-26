package es.crisbr.dentasoft.identity.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import es.crisbr.dentasoft.TestSecurityContext;
import es.crisbr.dentasoft.identity.ClinicRole;
import es.crisbr.dentasoft.identity.persistence.ClinicMembership;
import es.crisbr.dentasoft.identity.persistence.ClinicMembershipRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * An administrator changes or cancels an invitation while its user is accepting it: the version check at the write
 * refuses the acceptance, which must be a 409 the user can act on, never a 500.
 */
class InvitationAcceptanceUnitTests {

    private static final UUID INVITED = UUID.fromString("5f0c2b1e-0000-4000-8000-0000000007a1");
    private static final UUID MEMBERSHIP = UUID.fromString("5f0c2b1e-0000-4000-8000-0000000007b2");

    @Test
    void anInvitationChangedWhileBeingAcceptedIsAConflict() {
        ClinicMembershipRepository repository = mock(ClinicMembershipRepository.class);
        when(repository.findById(MEMBERSHIP))
                .thenReturn(Optional.of(new ClinicMembership(INVITED, ClinicRole.ODONTOLOGO)));
        doThrow(new ObjectOptimisticLockingFailureException(ClinicMembership.class, MEMBERSHIP))
                .when(repository)
                .flush();
        InvitationAcceptance acceptance = new InvitationAcceptance(repository);

        TestSecurityContext.runAs(
                INVITED,
                List.of(),
                () -> assertThatThrownBy(() -> acceptance.accept(MEMBERSHIP))
                        .isInstanceOf(MembershipVersionConflictException.class));
    }
}
