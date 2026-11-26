package es.crisbr.dentasoft.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;

import es.crisbr.dentasoft.TestSecurityContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * When the registration of a signed-in user fails, their request fails too, and the next one tries again: a user is
 * never remembered as registered without an account.
 */
class UserRegistrationFilterTests {

    private static final UUID USER = UUID.fromString("5f0c2b1e-0000-4000-8000-000000000601");

    @Test
    void aFailedRegistrationFailsTheRequestAndTheNextRequestTriesAgain() {
        FailingOnceAccounts accounts = new FailingOnceAccounts();
        UserRegistrationFilter filter = new UserRegistrationFilter(provider(accounts), new RegisteredUsers(10));

        MockFilterChain failed = new MockFilterChain();
        TestSecurityContext.runAs(
                USER,
                List.of(),
                () -> assertThatExceptionOfType(DataAccessResourceFailureException.class)
                        .isThrownBy(() ->
                                filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), failed)));
        assertThat(failed.getRequest())
                .as("the request went on without an account")
                .isNull();

        MockFilterChain retried = new MockFilterChain();
        MockFilterChain remembered = new MockFilterChain();
        TestSecurityContext.runAs(USER, List.of(), () -> {
            doFilter(filter, retried);
            doFilter(filter, remembered);
        });

        assertThat(accounts.attempts).as("tried again, then remembered").isEqualTo(2);
        assertThat(retried.getRequest()).isNotNull();
        assertThat(remembered.getRequest()).isNotNull();
    }

    @Test
    void withoutUserAccountsASignedInRequestFailsInsteadOfSkippingTheRegistration() {
        UserRegistrationFilter filter = new UserRegistrationFilter(
                new StaticListableBeanFactory().getBeanProvider(UserAccounts.class), new RegisteredUsers(10));

        MockFilterChain signedIn = new MockFilterChain();
        TestSecurityContext.runAs(
                USER,
                List.of(),
                () -> assertThatExceptionOfType(NoSuchBeanDefinitionException.class)
                        .isThrownBy(() -> filter.doFilter(
                                new MockHttpServletRequest(), new MockHttpServletResponse(), signedIn)));
        assertThat(signedIn.getRequest()).isNull();

        MockFilterChain anonymous = new MockFilterChain();
        doFilter(filter, anonymous);
        assertThat(anonymous.getRequest())
                .as("anonymous requests never need it")
                .isNotNull();
    }

    private static ObjectProvider<UserAccounts> provider(UserAccounts accounts) {
        return new StaticListableBeanFactory(Map.of("userAccounts", accounts)).getBeanProvider(UserAccounts.class);
    }

    private static void doFilter(UserRegistrationFilter filter, MockFilterChain chain) {
        try {
            filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** Fails the first insert, as an unreachable database would, and succeeds after. */
    private static final class FailingOnceAccounts extends UserAccounts {

        private int attempts;

        FailingOnceAccounts() {
            super(mock(JdbcClient.class));
        }

        @Override
        public boolean registerCurrentUser() {
            attempts++;
            if (attempts == 1) {
                throw new DataAccessResourceFailureException("database unreachable (test)");
            }
            return true;
        }
    }
}
