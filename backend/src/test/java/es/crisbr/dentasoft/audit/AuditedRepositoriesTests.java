package es.crisbr.dentasoft.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import es.crisbr.dentasoft.tenancy.persistence.HoursOfOperation;
import es.crisbr.dentasoft.tenancy.persistence.HoursOfOperationRepository;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.ResolvableType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;

/**
 * The audit listener only sees what Hibernate writes entity by entity ({@link AuditedEntity}). A bulk {@code UPDATE}
 * or {@code DELETE} would change audited rows without a single entry, so the repositories of audited entities may not
 * declare {@code @Modifying} queries, and nobody may call their batch deletes ({@code deleteAllInBatch} and the like).
 *
 * <p>Not covered, as ADR 0012 says: JPQL or native SQL written by hand through the {@code EntityManager} or {@code
 * JdbcClient}.
 */
class AuditedRepositoriesTests {

    /** The {@code JpaRepository} methods that delete without loading the entities. */
    private static final Set<String> BULK_METHODS = Set.of("deleteAllInBatch", "deleteAllByIdInBatch", "deleteInBatch");

    private static final JavaClasses APPLICATION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("es.crisbr.dentasoft");

    @Test
    void noRepositoryOfAnAuditedEntityWritesInBulk() {
        assertThat(violations(APPLICATION)).isEmpty();
    }

    /** The rule must actually see audited repositories, or it would pass on nothing. */
    @Test
    void findsTheRepositoriesOfAuditedEntities() {
        assertThat(auditedRepositories(APPLICATION))
                .extracting(JavaClass::getSimpleName)
                .contains(
                        "ClinicRepository",
                        "ClinicSettingsRepository",
                        "LocationRepository",
                        "HoursOfOperationRepository");
    }

    /** The check itself, on fixtures that break both rules. */
    @Test
    void catchesModifyingQueriesAndBatchDeletes() {
        JavaClasses fixtures = new ClassFileImporter()
                .importClasses(BulkRepository.class, BulkCaller.class, HoursOfOperationRepository.class);

        assertThat(violations(fixtures))
                .containsExactly(
                        "AuditedRepositoriesTests$BulkCaller.replaceAll calls"
                                + " HoursOfOperationRepository.deleteAllInBatch",
                        "AuditedRepositoriesTests$BulkRepository.closeAll is @Modifying");
    }

    private static List<String> violations(JavaClasses classes) {
        List<JavaClass> audited = auditedRepositories(classes);
        Set<String> found = new TreeSet<>();
        for (JavaClass repository : audited) {
            for (Method method : repository.reflect().getMethods()) {
                if (method.isAnnotationPresent(Modifying.class)) {
                    found.add(repository
                                    .getName()
                                    .substring(repository.getPackageName().length() + 1) + "." + method.getName()
                            + " is @Modifying");
                }
            }
        }
        Set<String> auditedNames = new TreeSet<>();
        audited.forEach(repository -> auditedNames.add(repository.getName()));
        for (JavaClass caller : classes) {
            for (JavaMethodCall call : caller.getMethodCallsFromSelf()) {
                if (BULK_METHODS.contains(call.getName())
                        && auditedNames.contains(call.getTargetOwner().getName())) {
                    found.add(caller.getName().substring(caller.getPackageName().length() + 1) + "."
                            + call.getOrigin().getName() + " calls "
                            + call.getTargetOwner().getSimpleName() + "."
                            + call.getName());
                }
            }
        }
        return new ArrayList<>(found);
    }

    /** Spring Data repositories, interfaces only, whose domain type is an {@link AuditedEntity}. */
    private static List<JavaClass> auditedRepositories(JavaClasses classes) {
        return classes.stream()
                .filter(JavaClass::isInterface)
                .filter(type -> type.isAssignableTo(Repository.class))
                .filter(type -> {
                    Class<?> domain = ResolvableType.forClass(type.reflect())
                            .as(Repository.class)
                            .getGeneric(0)
                            .resolve();
                    return domain != null && domain.isAnnotationPresent(AuditedEntity.class);
                })
                .toList();
    }

    /** Not a bean: {@code @NoRepositoryBean} keeps Spring Data from creating it in integration tests. */
    @NoRepositoryBean
    interface BulkRepository extends JpaRepository<HoursOfOperation, UUID> {

        @Modifying
        @Query("DELETE FROM HoursOfOperation h")
        int closeAll();
    }

    static final class BulkCaller {

        private final HoursOfOperationRepository hours;

        BulkCaller(HoursOfOperationRepository hours) {
            this.hours = hours;
        }

        void replaceAll() {
            hours.deleteAllInBatch();
        }
    }
}
