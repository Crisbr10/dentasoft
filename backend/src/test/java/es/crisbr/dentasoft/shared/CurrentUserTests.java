package es.crisbr.dentasoft.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import es.crisbr.dentasoft.TestSecurityContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

/** Who acts, for the audit columns and the audit log: the token's {@code sub}, the system, or a refusal. */
class CurrentUserTests {

    private static final UUID USER = UUID.fromString("0190a6e8-0000-7000-8000-00000000c0de");

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aTokenUserIsItsSub() {
        signIn(TestSecurityContext.authentication(USER, List.of()));

        assertThat(CurrentUser.id()).contains(USER);
    }

    @Test
    void withoutAuthenticationItIsTheSystem() {
        assertThat(CurrentUser.id()).isEmpty();
    }

    @Test
    void anAnonymousRequestIsTheSystem() {
        signIn(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertThat(CurrentUser.id()).isEmpty();
    }

    @Test
    void anUnauthenticatedAttemptIsTheSystem() {
        signIn(UsernamePasswordAuthenticationToken.unauthenticated("someone", "secret"));

        assertThat(CurrentUser.id()).isEmpty();
    }

    /** Never recorded as the system: a person acted, but not with a Keycloak token. */
    @Test
    void anyOtherAuthenticationIsRefused() {
        signIn(UsernamePasswordAuthenticationToken.authenticated("someone", null, List.of()));

        assertThatThrownBy(CurrentUser::id)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("someone");
    }

    private static void signIn(Authentication authentication) {
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
