package es.crisbr.dentasoft.identity.internal;

import es.crisbr.dentasoft.identity.ClinicRole;
import es.crisbr.dentasoft.shared.ApiSecurity;
import es.crisbr.dentasoft.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Sets the active clinic of every request to the clinic API ({@link ApiSecurity#CLINIC_API}) from its {@link
 * ApiSecurity#CLINIC_HEADER} header, after checking in the database that the user may act as it ({@link
 * ActiveClinicAccess}). The rest of the request runs inside {@link TenantContext#callAs} with that clinic, and with the
 * role of the membership added to the token's authorities as {@code ROLE_<role>}; both end with the request.
 *
 * <ul>
 *   <li>No header, more than one, or a value that is not a UUID: 400.
 *   <li>A clinic that does not exist, someone else's, a suspended one, a membership that is no longer active or a
 *       disabled account: the same 403 as any other refusal, so nobody can find out which clinics exist. The refusal
 *       is logged with the user and the clinic IDs only, to spot someone trying clinics. The per-user rate limit has
 *       already counted the request, which runs before this filter.
 * </ul>
 *
 * <p>Runs after Spring Security, so the clinic role is not there yet when the security chains authorize the request:
 * rules by URL ({@code requestMatchers(…).hasRole("ODONTOLOGO")}) never see it. Clinic roles are checked with {@code
 * @PreAuthorize} on the services. Also after the registration of new users ({@link UserRegistrationFilter}), so a
 * user's very first request may already be one to the clinic API.
 *
 * <p>The check runs in its own read-only transaction, closed before the clinic is bound: the request's transactions
 * start inside it ({@link TenantContext} refuses to bind a clinic inside an open transaction). The clinic is not passed
 * to other threads, so endpoints of the clinic API cannot answer asynchronously ({@code NoAsyncEndpointsTests}).
 */
final class ActiveClinicFilter extends OncePerRequestFilter {

    /** After the registration of new users ({@link UserRegistrationFilter#ORDER}). */
    static final int ORDER = UserRegistrationFilter.ORDER + 10;

    private static final Logger log = LoggerFactory.getLogger(ActiveClinicFilter.class);

    /** The same pattern as the clinic API's security chain, matched the same way. */
    private static final RequestMatcher CLINIC_API =
            PathPatternRequestMatcher.withDefaults().matcher(ApiSecurity.CLINIC_API);

    /** A UUID written out in full, as {@link UUID#toString} does, in either case: no shortened forms. */
    private static final Pattern UUID_TEXT = Pattern.compile("\\p{XDigit}{8}(-\\p{XDigit}{4}){3}-\\p{XDigit}{12}");

    /** The token never carries clinic roles; any authority by these names would be dropped. */
    private static final Set<String> CLINIC_AUTHORITIES = Arrays.stream(ClinicRole.values())
            .map(ActiveClinicFilter::authority)
            .collect(Collectors.toUnmodifiableSet());

    private final ObjectProvider<ActiveClinicAccess> access;
    private final HandlerExceptionResolver exceptionResolver;
    private final SecurityContextHolderStrategy securityContexts = SecurityContextHolder.getContextHolderStrategy();

    ActiveClinicFilter(ObjectProvider<ActiveClinicAccess> access, HandlerExceptionResolver exceptionResolver) {
        this.access = access;
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !CLINIC_API.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        SecurityContext previous = securityContexts.getContext();
        if (!(previous.getAuthentication() instanceof JwtAuthenticationToken token) || !token.isAuthenticated()) {
            // The chain of the clinic API lets no one else through; if it ever did, nothing opens.
            refuse(request, response, new AccessDeniedException("No signed-in user"), HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        Optional<UUID> clinic = clinicOf(request);
        if (clinic.isEmpty()) {
            refuse(request, response, new InvalidClinicHeaderException(), HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        Optional<ClinicRole> role = access.getObject().roleIn(clinic.get());
        if (role.isEmpty()) {
            log.warn("Active clinic refused: user {} asked for clinic {}", token.getName(), clinic.get());
            refuse(request, response, new AccessDeniedException("Clinic refused"), HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        SecurityContext context = securityContexts.createEmptyContext();
        context.setAuthentication(withClinicRole(token, role.get()));
        securityContexts.setContext(context);
        try {
            inClinic(clinic.get(), request, response, chain);
        } finally {
            securityContexts.setContext(previous);
        }
    }

    /** The clinic named by the request: exactly one header with a whole UUID. */
    static Optional<UUID> clinicOf(HttpServletRequest request) {
        Enumeration<String> headers = request.getHeaders(ApiSecurity.CLINIC_HEADER);
        List<String> values = headers == null ? List.of() : Collections.list(headers);
        if (values.size() != 1 || !UUID_TEXT.matcher(values.getFirst()).matches()) {
            return Optional.empty();
        }
        return Optional.of(UUID.fromString(values.getFirst()));
    }

    /** The same token and user, with the clinic role added to the token's own authorities. */
    static JwtAuthenticationToken withClinicRole(JwtAuthenticationToken token, ClinicRole role) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        for (GrantedAuthority authority : token.getAuthorities()) {
            if (!CLINIC_AUTHORITIES.contains(authority.getAuthority())) {
                authorities.add(authority);
            }
        }
        authorities.add(new SimpleGrantedAuthority(authority(role)));
        JwtAuthenticationToken withRole = new JwtAuthenticationToken(token.getToken(), authorities, token.getName());
        withRole.setDetails(token.getDetails());
        return withRole;
    }

    private static String authority(ClinicRole role) {
        return "ROLE_" + role.name();
    }

    private static void inClinic(
            UUID clinic, HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            TenantContext.<Void, Exception>callAs(clinic, () -> {
                chain.doFilter(request, response);
                return null;
            });
        } catch (IOException | ServletException | RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            // The chain throws nothing else.
            throw new ServletException(failure);
        }
    }

    /** A {@code ProblemDetail} through the MVC resolvers; a bare status if none handles it, never an empty 200. */
    private void refuse(HttpServletRequest request, HttpServletResponse response, Exception failure, int status)
            throws IOException {
        if (exceptionResolver.resolveException(request, response, null, failure) == null) {
            response.sendError(status);
        }
    }
}
