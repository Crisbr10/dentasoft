package es.crisbr.dentasoft.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.DentasoftApplication;
import jakarta.persistence.Entity;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.ResolvableType;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.util.ClassUtils;

/**
 * Guard for the isolation test every module with clinic data must have (AGENTS.md): every entity that extends
 * {@link TenantScopedEntity}, in the application or its tests, needs a subclass of {@link TenantIsolationTest} for it.
 * It also fails if the scan finds no such entity at all, so that it never passes without checking anything.
 */
class TenantIsolationCoverageTests {

    private static final String BASE_PACKAGE = DentasoftApplication.class.getPackageName();

    @Test
    void everyClinicEntityHasAnIsolationTest() {
        assertThat(problems(clinicEntities(BASE_PACKAGE), coveredEntities(BASE_PACKAGE)))
                .as("entities without an isolation test; see TenantIsolationTest")
                .isEmpty();
    }

    @Test
    void findsTheKnownEntityAndItsIsolationTest() {
        assertThat(clinicEntities(BASE_PACKAGE)).contains(TenancyTestRecord.class);
        assertThat(coveredEntities(BASE_PACKAGE)).contains(TenancyTestRecord.class);
        assertThat(clinicEntities(BASE_PACKAGE)).doesNotContain(TenancyTestRecordWithoutTenantId.class);
    }

    @Test
    void reportsAnEntityWithoutAnIsolationTest() {
        assertThat(problems(Set.of(TenancyTestRecord.class), Set.of()))
                .containsExactly(TenancyTestRecord.class.getName()
                        + " has no isolation test: add a subclass of TenantIsolationTest<TenancyTestRecord>");
    }

    @Test
    void failsWhenTheScanFindsNoEntities() {
        Set<Class<?>> none = clinicEntities(BASE_PACKAGE + ".nonexistent");

        assertThat(none).isEmpty();
        assertThat(problems(none, Set.of()))
                .containsExactly("found no entity extending TenantScopedEntity under " + BASE_PACKAGE
                        + ": the scan no longer finds the classes, so nothing would be checked");
    }

    /** What is wrong, one line per problem; empty when every entity found has its isolation test. */
    private static List<String> problems(Collection<Class<?>> entities, Collection<Class<?>> covered) {
        if (entities.isEmpty()) {
            return List.of("found no entity extending TenantScopedEntity under " + BASE_PACKAGE
                    + ": the scan no longer finds the classes, so nothing would be checked");
        }
        return entities.stream()
                .filter(entity -> !covered.contains(entity))
                .sorted(Comparator.comparing(Class::getName))
                .map(entity -> "%s has no isolation test: add a subclass of TenantIsolationTest<%s>"
                        .formatted(entity.getName(), entity.getSimpleName()))
                .toList();
    }

    /** Concrete {@code @Entity} classes that extend {@link TenantScopedEntity}, in application and test classes. */
    private static Set<Class<?>> clinicEntities(String basePackage) {
        return concreteSubclassesOf(TenantScopedEntity.class, basePackage).stream()
                .filter(type -> type.isAnnotationPresent(Entity.class))
                .collect(Collectors.toSet());
    }

    /** The entities that a concrete subclass of {@link TenantIsolationTest} tests. */
    private static Set<Class<?>> coveredEntities(String basePackage) {
        return concreteSubclassesOf(TenantIsolationTest.class, basePackage).stream()
                .map(test -> ResolvableType.forClass(test)
                        .as(TenantIsolationTest.class)
                        .resolveGeneric(0))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private static Set<Class<?>> concreteSubclassesOf(Class<?> type, String basePackage) {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(type));
        ClassLoader classLoader = TenantIsolationCoverageTests.class.getClassLoader();
        return scanner.findCandidateComponents(basePackage).stream()
                .map(candidate ->
                        ClassUtils.resolveClassName(Objects.requireNonNull(candidate.getBeanClassName()), classLoader))
                .collect(Collectors.toSet());
    }
}
