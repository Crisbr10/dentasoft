package es.crisbr.dentasoft.audit;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.util.StringJoiner;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What else an entry says, stored as JSON in {@code audit_log.details}.
 *
 * <p>No personal or clinical data by design: a component may only be a {@link UUID}, an enum, a number, a boolean or
 * another record that follows the same rule. No text, no dates, no collections: {@code AuditDetailsTests} fails as
 * soon as a component of another type is added, here or in a nested record.
 *
 * @param subtype what the action meant, when the action alone does not say it
 */
public record AuditDetails(@Nullable AuditSubtype subtype) {

    public static AuditDetails of(AuditSubtype subtype) {
        return new AuditDetails(subtype);
    }

    /**
     * This record as a JSON object, without the components that are {@code null}; {@code null} if all of them are.
     * Names are Java identifiers and values are UUIDs, enum names, numbers or booleans, so nothing needs escaping.
     */
    public @Nullable String toJson() {
        return json(this);
    }

    /** Any record that follows the rule of this one; package-private for {@code AuditDetailsTests}. */
    static @Nullable String json(Record value) {
        StringJoiner object = new StringJoiner(",", "{", "}");
        boolean empty = true;
        for (RecordComponent component : value.getClass().getRecordComponents()) {
            Object member = accessor(component, value);
            if (member == null) {
                continue;
            }
            String json = member instanceof Record nested ? json(nested) : scalar(member);
            if (json != null) {
                object.add('"' + component.getName() + "\":" + json);
                empty = false;
            }
        }
        return empty ? null : object.toString();
    }

    private static String scalar(Object value) {
        return switch (value) {
            case UUID uuid -> '"' + uuid.toString() + '"';
            case Enum<?> code -> '"' + code.name() + '"';
            case BigDecimal decimal -> decimal.toPlainString();
            case Integer _, Long _, Short _, Boolean _ -> value.toString();
            default ->
                throw new IllegalStateException(
                        "Audit details cannot hold a " + value.getClass().getName());
        };
    }

    private static @Nullable Object accessor(RecordComponent component, Record value) {
        try {
            return component.getAccessor().invoke(value);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Cannot read audit detail " + component.getName(), e);
        }
    }
}
