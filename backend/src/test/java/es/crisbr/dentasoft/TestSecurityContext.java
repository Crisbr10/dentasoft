package es.crisbr.dentasoft;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The authentication the Resource Server leaves in the security context for a token user, built without a token, for
 * tests of what happens after authentication. {@link WithJwtUser} uses it for a whole test; {@link #runAs} for one
 * call made by another user.
 */
public final class TestSecurityContext {

    private TestSecurityContext() {}

    public static JwtAuthenticationToken authentication(UUID sub, Collection<? extends GrantedAuthority> authorities) {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .issuer(TestTokens.ISSUER)
                .audience(List.of(TestTokens.AUDIENCE))
                .subject(sub.toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();
        return new JwtAuthenticationToken(jwt, authorities, sub.toString());
    }

    /** Runs {@code operation} as the token user {@code sub} with {@code roles}, then restores the previous user. */
    public static void runAs(UUID sub, List<String> roles, Runnable operation) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication(
                sub,
                roles.stream()
                        .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                        .toList()));
        SecurityContextHolder.setContext(context);
        try {
            operation.run();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}
