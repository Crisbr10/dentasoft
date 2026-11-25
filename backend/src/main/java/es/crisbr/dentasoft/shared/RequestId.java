package es.crisbr.dentasoft.shared;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * The ID of the request being served, which the request ID filter puts in the logging MDC under {@value #MDC_KEY}
 * and returns in the {@code X-Request-Id} header. {@value #ORIGIN_MDC_KEY} says whether the server generated it
 * ({@value #ORIGIN_SERVER}) or kept the one the client sent ({@value #ORIGIN_CLIENT}).
 */
public final class RequestId {

    public static final String MDC_KEY = "requestId";
    public static final String ORIGIN_MDC_KEY = "requestIdOrigin";
    public static final String ORIGIN_SERVER = "server";
    public static final String ORIGIN_CLIENT = "client";

    /** Exactly the canonical form: {@link UUID#fromString} alone also accepts shorter groups. */
    private static final Pattern CANONICAL_UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private RequestId() {}

    /**
     * The ID of the current request, only when the server generated it; empty outside a request and when the client
     * sent an ID of its own, even one shaped like a UUID: a client could reuse the ID of another request, so what it
     * chooses is never stored as evidence (the audit log, ADR 0012).
     */
    public static Optional<UUID> serverGenerated() {
        String id = MDC.get(MDC_KEY);
        return id != null
                        && ORIGIN_SERVER.equals(MDC.get(ORIGIN_MDC_KEY))
                        && CANONICAL_UUID.matcher(id).matches()
                ? Optional.of(UUID.fromString(id))
                : Optional.empty();
    }
}
