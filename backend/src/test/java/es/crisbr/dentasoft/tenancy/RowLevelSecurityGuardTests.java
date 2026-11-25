package es.crisbr.dentasoft.tenancy;

import static es.crisbr.dentasoft.TestPostgresConfiguration.APP_USER;
import static es.crisbr.dentasoft.TestPostgresConfiguration.PLATFORM_USER;
import static es.crisbr.dentasoft.TestPostgresConfiguration.connectAsApplication;
import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.tenancy.TenantIsolationInspector.AllowedPolicy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Guard for the multi-clinic rule of AGENTS.md, run on the schema after all migrations: every table except Flyway's
 * history is isolated by clinic (see {@link TenantIsolationInspector}), and the application role cannot escape Row
 * Level Security. A new table that skips {@code enable_tenant_isolation}, or brings a policy of its own, fails here.
 */
@IntegrationTest
class RowLevelSecurityGuardTests {

    private static final String SCHEMA = "public";

    /**
     * Tables whose policy compares a column other than {@code tenant_id}. Every entry needs a reason: {@code clinic} is
     * the tenant itself, so its rows are isolated by {@code id}.
     */
    private static final Map<String, String> TENANT_COLUMN_EXCEPTIONS = Map.of("clinic", "id");

    /**
     * Policies allowed next to {@code tenant_isolation}. Every entry needs a reason: the platform administration
     * (ADR 0011, V002) lists, creates and changes the status of every clinic, and creates their initial settings, as
     * {@code dentasoft_platform} only. It also records what it does in {@code audit_log}, under the affected clinic,
     * but only as itself and only about that clinic, creating or changing it (V004). No DELETE policy, and none on any other table.
     */
    private static final Set<AllowedPolicy> ALLOWED_POLICIES = Set.of(
            new AllowedPolicy("clinic", "platform_select", PLATFORM_USER, "SELECT", "true", null),
            new AllowedPolicy("clinic", "platform_insert", PLATFORM_USER, "INSERT", null, "true"),
            new AllowedPolicy("clinic", "platform_update", PLATFORM_USER, "UPDATE", "true", "true"),
            new AllowedPolicy("clinic_settings", "platform_insert", PLATFORM_USER, "INSERT", null, "true"),
            new AllowedPolicy(
                    "audit_log",
                    "platform_insert",
                    PLATFORM_USER,
                    "INSERT",
                    null,
                    "((actor_type = 'PLATFORM'::text) AND (entity_type = 'CLINIC'::text) AND (entity_id = tenant_id)"
                            + " AND (action = ANY (ARRAY['CREATE'::text, 'UPDATE'::text])))"));

    /**
     * Schemas other than {@code public}, which hold no clinic data and so have no Row Level Security. Every entry needs a
     * reason, and its tables and grants are checked one by one: {@code scheduler} holds ShedLock's lock rows (V005,
     * ADR 0013).
     */
    private static final Map<String, String> SCHEMA_EXCEPTIONS =
            Map.of("scheduler", "locks of scheduled tasks, without clinic data (V005, ADR 0013)");

    /** The only privileges anyone but the owner has in {@code scheduler}: the application's, on its lock table. */
    private static final Map<String, Set<String>> SCHEDULER_TABLE_PRIVILEGES =
            Map.of(APP_USER, Set.of("SELECT", "INSERT", "UPDATE"), PLATFORM_USER, Set.of());

    private static final List<String> TABLE_PRIVILEGES =
            List.of("SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER");

    @Autowired
    private Flyway flyway;

    private Connection connection;

    @BeforeEach
    void connect() throws SQLException {
        connection = connectAsApplication();
    }

    @AfterEach
    void disconnect() throws SQLException {
        connection.close();
    }

    @Test
    void everyTableIsIsolatedByClinic() throws SQLException {
        TenantIsolationInspector inspector = new TenantIsolationInspector(
                connection,
                SCHEMA,
                TENANT_COLUMN_EXCEPTIONS,
                Set.of(flyway.getConfiguration().getTable()),
                ALLOWED_POLICIES);

        assertThat(inspector.tables()).as("tables of schema %s", SCHEMA).contains("clinic");
        assertThat(inspector.problems())
                .as("tables not isolated by clinic; create them as described in docs/plan/M01-clinicas-multitenant.md")
                .isEmpty();
    }

