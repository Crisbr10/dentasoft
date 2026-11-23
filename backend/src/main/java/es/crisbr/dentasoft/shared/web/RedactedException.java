package es.crisbr.dentasoft.shared.web;

import java.util.IdentityHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A copy of an exception chain that keeps the classes and the stack frames but drops every message, so it can be
 * logged: messages often carry the values that caused the error, which may be personal or clinical data.
 */
final class RedactedException extends RuntimeException {

    private RedactedException(String originalClass, @Nullable Throwable cause) {
        super(originalClass, cause, false, true);
    }

    static RedactedException of(Throwable throwable) {
        return copy(throwable, new IdentityHashMap<>());
    }

    private static RedactedException copy(Throwable throwable, Map<Throwable, Boolean> seen) {
        seen.put(throwable, Boolean.TRUE);
        Throwable cause = throwable.getCause();
        RedactedException redactedCause = cause != null && !seen.containsKey(cause) ? copy(cause, seen) : null;
        RedactedException redacted = new RedactedException(throwable.getClass().getName(), redactedCause);
        redacted.setStackTrace(throwable.getStackTrace());
        return redacted;
    }

    @Override
    public String toString() {
        // The original class name is the message; leave out this wrapper's own name.
        return "[redacted] " + getMessage();
    }
}
