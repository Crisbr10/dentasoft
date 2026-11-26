package es.crisbr.dentasoft.tenancy;

import static es.crisbr.dentasoft.TestPostgresConfiguration.connectAsOwner;
import static es.crisbr.dentasoft.tenancy.TenantIsolationInspector.expectedExpression;
import static es.crisbr.dentasoft.tenancy.TenantIsolationInspector.expectedUserExpression;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import es.crisbr.dentasoft.IntegrationTest;
import es.crisbr.dentasoft.tenancy.TenantIsolationInspector.AllowedPolicy;
import es.crisbr.dentasoft.tenancy.TenantIsolationInspector.ApplicationReadPolicy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves that {@link TenantIsolationInspector} (and so {@link RowLevelSecurityGuardTests}) catches each way of
 * skipping the isolation rule. The broken tables live in a throwaway schema inside a transaction that is rolled back,
 * so they never reach the migrated schema.
 */
@IntegrationTest
class TenantIsolationInspectorTests {

    private static final String SCHEMA = "guard_probe";
    private static final String POLICY_EXPRESSION = "%s = NULLIF(current_setting('app.tenant_id', true), '')::uuid";
    private static final String TENANT = POLICY_EXPRESSION.formatted("tenant_id");
    private static final String EXPECTED = expectedExpression("tenant_id");

    private Connection connection;

    @BeforeEach
    void createSchema() throws SQLException {
        connection = connectAsOwner();
        connection.setAutoCommit(false);
        execute("CREATE SCHEMA " + SCHEMA);
    }

    @AfterEach
    void dropSchema() throws SQLException {
        connection.rollback();
        connection.close();
    }

    @Test
    void acceptsAnIsolatedTable() throws SQLException {
        isolatedTable("isolated");

        assertThat(inspector().problems()).isEmpty();
    }

    @Test
    void acceptsTheExceptionsAndSkipsTheExcludedTables() throws SQLException {
        execute("CREATE TABLE guard_probe.tenant_itself (id uuid PRIMARY KEY)");
        enableAndForce("tenant_itself");
        execute("CREATE POLICY tenant_isolation ON guard_probe.tenant_itself USING (%s) WITH CHECK (%s)"
                .formatted(POLICY_EXPRESSION.formatted("id"), POLICY_EXPRESSION.formatted("id")));
        execute("CREATE TABLE guard_probe.history (id int)");

        TenantIsolationInspector inspector =
                new TenantIsolationInspector(connection, SCHEMA, Map.of("tenant_itself", "id"), Set.of("history"));

        assertThat(inspector.tables()).containsExactly("tenant_itself");
        assertThat(inspector.problems()).isEmpty();
    }

    @Test
    void rejectsRowLevelSecurityThatIsNotEnabledOrNotForced() throws SQLException {
        isolatedTable("not_enabled");
        execute("ALTER TABLE guard_probe.not_enabled NO FORCE ROW LEVEL SECURITY");
        execute("ALTER TABLE guard_probe.not_enabled DISABLE ROW LEVEL SECURITY");
        isolatedTable("not_forced");
        execute("ALTER TABLE guard_probe.not_forced NO FORCE ROW LEVEL SECURITY");

        assertThat(inspector().problems())
                .containsExactlyInAnyOrder(
                        "not_enabled: Row Level Security is not enabled",
                        "not_enabled: Row Level Security is not forced",
                        "not_forced: Row Level Security is not forced");
    }

    @Test
    void rejectsPoliciesThatDoNotUseTheActiveClinic() throws SQLException {
        tableWithPolicy("using_true", "USING (true) WITH CHECK (true)");
        tableWithPolicy("or_true", "USING (%s OR true) WITH CHECK (%s)".formatted(TENANT, TENANT));

        assertThat(inspector().problems())
                .containsExactlyInAnyOrder(
                        "using_true: tenant_isolation USING is true, expected " + EXPECTED,
                        "using_true: tenant_isolation WITH CHECK is true, expected " + EXPECTED,
                        "or_true: tenant_isolation USING is ((tenant_id = (NULLIF(current_setting('app.tenant_id'::text,"
                                + " true), ''::text))::uuid) OR true), expected " + EXPECTED);
    }

    @Test
    void rejectsPoliciesWithTheWrongShape() throws SQLException {
        tableWithPolicy("select_only", "FOR SELECT USING (%s)".formatted(TENANT));
        tableWithPolicy("app_only", "TO dentasoft_app USING (%s) WITH CHECK (%s)".formatted(TENANT, TENANT));
        tableWithPolicy("restrictive", "AS RESTRICTIVE USING (%s) WITH CHECK (%s)".formatted(TENANT, TENANT));

        String shape = ": tenant_isolation must be a permissive policy FOR ALL TO PUBLIC";
        assertThat(inspector().problems())
                .containsExactlyInAnyOrder(
                        "select_only" + shape,
                        "select_only: tenant_isolation WITH CHECK is null, expected " + EXPECTED,
                        "app_only" + shape,
                        "restrictive" + shape);
    }

