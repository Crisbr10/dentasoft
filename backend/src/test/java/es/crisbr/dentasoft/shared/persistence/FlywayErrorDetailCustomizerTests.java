package es.crisbr.dentasoft.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.mock;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Properties;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * The data source Spring Boot derives for Flyway is covered end to end by {@code DatabaseErrorDetailTests}; these
 * tests cover the data sources the customizer accepts or refuses without changing them.
 */
class FlywayErrorDetailCustomizerTests {

    private final FlywayErrorDetailCustomizer customizer = new FlywayErrorDetailCustomizer();

    @Test
    void anUnknownDataSourceStopsTheApplication() {
        FluentConfiguration configuration = Flyway.configure().dataSource(mock(DataSource.class));

        assertThatIllegalStateException()
                .isThrownBy(() -> customizer.customize(configuration))
                .withMessageContaining("Flyway's data source")
                .withMessageContaining("logServerErrorDetail=false");
    }

    @Test
    void aHikariPoolWithoutThePropertyStopsTheApplication() {
        try (HikariDataSource pool = new HikariDataSource()) {
            FluentConfiguration configuration = Flyway.configure().dataSource(pool);

            assertThatIllegalStateException()
                    .isThrownBy(() -> customizer.customize(configuration))
                    .withMessageContaining(HikariDataSource.class.getName());
        }
    }

    @Test
    void aHikariPoolWithThePropertyIsAccepted() {
        try (HikariDataSource pool = new HikariDataSource()) {
            pool.addDataSourceProperty("logServerErrorDetail", "false");
            FluentConfiguration configuration = Flyway.configure().dataSource(pool);

            assertThatNoException().isThrownBy(() -> customizer.customize(configuration));
        }
    }

    @Test
    void aDriverDataSourceKeepsItsOtherConnectionProperties() {
        SimpleDriverDataSource dataSource = new SimpleDriverDataSource();
        Properties properties = new Properties();
        properties.setProperty("ApplicationName", "dentasoft-migrations");
        dataSource.setConnectionProperties(properties);

        customizer.customize(Flyway.configure().dataSource(dataSource));

        assertThat(dataSource.getConnectionProperties())
                .containsEntry("ApplicationName", "dentasoft-migrations")
                .containsEntry("logServerErrorDetail", "false");
    }
}
