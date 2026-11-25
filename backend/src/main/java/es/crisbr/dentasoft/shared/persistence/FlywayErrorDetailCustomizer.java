package es.crisbr.dentasoft.shared.persistence;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Properties;
import javax.sql.DataSource;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.jdbc.datasource.AbstractDriverBasedDataSource;
import org.springframework.stereotype.Component;

/**
 * Flyway's connections leave the detail of PostgreSQL's errors out of exception messages, as the application's do
 * (#123, #125): the detail repeats the values of the row, and a migration that fails on existing rows would log them
 * at startup.
 *
 * <p>Flyway migrates as the owner, so Spring Boot derives a data source of its own from the main one, with the URL and
 * the credentials but without the pool's {@code data-source-properties}. Boot sets it before calling the customizers,
 * so it is added here. The driver reads the property when it opens a connection, so it must reach the data source
 * itself, not a wrapper. If Flyway uses the main pool instead, the pool must already carry it. Any other data source
 * stops the application: this class cannot tell whether it leaves the detail out.
 */
@Component
class FlywayErrorDetailCustomizer implements FlywayConfigurationCustomizer {

    static final String LOG_SERVER_ERROR_DETAIL = "logServerErrorDetail";

    @Override
    public void customize(FluentConfiguration configuration) {
        DataSource dataSource = configuration.getDataSource();
        if (dataSource instanceof AbstractDriverBasedDataSource driverBased) {
            Properties properties = new Properties();
            if (driverBased.getConnectionProperties() != null) {
                properties.putAll(driverBased.getConnectionProperties());
            }
            properties.setProperty(LOG_SERVER_ERROR_DETAIL, "false");
            driverBased.setConnectionProperties(properties);
        } else if (!(dataSource instanceof HikariDataSource hikari && leavesTheDetailOut(hikari))) {
            throw new IllegalStateException("Flyway's data source ("
                    + (dataSource == null ? "none" : dataSource.getClass().getName())
                    + ") may log the values of rows: use the one Spring Boot derives (spring.flyway.user) or a Hikari"
                    + " pool with the data source property " + LOG_SERVER_ERROR_DETAIL + "=false (#125)");
        }
    }

    private static boolean leavesTheDetailOut(HikariDataSource hikari) {
        Object value = hikari.getDataSourceProperties().get(LOG_SERVER_ERROR_DETAIL);
        return value != null && "false".equalsIgnoreCase(value.toString().strip());
    }
}
