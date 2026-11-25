package es.crisbr.dentasoft.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link AuditDetails} cannot hold personal or clinical data because of its types, not because of discipline: every
 * component, nested records included, is a UUID, an enum, a number or a boolean. Adding a {@code String} (a name, a
 * note), a date (a birth date) or a collection makes this test fail.
 */
class AuditDetailsTests {

    private static final Set<Class<?>> ALLOWED = Set.of(
            UUID.class,
            BigDecimal.class,
            Integer.class,
            int.class,
            Long.class,
            long.class,
            Short.class,
            short.class,
            Boolean.class,
            boolean.class);

    @Test
    void holdsOnlyClosedTypes() {
        assertThat(openComponents(AuditDetails.class)).isEmpty();
    }

    /** The check itself, so a broken check cannot make the test above pass. */
    @Test
    void theCheckFindsTextDatesAndCollectionsAtAnyDepth() {
        assertThat(openComponents(Open.class))
                .containsExactly(
                        "Open.name: String", "Open.birthDate: LocalDate", "Open.tags: List", "Nested.note: String");
        assertThat(openComponents(Closed.class)).isEmpty();
    }

    @Test
    void writesOnlyTheComponentsThatAreSet() {
        assertThat(AuditDetails.of(AuditSubtype.CLINIC_SUSPENDED).toJson())
                .isEqualTo("{\"subtype\":\"CLINIC_SUSPENDED\"}");
        assertThat(new AuditDetails(null).toJson()).isNull();
    }

    @Test
    void writesEveryClosedType() {
        UUID id = UUID.fromString("0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b");
        assertThat(AuditDetails.json(new Closed(
                        id, AuditSubtype.CLINIC_REACTIVATED, 3, 4L, new BigDecimal("12.50"), true, new Inner((short)
                                5))))
                .isEqualTo(
                        "{\"id\":\"0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b\",\"code\":\"CLINIC_REACTIVATED\",\"count\":3,"
                                + "\"total\":4,\"amount\":12.50,\"flag\":true,\"inner\":{\"value\":5}}");
    }

    @Test
    void refusesToWriteAnythingElse() {
        assertThatIllegalStateException()
                .isThrownBy(() -> AuditDetails.json(new Open("Juan Pérez", null, null, null)))
                .withMessageContaining("java.lang.String");
    }

    private static List<String> openComponents(Class<? extends Record> type) {
        List<String> open = new ArrayList<>();
        collectOpen(type, open);
        return open;
    }

    private static void collectOpen(Class<? extends Record> type, List<String> open) {
        for (RecordComponent component : type.getRecordComponents()) {
            Class<?> componentType = component.getType();
            if (componentType.isRecord()) {
                collectOpen(componentType.asSubclass(Record.class), open);
            } else if (!componentType.isEnum() && !ALLOWED.contains(componentType)) {
                open.add(type.getSimpleName() + "." + component.getName() + ": " + componentType.getSimpleName());
            }
        }
    }

    record Open(String name, LocalDate birthDate, List<UUID> tags, Nested nested) {}

    record Nested(String note) {}

    record Closed(UUID id, AuditSubtype code, int count, Long total, BigDecimal amount, boolean flag, Inner inner) {}

    record Inner(short value) {}
}
