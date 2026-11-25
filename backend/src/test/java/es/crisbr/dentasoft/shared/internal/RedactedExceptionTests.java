package es.crisbr.dentasoft.shared.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class RedactedExceptionTests {

    @Test
    void keepsClassesAndFramesButNoMessages() {
        IllegalArgumentException cause = new IllegalArgumentException("phone 600111222");
        IllegalStateException original = new IllegalStateException("patient Ana Inventada", cause);

        RedactedException redacted = RedactedException.of(original);

        String printed = stackTraceOf(redacted);
        assertThat(printed)
                .contains("java.lang.IllegalStateException")
                .contains("java.lang.IllegalArgumentException")
                .contains("RedactedExceptionTests.keepsClassesAndFramesButNoMessages")
                .doesNotContain("Ana Inventada")
                .doesNotContain("600111222");
        assertThat(redacted.getStackTrace()).isEqualTo(original.getStackTrace());
        assertThat(redacted.getCause()).isNotNull();
        assertThat(redacted.getCause().getStackTrace()).isEqualTo(cause.getStackTrace());
    }

    @Test
    void keepsTheSqlStateOfAnSqlException() {
        SQLException sql = new SQLException("Key (phone)=(600111222) already exists.", "23505");
        IllegalStateException original = new IllegalStateException("could not save 600111222", sql);

        String printed = stackTraceOf(RedactedException.of(original));

        assertThat(printed).contains("java.sql.SQLException (SQLState 23505)").doesNotContain("600111222");
    }

    @Test
    void leavesOutAnSqlStateThatIsNotOne() {
        SQLException sql = new SQLException("x", "600111222");

        assertThat(RedactedException.of(sql).getMessage()).isEqualTo("java.sql.SQLException");
    }

    @Test
    void dropsSuppressedExceptions() {
        IllegalStateException original = new IllegalStateException("x");
        original.addSuppressed(new IllegalArgumentException("secret 600111222"));

        assertThat(stackTraceOf(RedactedException.of(original))).doesNotContain("600111222");
    }

    @Test
    void survivesACyclicCauseChain() {
        IllegalStateException first = new IllegalStateException("a");
        IllegalArgumentException second = new IllegalArgumentException("b", first);
        first.initCause(second);

        RedactedException redacted = RedactedException.of(first);

        assertThat(redacted.getCause()).isNotNull();
        assertThat(redacted.getCause().getCause()).isNull();
    }

    private static String stackTraceOf(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
