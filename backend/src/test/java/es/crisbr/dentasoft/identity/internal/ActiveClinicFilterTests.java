package es.crisbr.dentasoft.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import es.crisbr.dentasoft.TestSecurityContext;
import es.crisbr.dentasoft.identity.ClinicRole;
import es.crisbr.dentasoft.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/** {@link ActiveClinicFilter} without Spring: what it leaves behind and which requests it looks at. */
class ActiveClinicFilterTests {

    private static final UUID USER = UUID.fromString("5f0c2b1e-0000-4000-8000-0000000004b1");
    private static final UUID CLINIC = UUID.fromString("0192f0a0-0000-7000-8000-00000000000a");

    private final ActiveClinicAccess access = mock(ActiveClinicAccess.class);
    private final HandlerExceptionResolver resolver = mock(HandlerExceptionResolver.class);
    private ActiveClinicFilter filter;
    private JwtAuthenticationToken token;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<ActiveClinicAccess> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(access);
        when(resolver.resolveException(any(), any(), any(), any())).thenReturn(new ModelAndView());
        filter = new ActiveClinicFilter(provider, resolver);
        token = TestSecurityContext.authentication(USER, List.of(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(token);
        SecurityContextHolder.setContext(context);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** The clinic and its role last as long as the request, and the user is the same token's. */
    @Test
    void theClinicAndItsRoleEndWithTheRequest() throws Exception {
        when(access.roleIn(CLINIC)).thenReturn(Optional.of(ClinicRole.ADMIN_CLINICA));
        AtomicReference<Authentication> inside = new AtomicReference<>();
        AtomicReference<UUID> clinicInside = new AtomicReference<>();

        filter.doFilter(clinicRequest(CLINIC.toString()), new MockHttpServletResponse(), (request, response) -> {
            inside.set(SecurityContextHolder.getContext().getAuthentication());
            clinicInside.set(TenantContext.current());
        });

        assertThat(clinicInside.get()).isEqualTo(CLINIC);
        assertThat(inside.get()).isInstanceOfSatisfying(JwtAuthenticationToken.class, withRole -> {
            assertThat(withRole.getToken()).isSameAs(token.getToken());
            assertThat(withRole.getName()).isEqualTo(USER.toString());
            assertThat(withRole.isAuthenticated()).isTrue();
        });
        assertThat(inside.get().getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_PLATFORM_ADMIN", "ROLE_ADMIN_CLINICA");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(token);
        assertThat(TenantContext.isBound()).isFalse();
    }

    @Test
    void theUserIsRestoredAlsoWhenTheRequestFails() {
        when(access.roleIn(CLINIC)).thenReturn(Optional.of(ClinicRole.RECEPCION));
        FilterChain failing = (request, response) -> {
            throw new IllegalStateException("boom");
        };

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() ->
                        filter.doFilter(clinicRequest(CLINIC.toString()), new MockHttpServletResponse(), failing));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(token);
        assertThat(TenantContext.isBound()).isFalse();
    }

    /** Only the clinic API: a path that merely starts the same way is not part of it. */
    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/me", "/api/v1/clinics", "/api/v1/clinicx/settings", "/api/v1/platform/clinics"})
    void requestsOutsideTheClinicApiPassUntouched(String path) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        AtomicReference<Boolean> bound = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> bound.set(TenantContext.isBound()));

        assertThat(bound.get()).isFalse();
        verifyNoInteractions(access);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/clinic", "/api/v1/clinic/", "/api/v1/clinic/locations", "/api/v1/clinic/a/b"})
    /** A signed-in user without the header gets the 400, on every path of the clinic API. */
    void everyPathOfTheClinicApiNeedsTheHeader(String path) throws Exception {
        AtomicReference<Boolean> reached = new AtomicReference<>(false);

        filter.doFilter(
                new MockHttpServletRequest("GET", path),
                new MockHttpServletResponse(),
                (req, res) -> reached.set(true));

        assertThat(reached.get()).isFalse();
        verify(resolver).resolveException(any(), any(), isNull(), isA(InvalidClinicHeaderException.class));
        verifyNoInteractions(access);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "null",
                "0192f0a0-0000-7000-8000-00000000000",
                "0192f0a0-0000-7000-8000-00000000000aa",
                "{0192f0a0-0000-7000-8000-00000000000a}",
                "0192f0a000007000800000000000000a",
                "0192f0a0-0000-7000-8000-00000000000a,0192f0a0-0000-7000-8000-00000000000b",
                "1-1-1-1-1",
                "0192f0a0-0000-7000-8000-00000000000g"
            })
    void onlyAWholeUuidNamesAClinic(String value) {
        assertThat(ActiveClinicFilter.clinicOf(clinicRequest(value))).isEmpty();
    }

    @Test
    void theHeaderMustAppearOnce() {
        MockHttpServletRequest twice = clinicRequest(CLINIC.toString());
        twice.addHeader("X-Clinic-Id", CLINIC.toString());

        assertThat(ActiveClinicFilter.clinicOf(twice)).isEmpty();
        assertThat(ActiveClinicFilter.clinicOf(new MockHttpServletRequest("GET", "/api/v1/clinic")))
                .isEmpty();
        assertThat(ActiveClinicFilter.clinicOf(clinicRequest(CLINIC.toString().toUpperCase())))
                .contains(CLINIC);
    }

    /** A clinic role the token somehow carried is replaced by the membership's: only the database decides. */
    @Test
    void theRoleComesFromTheMembershipOnly() {
        JwtAuthenticationToken forged = TestSecurityContext.authentication(
                USER,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN_CLINICA"), new SimpleGrantedAuthority("FACTOR_BEARER")));

        assertThat(ActiveClinicFilter.withClinicRole(forged, ClinicRole.RECEPCION)
                        .getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("FACTOR_BEARER", "ROLE_RECEPCION");
    }

    private static MockHttpServletRequest clinicRequest(String clinic) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/clinic/settings");
        request.addHeader("X-Clinic-Id", clinic);
        return request;
    }
}
