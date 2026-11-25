package es.crisbr.dentasoft.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import es.crisbr.dentasoft.audit.AuditEntityType;
import es.crisbr.dentasoft.audit.AuditService;
import es.crisbr.dentasoft.audit.AuditedEntityId;
import es.crisbr.dentasoft.audit.AuditedRead;
import java.lang.reflect.Method;
import java.util.UUID;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.access.AccessDeniedException;

/**
 * An audited read must say what it reads: exactly one {@code UUID} parameter marked {@link AuditedEntityId}. A denied
 * read whose entry cannot be written is logged by id, without the messages of the failure.
 */
@ExtendWith(OutputCaptureExtension.class)
class AuditedReadInterceptorTests {

    /** Invented personal data, standing in for a value the failure to write the entry might quote. */
    private static final String PATIENT_NAME = "Ana Inventada Prueba";

    @Test
    void takesTheMarkedParameter() throws NoSuchMethodException {
        UUID id = UUID.randomUUID();

        assertThat(AuditedReadInterceptor.entityId(method("marked", String.class, UUID.class), new Object[] {"x", id}))
                .isEqualTo(id);
    }

    @Test
    void refusesMethodsThatDoNotSayWhatTheyRead() throws NoSuchMethodException {
        assertThatIllegalStateException()
                .isThrownBy(() -> AuditedReadInterceptor.entityId(
                        method("unmarked", UUID.class), new Object[] {UUID.randomUUID()}))
                .withMessageContaining("@AuditedEntityId");
        assertThatIllegalStateException()
                .isThrownBy(() -> AuditedReadInterceptor.entityId(
                        method("twice", UUID.class, UUID.class), new Object[] {UUID.randomUUID(), UUID.randomUUID()}));
        assertThatIllegalStateException()
                .isThrownBy(
                        () -> AuditedReadInterceptor.entityId(method("notAUuid", String.class), new Object[] {"x"}));
    }

    @Test
    void refusesANullId() throws NoSuchMethodException {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AuditedReadInterceptor.entityId(
                        method("marked", String.class, UUID.class), new Object[] {"x", null}));
    }

    @Test
    @SuppressWarnings("unchecked")
    void logsAFailureToRecordADeniedReadWithoutItsMessages(CapturedOutput output) throws Throwable {
        UUID id = UUID.fromString("0190a6e8-0000-7000-8000-000000000002");
        AuditService audit = mock(AuditService.class);
        doThrow(new IllegalStateException(
                        "Could not write the entry of " + PATIENT_NAME,
                        new IllegalArgumentException("Rejected value " + PATIENT_NAME)))
                .when(audit)
                .recordDeniedRead(any(), any());
        ObjectProvider<AuditService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(audit);
        AccessDeniedException denied = new AccessDeniedException("Access Denied");
        MethodInvocation invocation = mock(MethodInvocation.class);
        when(invocation.getMethod()).thenReturn(method("marked", String.class, UUID.class));
        when(invocation.getArguments()).thenReturn(new Object[] {"x", id});
        when(invocation.proceed()).thenThrow(denied);

        assertThatThrownBy(() -> new AuditedReadInterceptor(provider).invoke(invocation))
                .isSameAs(denied);

        assertThat(output.getAll())
                .contains("Could not record a denied read of CLINIC " + id)
                .contains("java.lang.IllegalStateException")
                .contains("java.lang.IllegalArgumentException")
                .doesNotContain(PATIENT_NAME);
    }

    private static Method method(String name, Class<?>... parameters) throws NoSuchMethodException {
        return Samples.class.getDeclaredMethod(name, parameters);
    }

    @SuppressWarnings("unused")
    static class Samples {

        @AuditedRead(AuditEntityType.CLINIC)
        void marked(String other, @AuditedEntityId UUID id) {}

        @AuditedRead(AuditEntityType.CLINIC)
        void unmarked(UUID id) {}

        @AuditedRead(AuditEntityType.CLINIC)
        void twice(@AuditedEntityId UUID first, @AuditedEntityId UUID second) {}

        @AuditedRead(AuditEntityType.CLINIC)
        void notAUuid(@AuditedEntityId String id) {}
    }
}
