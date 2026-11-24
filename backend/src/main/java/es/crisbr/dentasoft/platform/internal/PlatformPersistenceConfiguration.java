package es.crisbr.dentasoft.platform.internal;

import com.zaxxer.hikari.HikariDataSource;
import es.crisbr.dentasoft.tenancy.TenancyPersistence;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;

/**
 * The platform persistence unit (ADR 0011): its own pool as {@code dentasoft_platform}, its own entity manager factory
 * with only the entities of {@link #ENTITIES_PACKAGE}, and its own transaction manager.
 *
 * <p>Every bean is {@code defaultCandidate = false}: Spring Boot keeps creating the main data source, entity manager
 * factory and transaction manager, and nothing gets these by type. They are reached only by name, and only from this
 * module ({@code PlatformDatabaseAccessTests}).
 *
 * <p>The tenancy module's post-processor gives this factory the {@code TenantAwareJpaDialect} too, and checks this
 * transaction manager like the main one. {@link TenancyPersistence#hibernateProperties()} makes its sessions follow the
 * active clinic: platform services run without one, so the dialect sets no clinic in PostgreSQL, and the policies
 * written for {@code dentasoft_platform} let it see every clinic.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PlatformDataSourceProperties.class)
class PlatformPersistenceConfiguration {

    static final String UNIT = "platform";
    static final String DATA_SOURCE = "platformDataSource";
    static final String ENTITY_MANAGER_FACTORY = "platformEntityManagerFactory";
    static final String TRANSACTION_MANAGER = "platformTransactionManager";
    static final String ENTITIES_PACKAGE = "es.crisbr.dentasoft.platform.persistence";

    /** Platform administration is rare and short: two connections are enough, and none is kept idle. */
    static final int MAXIMUM_POOL_SIZE = 2;

    @Bean(name = DATA_SOURCE, defaultCandidate = false)
    HikariDataSource platformDataSource(PlatformDataSourceProperties properties) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("dentasoft-platform");
        dataSource.setJdbcUrl(properties.url());
        dataSource.setUsername(properties.username());
        dataSource.setPassword(properties.password());
        dataSource.setMaximumPoolSize(MAXIMUM_POOL_SIZE);
        dataSource.setMinimumIdle(0);
        return dataSource;
    }

    @Bean(name = ENTITY_MANAGER_FACTORY, defaultCandidate = false)
    LocalContainerEntityManagerFactoryBean platformEntityManagerFactory(
            EntityManagerFactoryBuilder builder, @Qualifier(DATA_SOURCE) DataSource dataSource) {
        return builder.dataSource(dataSource)
                .packages(ENTITIES_PACKAGE)
                .persistenceUnit(UNIT)
                .properties(TenancyPersistence.hibernateProperties())
                .build();
    }

    @Bean(name = TRANSACTION_MANAGER, defaultCandidate = false)
    JpaTransactionManager platformTransactionManager(
            @Qualifier(ENTITY_MANAGER_FACTORY) EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }
}
