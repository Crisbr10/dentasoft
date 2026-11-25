package es.crisbr.dentasoft.shared;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * The ID of the request being served, which the request ID filter puts in the logging MDC under {@value #MDC_KEY}
 * and returns in the {@code X-Request-Id} header.
 */
public final class RequestId {

    public static final String MDC_KEY = "requestId";

    /** Exactly the canonical form: {@link UUID#fromString} alone also accepts shorter groups. */
    private static final Pattern CANONICAL_UUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private RequestId() {}

    /**
     * The ID of the current request when it is a UUID, as the filter generates them; empty outside a request or when
     * the client sent an ID of its own that is not one. What a client chooses is never stored this way.
     */
    public static Optional<UUID> currentUuid() {
        String id = MDC.get(MDC_KEY);
        return id != null && CANONICAL_UUID.matcher(id).matches() ? Optional.of(UUID.fromString(id)) : Optional.empty();
    }
}
