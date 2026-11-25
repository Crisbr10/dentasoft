package es.crisbr.dentasoft.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import es.crisbr.dentasoft.audit.AuditEntityType;
import es.crisbr.dentasoft.audit.AuditedEntityId;
import es.crisbr.dentasoft.audit.AuditedRead;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** An audited read must say what it reads: exactly one {@code UUID} parameter marked {@link AuditedEntityId}. */
class AuditedReadInterceptorTests {

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
