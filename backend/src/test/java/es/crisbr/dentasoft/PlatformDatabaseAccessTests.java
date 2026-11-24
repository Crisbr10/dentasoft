package es.crisbr.dentasoft;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import es.crisbr.dentasoft.shared.PlatformDatabaseAccessFixtures;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Only the platform module reaches the platform database (ADR 0011). Its beans are not default candidates, so nothing
 * gets them by type; this test stops any other module from asking for them by name: a qualifier, a transaction
 * manager name or the persistence unit name. Its configuration lives in {@code platform.internal}, which {@link
 * ModularityTests} already keeps other modules from using.
 *
 * <p>ArchUnit comes with Spring Modulith ({@code spring-modulith-starter-test}), so it is not declared on its own.
 */
class PlatformDatabaseAccessTests {

    private static final String PLATFORM_PACKAGE = "es.crisbr.dentasoft.platform..";

    private static final Set<String> PLATFORM_BEANS =
            Set.of("platformDataSource", "platformEntityManagerFactory", "platformTransactionManager");

    private static final String PLATFORM_UNIT = "platform";

    private static final ArchRule ONLY_THE_PLATFORM_MODULE =
            noClasses().that().resideOutsideOfPackage(PLATFORM_PACKAGE).should(usePlatformDatabase());

    @Test
    void onlyThePlatformModuleUsesThePlatformDatabase() {
        JavaClasses application = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(DentasoftApplication.class.getPackageName());

        assertThat(application.stream()
                        .anyMatch(c -> c.getPackageName().equals("es.crisbr.dentasoft.platform.internal")))
                .as("the platform module must be imported, or the rule checks nothing")
                .isTrue();
        ONLY_THE_PLATFORM_MODULE.check(application);
    }

    @Test
    void detectsEveryWayToAskForThePlatformDatabase() {
        JavaClasses fixtures = new ClassFileImporter().importClasses(PlatformDatabaseAccessFixtures.CLASSES);

        EvaluationResult result = ONLY_THE_PLATFORM_MODULE.evaluate(fixtures);

        assertThat(result.getFailureReport().getDetails())
                .hasSize(PlatformDatabaseAccessFixtures.VIOLATIONS)
                .anyMatch(line -> line.contains("@Transactional"))
                .anyMatch(line -> line.contains("@Qualifier"))
                .anyMatch(line -> line.contains("@PersistenceContext"))
                .anyMatch(line -> line.contains("@PersistenceUnit"))
                .noneMatch(line -> line.contains("Harmless"));
    }

    private static ArchCondition<JavaClass> usePlatformDatabase() {
        return new ArchCondition<>("use the platform database") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                List<String> uses = new ArrayList<>();
                javaClass
                        .getAnnotations()
                        .forEach(a -> describe(a, javaClass.getName()).ifPresent(uses::add));
                for (JavaMember member : javaClass.getMembers()) {
                    member.getAnnotations()
                            .forEach(a -> describe(a, member.getFullName()).ifPresent(uses::add));
                    if (member instanceof JavaCodeUnit codeUnit) {
                        for (JavaParameter parameter : codeUnit.getParameters()) {
                            parameter
                                    .getAnnotations()
                                    .forEach(a ->
                                            describe(a, codeUnit.getFullName()).ifPresent(uses::add));
                        }
                    }
                }
                uses.forEach(use -> events.add(SimpleConditionEvent.satisfied(javaClass, use)));
            }
        };
    }

    /** What the annotation asks for, if it names a platform bean or the platform unit. */
    private static Optional<String> describe(JavaAnnotation<?> annotation, String where) {
        Map<String, Object> values = annotation.getProperties();
        boolean platform = switch (annotation.getRawType().getName()) {
            case "org.springframework.transaction.annotation.Transactional" ->
                PLATFORM_BEANS.contains(values.get("value"))
                        || PLATFORM_BEANS.contains(values.get("transactionManager"));
            case "org.springframework.beans.factory.annotation.Qualifier" ->
                PLATFORM_BEANS.contains(values.get("value"));
            case "jakarta.persistence.PersistenceContext", "jakarta.persistence.PersistenceUnit" ->
                PLATFORM_UNIT.equals(values.get("unitName"));
            default -> false;
        };
        return platform
                ? Optional.of(
                        "%s uses @%s%s".formatted(where, annotation.getRawType().getSimpleName(), values))
                : Optional.empty();
    }
}