    @Test
    void rejectsMissingRenamedOrExtraPolicies() throws SQLException {
        isolatedTable("extra_policy");
        execute("CREATE POLICY open ON guard_probe.extra_policy FOR SELECT USING (true)");
        isolatedTable("renamed");
        execute("ALTER POLICY tenant_isolation ON guard_probe.renamed RENAME TO isolation");
        isolatedTable("no_policy");
        execute("DROP POLICY tenant_isolation ON guard_probe.no_policy");

        assertThat(inspector().problems())
                .containsExactlyInAnyOrder(
                        "extra_policy: unexpected policy open",
                        "renamed: unexpected policy isolation",
                        "renamed: no tenant_isolation policy",
                        "no_policy: no tenant_isolation policy");
    }

    @Test
    void acceptsAnAllowedPolicyOnlyAsDeclared() throws SQLException {
        isolatedTable("allowed");
        execute("CREATE POLICY platform_select ON guard_probe.allowed FOR SELECT TO dentasoft_platform USING (true)");
        isolatedTable("other_role");
        execute("CREATE POLICY platform_select ON guard_probe.other_role FOR SELECT TO dentasoft_app USING (true)");
        isolatedTable("other_command");
        execute("CREATE POLICY platform_select ON guard_probe.other_command TO dentasoft_platform USING (true)");
        isolatedTable("two_roles");
        execute("CREATE POLICY platform_select ON guard_probe.two_roles FOR SELECT"
                + " TO dentasoft_platform, dentasoft_app USING (true)");
        isolatedTable("restrictive_allowed");
        execute("CREATE POLICY platform_select ON guard_probe.restrictive_allowed AS RESTRICTIVE FOR SELECT"
                + " TO dentasoft_platform USING (true)");
        Set<AllowedPolicy> allowed =
                Set.of("allowed", "other_role", "other_command", "two_roles", "restrictive_allowed").stream()
                        .map(table -> new AllowedPolicy(
                                table, "platform_select", "dentasoft_platform", "SELECT", "true", null))
                        .collect(Collectors.toSet());

        TenantIsolationInspector inspector =
                new TenantIsolationInspector(connection, SCHEMA, Map.of(), Set.of(), allowed);

        assertThat(inspector.problems())
                .containsExactlyInAnyOrder(
                        "other_role: unexpected policy platform_select",
                        "other_command: unexpected policy platform_select",
                        "two_roles: unexpected policy platform_select",
                        "restrictive_allowed: unexpected policy platform_select");
    }

