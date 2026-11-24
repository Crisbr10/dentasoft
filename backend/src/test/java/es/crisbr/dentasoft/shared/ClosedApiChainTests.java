package es.crisbr.dentasoft.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** A misconfiguration that cannot happen in the running application, so it is forced here. */
class ClosedApiChainTests {

    /** If no MVC resolver handles the denial, the response is still a 403, never an empty 200. */
    @Test
    void aDenialNoResolverHandlesIsStillForbidden() throws Exception {
        HandlerExceptionResolver noResolver = mock(HandlerExceptionResolver.class);
        when(noResolver.resolveException(any(), any(), any(), any())).thenReturn(null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        ClosedApiChain.deny(noResolver, new MockHttpServletRequest(), response, new AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(403);
    }
}
