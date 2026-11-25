package es.crisbr.dentasoft.shared.internal;

import java.sql.SQLException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A copy of an exception chain that keeps the classes and the stack frames but drops every message, so it can be
 * logged: messages often carry the values that caused the error, which may be personal or clinical data. A
 * {@link SQLException} keeps its SQLSTATE, a five-character code of the kind of error that carries no values.
 *
 * <p>Used wherever an exception is logged that nobody handled: in requests ({@code GlobalExceptionHandler}),
 * {@code @Async} methods and listeners ({@code SharedConfiguration}) and scheduled tasks ({@code
 * SchedulingConfiguration}).
 */
public final class RedactedException extends RuntimeException {

    /** What SQLSTATE codes are made of; anything else is left out, as it would not be one. */
    private static final Pattern SQL_STATE = Pattern.compile("[0-9A-Z]{5}");

    private RedactedException(String description, @Nullable Throwable cause) {
        super(description, cause, false, true);
    }

    public static RedactedException of(Throwable throwable) {
        return copy(throwable, new IdentityHashMap<>());
    }

    private static RedactedException copy(Throwable throwable, Map<Throwable, Boolean> seen) {
        seen.put(throwable, Boolean.TRUE);
        Throwable cause = throwable.getCause();
        RedactedException redactedCause = cause != null && !seen.containsKey(cause) ? copy(cause, seen) : null;
        RedactedException redacted = new RedactedException(describe(throwable), redactedCause);
        redacted.setStackTrace(throwable.getStackTrace());
        return redacted;
    }

    private static String describe(Throwable throwable) {
        String description = throwable.getClass().getName();
        if (throwable instanceof SQLException sql
                && sql.getSQLState() != null
                && SQL_STATE.matcher(sql.getSQLState()).matches()) {
            return description + " (SQLState " + sql.getSQLState() + ")";
        }
        return description;
    }

    @Override
    public String toString() {
        // The original class name is the message; leave out this wrapper's own name.
        return "[redacted] " + getMessage();
    }
}