    @Test
    void neverAllowsAPolicyForEveryRoleOrTheApplicationRole() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AllowedPolicy("clinic", "open", "public", "SELECT", "true", null));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new AllowedPolicy("clinic", "open", "dentasoft_app", "SELECT", "true", null));
    }

    @Test
    void acceptsADeclaredReadPolicyOfTheApplicationOnlyForSelect() throws SQLException {
        String own = "(user_id = (NULLIF(current_setting('app.user_id'::text, true), ''::text))::uuid)";
        for (String table : List.of("read", "write", "all_commands", "other_expression", "undeclared")) {
            isolatedTable(table);
            execute("ALTER TABLE guard_probe.%s ADD COLUMN user_id uuid".formatted(table));
        }
        String user = "user_id = NULLIF(current_setting('app.user_id', true), '')::uuid";
        execute("CREATE POLICY own ON guard_probe.read FOR SELECT TO dentasoft_app USING (%s)".formatted(user));
        execute("CREATE POLICY own ON guard_probe.write FOR UPDATE TO dentasoft_app USING (%s)".formatted(user));
        execute("CREATE POLICY own ON guard_probe.all_commands TO dentasoft_app USING (%s)".formatted(user));
        execute("CREATE POLICY own ON guard_probe.other_expression FOR SELECT TO dentasoft_app USING (true)");
        execute("CREATE POLICY own ON guard_probe.undeclared FOR SELECT TO dentasoft_app USING (%s)".formatted(user));
        Set<ApplicationReadPolicy> declared = Set.of("read", "write", "all_commands", "other_expression").stream()
                .map(table -> new ApplicationReadPolicy(table, "own", own))
                .collect(Collectors.toSet());

        TenantIsolationInspector inspector =
                new TenantIsolationInspector(connection, SCHEMA, Map.of(), Set.of(), Set.of(), declared, Map.of());

        assertThat(inspector.problems())
                .containsExactlyInAnyOrder(
                        "write: unexpected policy own",
                        "all_commands: unexpected policy own",
                        "other_expression: unexpected policy own",
                        "undeclared: unexpected policy own");
    }

    @Test
    void checksTablesIsolatedByUserAgainstTheActiveUser() throws SQLException {
        String user = "id = NULLIF(current_setting('app.user_id', true), '')::uuid";
        String clinic = POLICY_EXPRESSION.formatted("id");
        execute("CREATE TABLE guard_probe.by_user (id uuid PRIMARY KEY)");
        enableAndForce("by_user");
        execute("CREATE POLICY user_isolation ON guard_probe.by_user USING (%s) WITH CHECK (%s)".formatted(user, user));
        execute("CREATE TABLE guard_probe.by_clinic_setting (id uuid PRIMARY KEY)");
        enableAndForce("by_clinic_setting");
        execute("CREATE POLICY user_isolation ON guard_probe.by_clinic_setting USING (%s) WITH CHECK (%s)"
                .formatted(clinic, clinic));
        execute("CREATE TABLE guard_probe.tenant_policy (id uuid PRIMARY KEY)");
        enableAndForce("tenant_policy");
        execute("CREATE POLICY tenant_isolation ON guard_probe.tenant_policy USING (%s) WITH CHECK (%s)"
                .formatted(clinic, clinic));
        String expected = expectedUserExpression("id");

        TenantIsolationInspector inspector = new TenantIsolationInspector(
                connection,
                SCHEMA,
                Map.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Map.of("by_user", "id", "by_clinic_setting", "id", "tenant_policy", "id"));

        assertThat(inspector.problems())
                .containsExactlyInAnyOrder(
                        "by_clinic_setting: user_isolation USING is " + expectedExpression("id") + ", expected "
                                + expected,
                        "by_clinic_setting: user_isolation WITH CHECK is " + expectedExpression("id") + ", expected "
                                + expected,
                        "tenant_policy: unexpected policy tenant_isolation",
                        "tenant_policy: no user_isolation policy");
    }

    @Test
    void rejectsATenantColumnThatIsMissingNullableOrNotIndexedFirst() throws SQLException {
        execute("CREATE TABLE guard_probe.no_tenant (id uuid PRIMARY KEY)");
        enableAndForce("no_tenant");
        isolatedTable("nullable_tenant");
        execute("ALTER TABLE guard_probe.nullable_tenant ALTER COLUMN tenant_id DROP NOT NULL");
        isolatedTable("no_index");
        execute("DROP INDEX guard_probe.no_index_tenant_id_idx");
        isolatedTable("index_second");
        execute("DROP INDEX guard_probe.index_second_tenant_id_idx");
        execute("CREATE INDEX ON guard_probe.index_second (name, tenant_id)");

        assertThat(inspector().problems())
                .containsExactlyInAnyOrder(
                        "no_tenant: no tenant_isolation policy",
                        "no_tenant: missing column tenant_id",
                        "no_tenant: no index whose first column is tenant_id",
                        "nullable_tenant: tenant_id must be uuid NOT NULL",
                        "no_index: no index whose first column is tenant_id",
                        "index_second: no index whose first column is tenant_id");
    }

    @Test
    void rejectsMaterializedViews() throws SQLException {
        isolatedTable("isolated");
        execute("CREATE MATERIALIZED VIEW guard_probe.isolated_summary AS SELECT tenant_id FROM guard_probe.isolated");

        assertThat(inspector().problems())
                .containsExactly("isolated_summary: a materialized view has no Row Level Security");
    }

    private TenantIsolationInspector inspector() {
        return new TenantIsolationInspector(connection, SCHEMA, Map.of(), Set.of());
    }

    /** A table built as the module plan says, with the policy {@code enable_tenant_isolation} would create. */
    private void isolatedTable(String table) throws SQLException {
        tableWithPolicy(table, "FOR ALL USING (%s) WITH CHECK (%s)".formatted(TENANT, TENANT));
    }

    private void tableWithPolicy(String table, String policy) throws SQLException {
        execute("CREATE TABLE guard_probe.%s (id uuid PRIMARY KEY, tenant_id uuid NOT NULL, name text)"
                .formatted(table));
        execute("CREATE INDEX %s_tenant_id_idx ON guard_probe.%s (tenant_id)".formatted(table, table));
        enableAndForce(table);
        execute("CREATE POLICY tenant_isolation ON guard_probe.%s %s".formatted(table, policy));
    }

    private void enableAndForce(String table) throws SQLException {
        execute("ALTER TABLE guard_probe.%s ENABLE ROW LEVEL SECURITY".formatted(table));
        execute("ALTER TABLE guard_probe.%s FORCE ROW LEVEL SECURITY".formatted(table));
    }

    /** DDL with fixed identifiers from this class: nothing here comes from outside the test. */
    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
