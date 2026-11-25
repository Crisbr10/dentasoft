package es.crisbr.dentasoft.shared;

import es.crisbr.dentasoft.shared.internal.RedactedException;

/**
 * How every module logs an exception: never the exception itself, whose messages may carry the personal or clinical
 * data that caused it (#123), but {@link #redacted(Throwable)}, which keeps what is needed to find the fault.
 *
 * <pre>{@code
 * log.error("Could not record a denied read of {} {}", entityType, entityId, Logging.redacted(failure));
 * }</pre>
 *
 * <p>{@code LoggedExceptionsTests} fails if code in {@code src/main} passes an exception to a logger directly.
 */
public final class Logging {

    private Logging() {}

    /**
     * A copy of {@code throwable} and its causes with their classes and stack frames but no messages; a {@code
     * SQLException} keeps its SQLSTATE. It is unchecked, so it can also be rethrown where the original would be
     * logged by someone else (a servlet container) with its messages.
     */
    public static RuntimeException redacted(Throwable throwable) {
        return RedactedException.of(throwable);
    }
}
