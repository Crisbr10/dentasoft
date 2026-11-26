package es.crisbr.dentasoft.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.source.RateLimitReachedException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.JwtException;
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

    /** Tokens with an unknown key ID, once the keys may not be asked again, are not logged: anyone can send them. */
    @Test
    void aRefusalBecauseTheKeysWereAskedTooRecentlyIsNotLogged() {
        assertThat(ApiSecurity.keysNotAskedAgainYet(new AuthenticationServiceException(
                        "keys", new JwtException("decode", new RateLimitReachedException()))))
                .isTrue();
    }

    /** Keycloak not answering is: that is the outage operations must see. */
    @Test
    void aRefusalBecauseKeycloakDidNotAnswerIsLogged() {
        assertThat(ApiSecurity.keysNotAskedAgainYet(new AuthenticationServiceException(
                        "keys", new JwtException("decode", new KeySourceException("Read timed out")))))
                .isFalse();
    }
}
