package es.crisbr.dentasoft.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.shared.RequestId;
import jakarta.servlet.DispatcherType;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestIdFilterTests {

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void theErrorDispatchKeepsTheGeneratedIdOfTheOriginalRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/test/ping");
        request.addHeader(RequestIdFilter.HEADER, "not valid\r\n");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> loggedDuringRequest = new AtomicReference<>();
        AtomicReference<String> loggedDuringError = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> loggedDuringRequest.set(MDC.get(RequestIdFilter.MDC_KEY)));
        String returned = response.getHeader(RequestIdFilter.HEADER);
        request.setDispatcherType(DispatcherType.ERROR);
        filter.doFilter(request, response, (req, res) -> loggedDuringError.set(MDC.get(RequestIdFilter.MDC_KEY)));

        assertThat(returned).isNotBlank().isNotEqualTo("not valid\r\n");
        assertThat(loggedDuringRequest.get()).isEqualTo(returned);
        assertThat(loggedDuringError.get()).isEqualTo(returned);
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(returned);
    }

    @Test
    void separateRequestsGetSeparateIds() throws Exception {
        MockHttpServletResponse first = new MockHttpServletResponse();
        MockHttpServletResponse second = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), first, (req, res) -> {});
        filter.doFilter(new MockHttpServletRequest(), second, (req, res) -> {});

        assertThat(first.getHeader(RequestIdFilter.HEADER)).isNotEqualTo(second.getHeader(RequestIdFilter.HEADER));
    }

    /** The audit log stores the id only when the server generated it (ADR 0012), on the error dispatch too. */
    @Test
    void marksWhetherTheServerGeneratedTheId() throws Exception {
        MockHttpServletRequest generated = new MockHttpServletRequest();
        AtomicReference<Optional<UUID>> duringRequest = new AtomicReference<>();
        AtomicReference<Optional<UUID>> duringError = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(generated, response, (req, res) -> duringRequest.set(RequestId.serverGenerated()));
        generated.setDispatcherType(DispatcherType.ERROR);
        filter.doFilter(generated, response, (req, res) -> duringError.set(RequestId.serverGenerated()));

        UUID returned = UUID.fromString(response.getHeader(RequestIdFilter.HEADER));
        assertThat(duringRequest.get()).contains(returned);
        assertThat(duringError.get()).contains(returned);
        assertThat(MDC.get(RequestId.ORIGIN_MDC_KEY)).isNull();
    }

    /** A client-chosen id is kept for the logs and the response, but it is not the server's, even as a UUID. */
    @Test
    void anIdChosenByTheClientIsNotServerGenerated() throws Exception {
        String chosen = UUID.randomUUID().toString();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(RequestIdFilter.HEADER, chosen);
        AtomicReference<String> logged = new AtomicReference<>();
        AtomicReference<String> origin = new AtomicReference<>();
        AtomicReference<Optional<UUID>> serverGenerated = new AtomicReference<>();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            logged.set(MDC.get(RequestIdFilter.MDC_KEY));
            origin.set(MDC.get(RequestId.ORIGIN_MDC_KEY));
            serverGenerated.set(RequestId.serverGenerated());
        });

        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(chosen);
        assertThat(logged.get()).isEqualTo(chosen);
        assertThat(origin.get()).isEqualTo(RequestId.ORIGIN_CLIENT);
        assertThat(serverGenerated.get()).isEmpty();
    }
}
