package es.crisbr.dentasoft.shared;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.provider.sql.DatabaseProduct;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduled tasks ({@code @Scheduled}) that run once across replicas (M01-T08, ADR 0013): a task that also has
 * {@code @SchedulerLock(name = "…")} only runs where it takes its lock, a row in {@code scheduler.shedlock}.
 *
 * <ul>
 *   <li>Times come from the database ({@code usingDbTime()}), so replicas with different clocks or time zones agree on
 *       when a lock ends.
 *   <li>No transaction manager is given: ShedLock then uses one of its own for the lock row, which is not a bean and
 *       does not reach {@code TenantAwareJpaDialectPostProcessor}. The lock table has no clinic data.
 *   <li>A task that works with clinics' data runs them with {@code ClinicBatch}, one clinic and one transaction at a
 *       time.
 * </ul>
 *
 * <p>A lock is held at most 10 minutes unless the task says otherwise ({@code lockAtMostFor}): if a replica dies
 * holding it, the task runs again after that time. It is also held at least 30 seconds ({@code lockAtLeastFor}), even
 * when the task ends sooner: the triggers of two replicas never fire at exactly the same instant, and without it a
 * short task would be over, and its lock free, by the time the other replica's trigger fires, so it would run twice. A
 * task that runs every 30 seconds or more often sets a {@code lockAtLeastFor} clearly shorter than its interval, such as
 * half of it: a lock that ends just as the next trigger fires makes runs skip at random. A task that lowers
 * {@code lockAtMostFor} below 30 seconds lowers {@code lockAtLeastFor} too: ShedLock refuses a minimum longer than the
 * maximum, and only when the task tries to take its lock, so the task would never run.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M", defaultLockAtLeastFor = "PT30S")
class SchedulingConfiguration {

    static final String LOCK_TABLE = "scheduler.shedlock";

    @Bean
    LockProvider lockProvider(DataSource dataSource) {
        return lockProvider(dataSource, null);
    }

    /**
     * The lock provider on {@code dataSource}; {@code lockedBy} names the replica in the lock row, or {@code null} for
     * the host name.
     */
    static LockProvider lockProvider(DataSource dataSource, String lockedBy) {
        JdbcTemplateLockProvider.Configuration.Builder configuration = JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTableName(LOCK_TABLE)
                .withDatabaseProduct(DatabaseProduct.POSTGRES_SQL)
                .usingDbTime();
        if (lockedBy != null) {
            configuration.withLockedByValue(lockedBy);
        }
        return new JdbcTemplateLockProvider(configuration.build());
    }
}
