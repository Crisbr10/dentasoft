package es.crisbr.dentasoft.tenancy;

import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoint: the user PostgreSQL sees ({@code app.user_id}) in a transaction of the request and in a {@code
 * REQUIRES_NEW} transaction opened inside it, on another connection, as the audit log of reads does.
 */
@RestController
class DatabaseUserTestEndpoint {

    static final String PATH = "/api/v1/test/database-user";

    private final TransactionTemplate required;
    private final TransactionTemplate requiresNew;
    private final JdbcClient jdbc;

    DatabaseUserTestEndpoint(PlatformTransactionManager transactionManager, JdbcClient jdbc) {
        this.required = new TransactionTemplate(transactionManager);
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.jdbc = jdbc;
    }

    @GetMapping(PATH)
    Map<String, String> databaseUser() {
        return required.execute(outer -> Map.of(
                "required", databaseUser(jdbc),
                "requiresNew", Objects.requireNonNull(requiresNew.execute(inner -> databaseUser(jdbc)))));
    }

    /** {@code app.user_id} of the current transaction, {@code ""} when it is not set. */
    static String databaseUser(JdbcClient jdbc) {
        return jdbc.sql("SELECT coalesce(current_setting('app.user_id', true), '')")
                .query(String.class)
                .single();
    }
}
