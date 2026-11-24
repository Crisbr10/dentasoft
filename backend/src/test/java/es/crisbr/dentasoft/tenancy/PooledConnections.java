package es.crisbr.dentasoft.tenancy;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/** Test-only: what every connection of the application's pool carries while it waits in the pool. */
final class PooledConnections {

    private PooledConnections() {}

    /**
     * One pooled connection as seen outside any transaction: its {@code app.tenant_id} and how many rows of
     * {@code tenancy_test_record} it can read.
     */
    record IdleConnection(int backendPid, String databaseClinic, long visibleRows) {

        /** No clinic left behind: the setting is missing ({@code null}) or reset by PostgreSQL ({@code ""}). */
        boolean hasNoClinic() {
            return databaseClinic == null || databaseClinic.isEmpty();
        }
    }

    /** Takes every connection the pool can hold at once, so none is left unchecked, and returns them afterwards. */
    static List<IdleConnection> inspectAll(DataSource dataSource) throws SQLException {
        int size = dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
        List<Connection> connections = new ArrayList<>();
        try {
            for (int i = 0; i < size; i++) {
                connections.add(dataSource.getConnection());
            }
            List<IdleConnection> idle = new ArrayList<>();
            for (Connection connection : connections) {
                idle.add(new IdleConnection(
                        (int) single(connection, "SELECT pg_backend_pid()"),
                        clinic(connection),
                        single(connection, "SELECT count(*) FROM tenancy_test_record")));
            }
            return idle;
        } finally {
            for (Connection connection : connections) {
                connection.close();
            }
        }
    }

    private static String clinic(Connection connection) throws SQLException {
        try (PreparedStatement statement =
                        connection.prepareStatement("SELECT current_setting('app.tenant_id', true)");
                ResultSet row = statement.executeQuery()) {
            row.next();
            return row.getString(1);
        }
    }

    private static long single(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet row = statement.executeQuery()) {
            row.next();
            return row.getLong(1);
        }
    }
}