    /** A table outside {@code public} escapes the check above: only the listed schemas may exist. */
    @Test
    void noSchemaOtherThanPublicAndTheListedExceptions() throws SQLException {
        List<String> schemas = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT nspname FROM pg_namespace"
                        // Names starting with pg_ are reserved to PostgreSQL (pg_catalog, pg_toast, pg_temp_N…).
                        + " WHERE left(nspname, 3) <> 'pg_' AND nspname <> 'information_schema'"
                        + " ORDER BY nspname");
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                schemas.add(rows.getString(1));
            }
        }

        Set<String> allowed = new HashSet<>(SCHEMA_EXCEPTIONS.keySet());
        allowed.add(SCHEMA);
        assertThat(schemas)
                .as("schemas; a new one needs its own reason in SCHEMA_EXCEPTIONS")
                .containsExactlyInAnyOrderElementsOf(allowed);
    }

    /** The scheduler schema holds the lock table alone, and the application can only use it, not change it. */
    @Test
    void schedulerSchemaHoldsOnlyTheLockTableWithExactGrants() throws SQLException {
        List<String> objects = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT c.relname FROM pg_class c"
                        + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                        + " WHERE n.nspname = 'scheduler' AND c.relkind IN ('r', 'p', 'v', 'm', 'f', 'S')");
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                objects.add(rows.getString(1));
            }
        }
        assertThat(objects)
                .as("tables, views and sequences of schema scheduler")
                .containsExactly("shedlock");

        for (Map.Entry<String, Set<String>> role : SCHEDULER_TABLE_PRIVILEGES.entrySet()) {
            Set<String> granted = new HashSet<>();
            for (String privilege : TABLE_PRIVILEGES) {
                if (hasPrivilege("has_table_privilege", role.getKey(), "scheduler.shedlock", privilege)) {
                    granted.add(privilege);
                }
            }
            assertThat(granted)
                    .as("privileges of %s on scheduler.shedlock", role.getKey())
                    .isEqualTo(role.getValue());
            assertThat(hasPrivilege("has_schema_privilege", role.getKey(), "scheduler", "CREATE"))
                    .as("%s can create objects in scheduler", role.getKey())
                    .isFalse();
        }
        assertThat(hasPrivilege("has_schema_privilege", APP_USER, "scheduler", "USAGE"))
                .isTrue();
        assertThat(hasPrivilege("has_schema_privilege", PLATFORM_USER, "scheduler", "USAGE"))
                .isFalse();
    }

    @Test
    void applicationRoleDoesNotBypassRowLevelSecurity() throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = ?")) {
            statement.setString(1, APP_USER);
            try (ResultSet role = statement.executeQuery()) {
                assertThat(role.next()).isTrue();
                assertThat(role.getBoolean("rolsuper")).isFalse();
                assertThat(role.getBoolean("rolbypassrls")).isFalse();
            }
        }
    }

    @Test
    void applicationRoleOwnsNothing() throws SQLException {
        // Ownership of any object (table, view, sequence, function, type, schema, database) is an 'o' entry in
        // pg_shdepend, whichever database of the cluster the object lives in.
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT count(*) FROM pg_shdepend WHERE deptype = 'o'"
                        + " AND refclassid = 'pg_authid'::regclass"
                        + " AND refobjid = (SELECT oid FROM pg_roles WHERE rolname = ?)")) {
            statement.setString(1, APP_USER);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong(1)).isZero();
            }
        }
    }

    /** {@code function} is one of PostgreSQL's privilege functions: never a value from outside the test. */
    private boolean hasPrivilege(String function, String role, String object, String privilege) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT %s(?, ?, ?)".formatted(privilegeFunction(function)))) {
            statement.setString(1, role);
            statement.setString(2, object);
            statement.setString(3, privilege);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private static String privilegeFunction(String function) {
        if (!Set.of("has_table_privilege", "has_schema_privilege").contains(function)) {
            throw new IllegalArgumentException("not a privilege function: " + function);
        }
        return function;
    }
}
