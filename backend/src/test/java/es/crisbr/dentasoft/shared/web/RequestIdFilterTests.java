package es.crisbr.dentasoft.shared.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.DispatcherType;
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
}
