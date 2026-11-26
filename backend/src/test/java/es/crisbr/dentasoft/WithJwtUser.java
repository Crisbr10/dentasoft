package es.crisbr.dentasoft;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.test.context.support.WithSecurityContext;
import org.springframework.security.test.context.support.WithSecurityContextFactory;

/**
 * Runs the test as a user signed in with a Keycloak access token, as the Resource Server leaves it in the security
 * context: a {@link JwtAuthenticationToken} whose name is {@link #sub()}. Use it instead of {@code @WithMockUser} for
 * anything that records who acted (audit columns, audit log), which only accepts token users.
 *
 * <p>{@link #roles()} become {@code ROLE_} authorities directly, also the clinic roles that M02-T04 will take from the
 * membership: the token itself is not decoded, so this only tests what happens after authentication. Tests of the token
 * checks send a real one ({@link TestTokens}).
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
@WithSecurityContext(factory = WithJwtUser.Factory.class)
public @interface WithJwtUser {

    String DEFAULT_SUB = "0190a6e8-0000-7000-8000-00000000c0de";

    String sub() default DEFAULT_SUB;

    String[] roles() default {};

    /** Authorities as they are, without the {@code ROLE_} prefix. */
    String[] authorities() default {};

    final class Factory implements WithSecurityContextFactory<WithJwtUser> {

        @Override
        public SecurityContext createSecurityContext(WithJwtUser user) {
            List<GrantedAuthority> authorities = new ArrayList<>();
            for (String role : user.roles()) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
            }
            for (String authority : user.authorities()) {
                authorities.add(new SimpleGrantedAuthority(authority));
            }
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(TestSecurityContext.authentication(UUID.fromString(user.sub()), authorities));
            return context;
        }
    }
}
