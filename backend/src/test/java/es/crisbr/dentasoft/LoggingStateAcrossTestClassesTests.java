package es.crisbr.dentasoft;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

/**
 * A test class that changes the logging system leaves it as it found it, so later classes get the levels of their
 * context whatever the order of the suite (#130). Logging state is global to the JVM and test contexts are cached, so
 * a class that reuses a context sees what the classes before it left behind.
 *
 * <p>The order the CI hit once: a class caches the integration context, {@code RequestLoggingTests} changes the
 * logging system, and {@code SqlErrorLoggingTests} reuses the cached context, whose
 * {@code org.hibernate.orm.jdbc.error} must still be at {@code error}. Each class runs on its own, one after another,
 * as Surefire runs them. By name: test classes are private to their packages.
 */
class LoggingStateAcrossTestClassesTests {

    @Test
    void aClassThatChangesTheLoggingSystemLeavesTheLevelsOfLaterClasses() {
        Launcher launcher = LauncherFactory.create();

        for (String testClass : List.of(
                "es.crisbr.dentasoft.tenancy.TenancyMigrationTests",
                "es.crisbr.dentasoft.shared.web.RequestLoggingTests",
                "es.crisbr.dentasoft.shared.SqlErrorLoggingTests")) {
            SummaryGeneratingListener listener = new SummaryGeneratingListener();
            launcher.execute(request().selectors(selectClass(testClass)).build(), listener);
            TestExecutionSummary summary = listener.getSummary();

            assertThat(summary.getTestsSucceededCount()).as("%s ran", testClass).isPositive();
            assertThat(summary.getFailures())
                    .as("failures of %s", testClass)
                    .extracting(failure -> failure.getTestIdentifier().getDisplayName() + ": " + failure.getException())
                    .isEmpty();
        }
    }
}
