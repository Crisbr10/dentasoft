package es.crisbr.dentasoft.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** A misconfiguration that cannot happen in the running application, so it is forced here. */
class ApiSecurityTests {

    /** If no MVC resolver handles the denial, the response is still a 403, never an empty 200. */
    @Test
    void aDenialNoResolverHandlesIsStillForbidden() throws Exception {
        HandlerExceptionResolver noResolver = mock(HandlerExceptionResolver.class);
        when(noResolver.resolveException(any(), any(), any(), any())).thenReturn(null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        ApiSecurity.deny(noResolver, new MockHttpServletRequest(), response);

        assertThat(response.getStatus()).isEqualTo(403);
    }

    /** The same for a missing or rejected token: still a 401 with its challenge, never an empty 200. */
    @Test
    void anAuthenticationFailureNoResolverHandlesIsStillUnauthorized() throws Exception {
        HandlerExceptionResolver noResolver = mock(HandlerExceptionResolver.class);
        when(noResolver.resolveException(any(), any(), any(), any())).thenReturn(null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        ApiSecurity.unauthenticated(
                noResolver, new MockHttpServletRequest(), response, new BadCredentialsException("expired"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
    }
}
