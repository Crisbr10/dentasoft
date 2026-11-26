package es.crisbr.dentasoft.identity.internal;

import es.crisbr.dentasoft.shared.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Registers the signed-in user on their first access ({@link UserAccounts#registerCurrentUser}), whatever the
 * endpoint. Runs after Spring Security, so only users with a valid token get here, and after the per-user rate limit,
 * so the insert is limited too. Anonymous requests pass untouched.
 *
 * <p>{@link UserAccounts} is looked up on the first signed-in request, not when the server starts, so the filter does
 * not need the database to start. If it cannot be had, that request fails instead of skipping the registration.
 */
final class UserRegistrationFilter extends OncePerRequestFilter {

    /** After the per-user rate limit ({@code shared.web.RateLimitFilter}, -90) and before everything else. */
    static final int ORDER = -80;

    private final ObjectProvider<UserAccounts> accounts;
    private final RegisteredUsers registered;

    UserRegistrationFilter(ObjectProvider<UserAccounts> accounts, RegisteredUsers registered) {
        this.accounts = accounts;
        this.registered = registered;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<UUID> user = CurrentUser.id();
        if (user.isPresent() && !registered.contains(user.get())) {
            accounts.getObject().registerCurrentUser();
            registered.add(user.get());
        }
        chain.doFilter(request, response);
    }
}
