package es.crisbr.dentasoft.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.TestData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * No connection goes back to the pool with a clinic: many virtual threads share a pool of two connections, switching
 * between clinics A and B, and some of their transactions fail. Every transaction must see only its own clinic, and
 * afterwards every pooled connection, used without a transaction, must have no clinic and see no rows.
 */
@IntegrationTest
class PooledConnectionClinicLeakTests {

    private static final UUID CLINIC_A = TestData.CLINIC_A.id();
    private static final UUID CLINIC_B = TestData.CLINIC_B.id();
    private static final int TRANSACTIONS = 400;
    /** One transaction in this many fails, half of them after writing. */
    private static final int FAILING_EVERY = 5;

    @Autowired
    private TenancyTestRecordRepository records;

    @Autowired
    private TransactionTemplate transaction;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void seed() throws SQLException {
        TenancyTestDatabase.insertClinics();
        TenantContext.runAs(
                CLINIC_A,
                () -> transaction.executeWithoutResult(status -> records.save(new TenancyTestRecord("north"))));
        TenantContext.runAs(
                CLINIC_B,
                () -> transaction.executeWithoutResult(status -> records.save(new TenancyTestRecord("south"))));
    }

    @AfterEach
    void deleteData() throws SQLException {
        TenancyTestDatabase.deleteClinicsAndRecords();
    }

    @Test
    void noConnectionGoesBackToThePoolWithAClinic() throws Exception {
        assertThat(dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize())
                .isEqualTo(2);
        Queue<String> wrongViews = new ConcurrentLinkedQueue<>();
        Set<Integer> usedConnections = ConcurrentHashMap.newKeySet();
        AtomicInteger failed = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> running = new ArrayList<>();
            for (int i = 0; i < TRANSACTIONS; i++) {
                int n = i;
                running.add(threads.submit(() -> {
                    start.await();
                    try {
                        runTransaction(n, wrongViews, usedConnections);
                    } catch (SimulatedFailure e) {
                        failed.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> task : running) {
                task.get(2, TimeUnit.MINUTES);
            }
        }

        assertThat(wrongViews).isEmpty();
        assertThat(failed).hasValue(TRANSACTIONS / FAILING_EVERY);
        List<PooledConnections.IdleConnection> idle = PooledConnections.inspectAll(dataSource);
        // The inspected connections are the ones the transactions used, so the checks below are not vacuous.
        assertThat(idle)
                .extracting(PooledConnections.IdleConnection::backendPid)
                .isSubsetOf(usedConnections);
        assertThat(idle).hasSize(2).allSatisfy(connection -> {
            assertThat(connection.hasNoClinic()).as("clinic of %s", connection).isTrue();
            assertThat(connection.visibleRows())
                    .as("rows seen by %s", connection)
                    .isZero();
        });
        assertThat(TenancyTestDatabase.countStored(CLINIC_A)).isEqualTo(1);
        assertThat(TenancyTestDatabase.countStored(CLINIC_B)).isEqualTo(1);
    }

    /** Transaction {@code n}: clinics alternate, and every {@value #FAILING_EVERY}th one fails. */
    private void runTransaction(int n, Queue<String> wrongViews, Set<Integer> usedConnections) {
        UUID clinic = n % 2 == 0 ? CLINIC_A : CLINIC_B;
        String expected = clinic.equals(CLINIC_A) ? "north" : "south";
        TenantContext.runAs(
                clinic,
                () -> transaction.executeWithoutResult(status -> {
                    usedConnections.add(jdbc.sql("SELECT pg_backend_pid()")
                            .query(Integer.class)
                            .single());
                    String databaseClinic = jdbc.sql("SELECT current_setting('app.tenant_id', true)")
                            .query(String.class)
                            .single();
                    List<String> labels = jdbc.sql("SELECT label FROM tenancy_test_record")
                            .query(String.class)
                            .list();
                    if (!clinic.toString().equals(databaseClinic) || !labels.equals(List.of(expected))) {
                        wrongViews.add("transaction %d for %s saw clinic %s and rows %s"
                                .formatted(
                                        n,
                                        clinic,
                                        databaseClinic,
                                        labels.stream().collect(Collectors.joining(", "))));
                    }
                    if (n % FAILING_EVERY == 0) {
                        if (n % (2 * FAILING_EVERY) == 0) {
                            records.saveAndFlush(new TenancyTestRecord("rolled back"));
                        }
                        throw new SimulatedFailure();
                    }
                }));
    }

    private static final class SimulatedFailure extends RuntimeException {}
}
