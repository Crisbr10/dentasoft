package es.crisbr.dentasoft.tenancy;

import es.crisbr.dentasoft.TestPostgresConfiguration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Reads the catalog of one schema and lists every table that is not isolated by clinic as AGENTS.md requires: Row
 * Level Security enabled and forced, exactly the {@code tenant_isolation} policy with the fail-closed expression of
 * {@code enable_tenant_isolation}, and a tenant column that is {@code uuid NOT NULL} and leads an index.
 *
 * <p>Used by {@link RowLevelSecurityGuardTests} on the migrated schema and by {@link TenantIsolationInspectorTests},
 * which proves on deliberately broken tables that each check fails.
 */
final class TenantIsolationInspector {

    static final String TENANT_COLUMN = "tenant_id";

    private final Connection connection;
    private final String schema;
    private final Map<String, String> tenantColumnExceptions;
    private final Set<String> excludedTables;
    private final Set<AllowedPolicy> allowedPolicies;

    /**
     * A policy allowed next to {@code tenant_isolation}, compared in full. It must name a single role that is neither
     * {@code PUBLIC} nor the application role: PostgreSQL ORs permissive policies, so a policy that reached the
     * application role would widen what it sees.
     *
     * @param using the {@code USING} expression as PostgreSQL prints it, {@code null} for an INSERT policy
     * @param withCheck the {@code WITH CHECK} expression as PostgreSQL prints it, {@code null} for a SELECT policy
     */
    record AllowedPolicy(
            String table,
            String name,
            String role,
            String command,
            @Nullable String using,
            @Nullable String withCheck) {

        AllowedPolicy {
            if (role.equals("public") || role.equals(TestPostgresConfiguration.APP_USER)) {
                throw new IllegalArgumentException(table + "." + name + ": cannot be allowed for role " + role);
            }
        }
    }

    /**
     * @param tenantColumnExceptions tables whose policy compares a column other than {@code tenant_id}
     * @param excludedTables tables that are not checked at all
     */
    TenantIsolationInspector(
            Connection connection,
            String schema,
            Map<String, String> tenantColumnExceptions,
            Set<String> excludedTables) {
        this(connection, schema, tenantColumnExceptions, excludedTables, Set.of());
    }

    /** @param allowedPolicies policies allowed next to {@code tenant_isolation}, each exactly as declared */
    TenantIsolationInspector(
            Connection connection,
            String schema,
            Map<String, String> tenantColumnExceptions,
            Set<String> excludedTables,
            Set<AllowedPolicy> allowedPolicies) {
        this.connection = connection;
        this.schema = schema;
        this.tenantColumnExceptions = tenantColumnExceptions;
        this.excludedTables = excludedTables;
        this.allowedPolicies = allowedPolicies;
    }

    /** The policy expression {@code enable_tenant_isolation} creates for {@code column}, as PostgreSQL prints it. */
    static String expectedExpression(String column) {
        return "(%s = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)".formatted(column);
    }

