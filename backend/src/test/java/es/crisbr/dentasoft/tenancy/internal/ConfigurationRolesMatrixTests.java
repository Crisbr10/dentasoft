package es.crisbr.dentasoft.tenancy.internal;

import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.identity.ClinicRole;
import es.crisbr.dentasoft.identity.ConfigurationSection;
import es.crisbr.dentasoft.identity.SectionAccess;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.expression.EvaluationContext;
import org.springframework.security.access.expression.ExpressionUtils;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.util.SimpleMethodInvocation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every public method of the «Clínica» section's services (M01-T06, M01-T10) against the {@code CLINIC} row of {@link
 * ConfigurationSection}: a read, a method with a read-only transaction, is open to exactly the roles that see the
 * section; anything else is a write, open to exactly those that edit it. Each method's {@code @PreAuthorize}, its own or
 * its class's, is evaluated for every role as Spring Security does, without a role hierarchy, as the application has
 * none.
 *
 * <p>A method added tomorrow is checked too, with no change here: without an annotation of its own it takes its class's
 * {@code ADMIN}, and without any it fails.
 */
class ConfigurationRolesMatrixTests {

    private static final List<Class<?>> SERVICES =
            List.of(ClinicConfiguration.class, ClinicOpeningHours.class, Locations.class);

    private final DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();

    /** At least those of M01-T06: the checks below are not run on an empty list. */
    @Test
    void findsTheMethodsOfM01T06() {
        assertThat(methods().map(ConfigurationRolesMatrixTests::name))
                .contains(
                        "ClinicConfiguration.clinic",
                        "ClinicConfiguration.updateClinic",
                        "ClinicConfiguration.settings",
                        "ClinicConfiguration.updateSettings",
                        "ClinicOpeningHours.week",
                        "ClinicOpeningHours.replaceWeek",
                        "Locations.list",
                        "Locations.get",
                        "Locations.create",
                        "Locations.update",
                        "Locations.deactivate",
                        "Locations.activate");
    }

    @ParameterizedTest
    @MethodSource("methods")
    void everyMethodIsAuthorized(Method method) {
        assertThat(preAuthorize(method)).as(name(method)).isNotNull();
    }

    @ParameterizedTest
    @MethodSource("methods")
    void eachClinicRoleGetsWhatTheMatrixGivesIt(Method method) {
        for (ClinicRole role : ClinicRole.values()) {
            Optional<SectionAccess> access = clinicAccess(role);
            boolean expected = isRead(method) ? access.isPresent() : access.equals(Optional.of(SectionAccess.EDIT));

            assertThat(allows(method, "ROLE_" + role.name()))
                    .as("%s for %s", name(method), role)
                    .isEqualTo(expected);
        }
    }

    /** A platform administrator is no member of the clinic and never sees its sections (ADR 0016). */
    @ParameterizedTest
    @MethodSource("methods")
    void thePlatformAdministratorGetsNothing(Method method) {
        assertThat(ConfigurationSection.visibleToPlatformAdmin())
                .noneMatch(visible -> visible.section() == ConfigurationSection.CLINIC);
        assertThat(allows(method, "ROLE_PLATFORM_ADMIN")).as(name(method)).isFalse();
    }

    /** The matrix this test reads: if it changes, so must the annotations, and this test says where. */
    @Test
    void theMatrixLetsEveryRoleSeeTheSectionAndOnlyTheAdministratorEditIt() {
        for (ClinicRole role : ClinicRole.values()) {
            assertThat(clinicAccess(role))
                    .as(role.name())
                    .contains(role == ClinicRole.ADMIN_CLINICA ? SectionAccess.EDIT : SectionAccess.VIEW);
        }
    }

    static Stream<Method> methods() {
        return SERVICES.stream()
                .flatMap(service -> Arrays.stream(service.getDeclaredMethods()))
                .filter(method -> Modifier.isPublic(method.getModifiers())
                        && !Modifier.isStatic(method.getModifiers())
                        && !method.isSynthetic());
    }

    private boolean allows(Method method, String authority) {
        PreAuthorize annotation = preAuthorize(method);
        assertThat(annotation).as(name(method)).isNotNull();
        Authentication user = UsernamePasswordAuthenticationToken.authenticated(
                "user", null, AuthorityUtils.createAuthorityList(authority));
        EvaluationContext context =
                handler.createEvaluationContext(() -> user, new SimpleMethodInvocation(null, method));
        return ExpressionUtils.evaluateAsBoolean(
                handler.getExpressionParser().parseExpression(annotation.value()), context);
    }

    /** The method's own annotation, or else its class's, as Spring Security looks for it. */
    private static @Nullable PreAuthorize preAuthorize(Method method) {
        PreAuthorize own = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
        return own != null
                ? own
                : AnnotatedElementUtils.findMergedAnnotation(method.getDeclaringClass(), PreAuthorize.class);
    }

    /** A read-only transaction cannot write, so a method that has one is a read. */
    private static boolean isRead(Method method) {
        Transactional transactional = AnnotatedElementUtils.findMergedAnnotation(method, Transactional.class);
        return transactional != null && transactional.readOnly();
    }

    private static Optional<SectionAccess> clinicAccess(ClinicRole role) {
        return ConfigurationSection.visibleTo(role).stream()
                .filter(visible -> visible.section() == ConfigurationSection.CLINIC)
                .map(ConfigurationSection.VisibleSection::access)
                .findFirst();
    }

    private static String name(Method method) {
        return method.getDeclaringClass().getSimpleName() + "." + method.getName();
    }
}
