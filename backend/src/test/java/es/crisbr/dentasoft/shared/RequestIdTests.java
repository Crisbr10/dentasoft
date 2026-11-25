package es.crisbr.dentasoft.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** Only an ID the server generated leaves {@link RequestId}: what a client sends is never stored as evidence. */
class RequestIdTests {

    @AfterEach
    void clear() {
        MDC.remove(RequestId.MDC_KEY);
        MDC.remove(RequestId.ORIGIN_MDC_KEY);
    }

    @Test
    void givesTheIdTheServerGenerated() {
        UUID id = UUID.randomUUID();
        MDC.put(RequestId.MDC_KEY, id.toString());
        MDC.put(RequestId.ORIGIN_MDC_KEY, RequestId.ORIGIN_SERVER);

        assertThat(RequestId.serverGenerated()).contains(id);
    }

    @Test
    void givesNothingOutsideARequest() {
        assertThat(RequestId.serverGenerated()).isEmpty();
    }

    /** Not even a UUID: the client could repeat the id of another request. */
    @Test
    void givesNothingForAnIdChosenByTheClient() {
        for (String chosen : new String[] {UUID.randomUUID().toString(), "juan.perez", "pedido-42"}) {
            MDC.put(RequestId.MDC_KEY, chosen);
            MDC.put(RequestId.ORIGIN_MDC_KEY, RequestId.ORIGIN_CLIENT);

            assertThat(RequestId.serverGenerated()).as(chosen).isEmpty();
        }
    }

    @Test
    void givesNothingWithoutAnOrigin() {
        MDC.put(RequestId.MDC_KEY, UUID.randomUUID().toString());

        assertThat(RequestId.serverGenerated()).isEmpty();
    }
}
