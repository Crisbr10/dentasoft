package es.crisbr.dentasoft;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import es.crisbr.dentasoft.tenancy.TenantScopedEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.EntityType;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Each persistence unit has exactly its entities (ADR 0011): the main unit, the application's, never maps a platform
 * entity, and the platform unit maps only the clinic, the initial clinic settings and its own audit log entries, never
 * clinic data.
 */
@IntegrationTest
class PersistenceUnitsTests {

    private static final String PLATFORM_PACKAGE = "es.crisbr.dentasoft.platform.";

    private static final Set<String> PLATFORM_ENTITIES = Set.of(
            "es.crisbr.dentasoft.platform.persistence.PlatformAdminMembership",
            "es.crisbr.dentasoft.platform.persistence.PlatformAuditRecord",
            "es.crisbr.dentasoft.platform.persistence.PlatformClinic",
            "es.crisbr.dentasoft.platform.persistence.PlatformClinicSettings");

    @Autowired
    private EntityManagerFactory mainUnit;

    @Autowired
    @Qualifier("platformEntityManagerFactory")
    private EntityManagerFactory platformUnit;

    @Autowired
    private PlatformTransactionManager mainTransactionManager;

    @Autowired
    @Qualifier("platformTransactionManager")
    private PlatformTransactionManager platformTransactionManager;

    @Autowired
    private DataSource mainDataSource;

    @Autowired
    @Qualifier("platformDataSource")
    private DataSource platformDataSource;

    @Test
    void thePlatformUnitMapsOnlyThePlatformEntities() {
        assertThat(names(platformUnit)).containsExactlyInAnyOrderElementsOf(PLATFORM_ENTITIES);
        assertThat(entities(platformUnit))
                .as("clinic data (TenantScopedEntity) in the platform unit")
                .noneMatch(TenantScopedEntity.class::isAssignableFrom);
    }

    @Test
    void theMainUnitMapsNoPlatformEntity() {
        assertThat(names(mainUnit)).noneMatch(name -> name.startsWith(PLATFORM_PACKAGE));
        assertThat(names(mainUnit)).contains("es.crisbr.dentasoft.tenancy.persistence.ClinicSettings");
    }

    @Test
    void everyEntityBelongsToExactlyOneUnit() {
        Set<String> scanned = scannedEntities();
        Set<String> inBoth = new HashSet<>(names(mainUnit));
        inBoth.retainAll(names(platformUnit));
        Set<String> inAny = new HashSet<>(names(mainUnit));
        inAny.addAll(names(platformUnit));

        assertThat(scanned).as("the scan must find the entities").containsAll(PLATFORM_ENTITIES);
        assertThat(inBoth).as("entities in both units").isEmpty();
        assertThat(scanned)
                .as("entities in no unit: add their package to EntityPackagesConfiguration")
                .containsExactlyInAnyOrderElementsOf(inAny);
    }

    @Test
    void eachUnitHasItsOwnPoolAndTransactionManager() {
        assertThat(platformDataSource).isNotSameAs(mainDataSource);
        HikariDataSource pool = (HikariDataSource) platformDataSource;
        assertThat(pool.getUsername()).isEqualTo(TestPostgresConfiguration.PLATFORM_USER);
        assertThat(pool.getMaximumPoolSize()).isEqualTo(2);

        assertThat(mainTransactionManager).isNotSameAs(platformTransactionManager);
        assertThat(((JpaTransactionManager) mainTransactionManager).getEntityManagerFactory())
                .isSameAs(mainUnit);
        assertThat(((JpaTransactionManager) platformTransactionManager).getEntityManagerFactory())
                .isSameAs(platformUnit);
        // The tenancy post-processor checks every transaction manager: both pass the clinic to PostgreSQL.
        assertThat(((JpaTransactionManager) platformTransactionManager)
                        .getJpaDialect()
                        .getClass()
                        .getSimpleName())
                .isEqualTo("TenantAwareJpaDialect");
    }

    private static Set<Class<?>> entities(EntityManagerFactory unit) {
        return unit.getMetamodel().getEntities().stream()
                .map(EntityType::getJavaType)
                .collect(Collectors.toSet());
    }

    private static Set<String> names(EntityManagerFactory unit) {
        return entities(unit).stream().map(Class::getName).collect(Collectors.toSet());
    }

    /** Every {@code @Entity} of the application and its tests, found on the classpath. */
    private static Set<String> scannedEntities() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        return scanner.findCandidateComponents(DentasoftApplication.class.getPackageName()).stream()
                .map(definition -> Objects.requireNonNull(definition.getBeanClassName()))
                .collect(Collectors.toSet());
    }
}
