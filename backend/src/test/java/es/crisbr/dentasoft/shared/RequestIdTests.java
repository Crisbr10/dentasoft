package es.crisbr.dentasoft.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** Only an ID in the canonical UUID form leaves {@link RequestId}: what else a client sends is never stored. */
class RequestIdTests {

    @AfterEach
    void clear() {
        MDC.remove(RequestId.MDC_KEY);
    }

    @Test
    void givesTheIdOfTheRequestWhenItIsAUuid() {
        UUID id = UUID.randomUUID();
        MDC.put(RequestId.MDC_KEY, id.toString());

        assertThat(RequestId.currentUuid()).contains(id);
    }

    @Test
    void givesNothingOutsideARequest() {
        assertThat(RequestId.currentUuid()).isEmpty();
    }

    @Test
    void givesNothingForAnIdChosenByTheClient() {
        for (String chosen :
                new String[] {"juan.perez", "1-1-1-1-1", "pedido-42", "0190A1B2C3D47E5F8A9B0C1D2E3F4A5B"}) {
            MDC.put(RequestId.MDC_KEY, chosen);

            assertThat(RequestId.currentUuid()).as(chosen).isEmpty();
        }
    }
}
