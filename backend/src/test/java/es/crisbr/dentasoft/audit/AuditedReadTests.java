package es.crisbr.dentasoft.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.audit.AuditLogDatabase.StoredEntry;
import es.crisbr.dentasoft.tenancy.TenantContext;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@link AuditedRead}: a read is recorded once it has finished, in a transaction of its own, so it works after a
 * read-only transaction; a denied read is recorded too; and a read whose entry cannot be written never returns what it
 * read.
 *
 * <p>The order is audit, then {@code @PreAuthorize}, then {@code @Transactional}. The parallel test proves it with the
 * pool of the tests, two connections: if the entry were written while the read's transaction still held its
 * connection, two threads would each hold one and wait forever for a second.
 *
 * <p>No clinic rows are needed: the log has no foreign key to {@code clinic}, and Row Level Security only compares ids.
 */
@IntegrationTest
@WithMockUser(roles = "ADMIN_CLINICA")
class AuditedReadTests {

    @Autowired
    private Reader reader;

    @Autowired
    private TransactionTemplate transaction;

    private final UUID clinic = UUID.randomUUID();

    @Test
    void aReadIsRecordedAfterItsReadOnlyTransaction() throws SQLException {
        UUID patient = UUID.randomUUID();

        String readOnly = TenantContext.callAs(clinic, () -> reader.read(patient));

        assertThat(readOnly).as("the read ran in a read-only transaction").isEqualTo("on");
        assertThat(AuditLogDatabase.entries(clinic, patient)).singleElement().satisfies(entry -> {
            assertThat(entry.tenantId()).isEqualTo(clinic);
            assertThat(entry.action()).isEqualTo("READ");
            assertThat(entry.entityType()).isEqualTo("CLINIC");
            assertThat(entry.outcome()).isEqualTo("SUCCESS");
            assertThat(entry.actorType()).isEqualTo("SYSTEM");
        });
    }

    @Test
    @WithMockUser(roles = "RECEPCION")
    void aDeniedReadIsRecordedAsDenied() throws SQLException {
        UUID patient = UUID.randomUUID();

        assertThatThrownBy(() -> TenantContext.callAs(clinic, () -> reader.read(patient)))
                .isInstanceOf(AccessDeniedException.class);

        assertThat(AuditLogDatabase.entries(clinic, patient))
                .extracting(StoredEntry::action, StoredEntry::outcome)
                .containsExactly(tuple("READ", "DENIED"));
    }

    /** The exception ADR 0012 documents: called inside an open transaction, the entry takes a new one. */
    @Test
    void aReadInsideAnOpenTransactionIsRecordedInANewOne() throws SQLException {
        UUID patient = UUID.randomUUID();
        TransactionTemplate readOnly = new TransactionTemplate(transaction.getTransactionManager());
        readOnly.setReadOnly(true);

        TenantContext.callAs(clinic, () -> readOnly.execute(status -> reader.read(patient)));

        assertThat(AuditLogDatabase.actions(clinic, patient)).containsExactly("READ");
    }

    @Test
    void aReadWhoseEntryCannotBeWrittenReturnsNothing() throws SQLException {
        UUID patient = UUID.randomUUID();
        List<String> returned = new ArrayList<>();

        AuditLogDatabase.withoutApplicationInsert(() -> assertThatThrownBy(
                        () -> returned.add(TenantContext.callAs(clinic, () -> reader.read(patient))))
                .isInstanceOf(DataAccessException.class));

        assertThat(returned).isEmpty();
        assertThat(AuditLogDatabase.entries(clinic, patient)).isEmpty();
    }

    /** Without an active clinic the entry has nowhere to go, so the read fails rather than go unrecorded. */
    @Test
    void aReadWithoutAnActiveClinicFails() {
        assertThatThrownBy(() -> reader.read(UUID.randomUUID())).isInstanceOf(DataAccessException.class);
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void manyParallelReadsShareTwoConnectionsWithoutWaitingForever() throws Exception {
        int threads = 8;
        int readsPerThread = 25;
        List<UUID> patients = new ArrayList<>();
        List<Future<?>> results = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) {
                UUID patient = UUID.randomUUID();
                patients.add(patient);
                results.add(executor.submit(() -> {
                    SecurityContextHolder.getContext()
                            .setAuthentication(new TestingAuthenticationToken("admin", null, "ROLE_ADMIN_CLINICA"));
                    try {
                        for (int i = 0; i < readsPerThread; i++) {
                            TenantContext.callAs(clinic, () -> reader.slowRead(patient));
                        }
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                }));
            }
            for (Future<?> result : results) {
                // Rethrows the first failure, such as a connection timeout, as the test's.
                result.get();
            }
        }

        for (UUID patient : patients) {
            assertThat(AuditLogDatabase.actions(clinic, patient)).hasSize(readsPerThread);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ReaderConfiguration {

        @Bean
        Reader reader(JdbcClient jdbc) {
            return new Reader(jdbc);
        }
    }

    /** A service read, as a module would write one: audited, authorized and read-only. */
    static class Reader {

        private final JdbcClient jdbc;

        Reader(JdbcClient jdbc) {
            this.jdbc = jdbc;
        }

        /** Whether its transaction is read-only, as PostgreSQL says. */
        @AuditedRead(AuditEntityType.CLINIC)
        @PreAuthorize("hasRole('ADMIN_CLINICA')")
        @Transactional(readOnly = true)
        public String read(@AuditedEntityId UUID id) {
            return jdbc.sql("SELECT current_setting('transaction_read_only')")
                    .query(String.class)
                    .single();
        }

        /** Holds its connection a little, so parallel reads compete for the pool. */
        @AuditedRead(AuditEntityType.CLINIC)
        @PreAuthorize("hasRole('ADMIN_CLINICA')")
        @Transactional(readOnly = true)
        public String slowRead(@AuditedEntityId UUID id) {
            return jdbc.sql("SELECT pg_sleep(0.02)::text").query(String.class).single();
        }
    }
}
