package es.crisbr.dentasoft;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Base for integration tests: the whole application with PostgreSQL and Redis in Testcontainers and the properties of
 * {@link WithTestEnvironment}. Every test class with this annotation shares the same Spring context and containers.
 *
 * <p>Data in these tests is always invented: see {@link TestData}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@SpringBootTest
@WithTestEnvironment
@Import(TestPostgresConfiguration.class)
public @interface IntegrationTest {}