    /** Ordinary and partitioned tables of the schema, without the excluded ones. */
    List<String> tables() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                        + " WHERE n.nspname = ? AND c.relkind IN ('r', 'p') ORDER BY c.relname")) {
            statement.setString(1, schema);
            try (ResultSet result = statement.executeQuery()) {
                List<String> tables = new ArrayList<>();
                while (result.next()) {
                    if (!excludedTables.contains(result.getString(1))) {
                        tables.add(result.getString(1));
                    }
                }
                return tables;
            }
        }
    }

    /** One line per problem, prefixed by the table name. Empty when every table is isolated. */
    List<String> problems() throws SQLException {
        List<String> problems = new ArrayList<>(unprotectedRelations());
        for (String table : tables()) {
            String column = tenantColumnExceptions.getOrDefault(table, TENANT_COLUMN);
            checkRowLevelSecurity(table, problems);
            checkPolicy(table, column, problems);
            checkTenantColumn(table, column, problems);
            checkLeadingIndex(table, column, problems);
        }
        return problems;
    }

    /**
     * Materialized views and foreign tables: Row Level Security does not apply to them, and the default privileges
     * of V001 still let the application read them, so any of them would show every clinic's rows.
     */
    private List<String> unprotectedRelations() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT c.relname, c.relkind FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                        + " WHERE n.nspname = ? AND c.relkind IN ('m', 'f') ORDER BY c.relname")) {
            statement.setString(1, schema);
            try (ResultSet result = statement.executeQuery()) {
                List<String> problems = new ArrayList<>();
                while (result.next()) {
                    String kind = "m".equals(result.getString("relkind")) ? "materialized view" : "foreign table";
                    problems.add(result.getString("relname") + ": a " + kind + " has no Row Level Security");
                }
                return problems;
            }
        }
    }

    private void checkRowLevelSecurity(String table, List<String> problems) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT c.relrowsecurity, c.relforcerowsecurity FROM pg_class c"
                        + " JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = ? AND c.relname = ?")) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    problems.add(table + ": not found in the catalog");
                    return;
                }
                if (!result.getBoolean("relrowsecurity")) {
                    problems.add(table + ": Row Level Security is not enabled");
                }
                if (!result.getBoolean("relforcerowsecurity")) {
                    problems.add(table + ": Row Level Security is not forced");
                }
            }
        }
    }

    /**
     * The policy {@code enable_tenant_isolation} creates, and no other except the allowed ones. Comparing the whole
     * expression (and not only the name) rejects {@code USING (true)} or an added {@code OR}; rejecting any other
     * policy rejects a second permissive policy, which PostgreSQL would OR with this one. An allowed policy must match
     * its declaration in full, role included, so it can never reach the application role.
     */
    private void checkPolicy(String table, String column, List<String> problems) throws SQLException {
        String expected = expectedExpression(column);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT policyname, permissive, roles::text[] AS roles, cmd, qual, with_check FROM pg_policies"
                        + " WHERE schemaname = ? AND tablename = ? ORDER BY policyname")) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet result = statement.executeQuery()) {
                boolean found = false;
                while (result.next()) {
                    String name = result.getString("policyname");
                    List<Object> roles =
                            List.of((Object[]) result.getArray("roles").getArray());
                    if (!"tenant_isolation".equals(name)) {
                        if (!isAllowed(table, name, roles, result)) {
                            problems.add(table + ": unexpected policy " + name);
                        }
                        continue;
                    }
                    found = true;
                    if (!"PERMISSIVE".equals(result.getString("permissive"))
                            || !"ALL".equals(result.getString("cmd"))
                            || !roles.equals(List.of("public"))) {
                        problems.add(table + ": tenant_isolation must be a permissive policy FOR ALL TO PUBLIC");
                    }
                    if (!expected.equals(result.getString("qual"))) {
                        problems.add(table + ": tenant_isolation USING is " + result.getString("qual") + ", expected "
                                + expected);
                    }
                    if (!expected.equals(result.getString("with_check"))) {
                        problems.add(table + ": tenant_isolation WITH CHECK is " + result.getString("with_check")
                                + ", expected " + expected);
                    }
                }
                if (!found) {
                    problems.add(table + ": no tenant_isolation policy");
                }
            }
        }
    }

    private boolean isAllowed(String table, String name, List<Object> roles, ResultSet policy) throws SQLException {
        if (!"PERMISSIVE".equals(policy.getString("permissive")) || roles.size() != 1) {
            return false;
        }
        String role = (String) roles.getFirst();
        if (role.equals("public") || role.equals(TestPostgresConfiguration.APP_USER)) {
            return false;
        }
        AllowedPolicy found = new AllowedPolicy(
                table, name, role, policy.getString("cmd"), policy.getString("qual"), policy.getString("with_check"));
        return allowedPolicies.contains(found);
    }

    private void checkTenantColumn(String table, String column, List<String> problems) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT format_type(a.atttypid, a.atttypmod) AS type, a.attnotnull FROM pg_attribute a"
                        + " JOIN pg_class c ON c.oid = a.attrelid JOIN pg_namespace n ON n.oid = c.relnamespace"
                        + " WHERE n.nspname = ? AND c.relname = ? AND a.attname = ? AND NOT a.attisdropped")) {
            statement.setString(1, schema);
            statement.setString(2, table);
            statement.setString(3, column);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    problems.add(table + ": missing column " + column);
                    return;
                }
                if (!"uuid".equals(result.getString("type")) || !result.getBoolean("attnotnull")) {
                    problems.add(table + ": " + column + " must be uuid NOT NULL");
                }
            }
        }
    }

    private void checkLeadingIndex(String table, String column, List<String> problems) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indrelid"
                        + " JOIN pg_namespace n ON n.oid = c.relnamespace"
                        + " JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = i.indkey[0]"
                        + " WHERE n.nspname = ? AND c.relname = ? AND a.attname = ?")) {
            statement.setString(1, schema);
            statement.setString(2, table);
            statement.setString(3, column);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getLong(1) == 0) {
                    problems.add(table + ": no index whose first column is " + column);
                }
            }
        }
    }
}
