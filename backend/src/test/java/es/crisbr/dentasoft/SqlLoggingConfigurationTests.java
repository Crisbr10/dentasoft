package es.crisbr.dentasoft;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.PropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.LogLevel;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

/**
 * No configuration file turns on the loggers that write SQL statements, their parameters or the rows they read (#123):
 * parameters and rows are personal and clinical data, which never go to the log ({@code AGENTS.md}).
 *
 * <ul>
 *   <li>{@code spring.jpa.show-sql}, {@code hibernate.show_sql} and Spring Boot's {@code debug} and {@code trace} modes
 *       stay off, in any spelling Spring Boot accepts.
 *   <li>The loggers of {@link #SENSITIVE} are not set below {@code WARN}, by name, by a logging group (Spring Boot's
 *       {@code sql} group holds two of them) or by a logger below them.
 *   <li>Their ancestors, {@code root} included, are not set to {@code DEBUG} or {@code TRACE}: those levels pass on to
 *       them. {@code INFO}, Spring Boot's default, writes nothing of theirs.
 *   <li>{@link #SQL_ERRORS}, Hibernate's log of failed statements, stays at {@code ERROR} or above: it writes the
 *       server's error message, which may carry values, at {@code WARN}.
 *   <li>Every document of a file is checked on its own, so a later profile cannot hide an earlier one, and logging
 *       groups count whichever file declares them.
 *   <li>There is no Logback or Log4j2 file of our own, nor {@code logging.config}: this test does not read them.
 * </ul>
 *
 * <p>Only the files are checked: an environment variable such as {@code LOGGING_LEVEL_ORG_HIBERNATE_SQL} can still turn
 * a logger on, as with any other property: {@code docs/operaciones/logs.md} rules that out in testing and production.
 */
class SqlLoggingConfigurationTests {

    static final String SQL_ERRORS = "org.hibernate.orm.jdbc.error";

    /**
     * Loggers that write SQL statements, their parameter values or the values they read; the entities Hibernate
     * flushes ({@code EntityPrinter}, at {@code DEBUG} on its event and core loggers); and the request and response
     * bodies Spring MVC reads and writes.
     */
    static final Set<String> SENSITIVE = Set.of(
            "org.hibernate.SQL",
            "org.hibernate.orm.jdbc.bind",
            "org.hibernate.orm.jdbc.extract",
            "org.hibernate.type.descriptor.sql",
            "org.hibernate.orm.event",
            "org.hibernate.orm.core",
            "org.springframework.jdbc.core",
            "org.springframework.web",
            "org.postgresql",
            SQL_ERRORS);

    /** Spring Boot's predefined groups that hold any of {@link #SENSITIVE}. */
    private static final Map<String, List<String>> PREDEFINED_GROUPS = Map.of(
            "sql",
            List.of("org.springframework.jdbc.core", "org.hibernate.SQL", "org.jooq.tools.LoggerListener"),
            "web",
            List.of(
                    "org.springframework.core.codec",
                    "org.springframework.http",
                    "org.springframework.web",
                    "org.springframework.boot.actuate.endpoint.web",
                    "org.springframework.boot.web.servlet.ServletContextInitializerBeans"));

    /** Switches that log SQL, as {@link #canonical} spells their keys. */
    private static final Set<String> SQL_SWITCHES =
            Set.of("spring.jpa.showsql", "spring.jpa.properties.hibernate.showsql", "debug", "trace");

    private static final Pattern CONFIGURATION_FILE = Pattern.compile("application.*\\.(ya?ml|properties)");

    private static final Pattern LOGGING_SYSTEM_FILE =
            Pattern.compile("(logback|log4j2).*\\.(xml|ya?ml|json|properties)");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{[^:}]+(?::([^}]*))?}");

    private static final Pattern INDEX = Pattern.compile("\\[\\d+]$");

    @Test
    void noConfigurationFileLogsSqlOrItsValues() {
        assertThat(violations(Path.of("src/main/resources"))).isEmpty();
    }

    @Test
    void hibernateDoesNotLogTheMessageOfFailedStatements() {
        assertThat(levels(Path.of("src/main/resources/application.yml")))
                .as("application.yml must set %s to ERROR or above", SQL_ERRORS)
                .hasEntrySatisfying(SQL_ERRORS, level -> assertThat(level).isGreaterThanOrEqualTo(LogLevel.ERROR));
    }

    @Test
    void detectsSqlLogging(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("application.yml"), """
                spring:
                  jpa:
                    show-sql: true
                    properties:
                      hibernate:
                        show_sql: true
                debug: true
                logging:
                  level:
                    org.hibernate.SQL: debug
                    "[org.hibernate.orm.jdbc.bind]": trace
                    org.hibernate.orm.jdbc.error: warn
                    org.postgresql.core: info
                """);
        Files.writeString(root.resolve("application-local.properties"), """
                trace=true
                logging.level.sql=debug
                logging.group.queries=org.hibernate.orm.jdbc.extract,es.crisbr.dentasoft
                logging.level.queries=info
                logging.level.org.hibernate=debug
                logging.level.root=trace
                logging.level.org.springframework.jdbc.core=${JDBC_LEVEL}
                """);

        assertThat(violations(root))
                .containsExactlyInAnyOrder(
                        "application.yml: spring.jpa.show-sql=true",
                        "application.yml: spring.jpa.properties.hibernate.show_sql=true",
                        "application.yml: debug=true",
                        "application.yml: logging.level.org.hibernate.SQL=debug",
                        "application.yml: logging.level[org.hibernate.orm.jdbc.bind]=trace",
                        "application.yml: logging.level.org.hibernate.orm.jdbc.error=warn",
                        "application.yml: logging.level.org.postgresql.core=info",
                        "application-local.properties: trace=true",
                        "application-local.properties: logging.level.sql=debug",
                        "application-local.properties: logging.level.queries=info",
                        "application-local.properties: logging.level.org.hibernate=debug",
                        "application-local.properties: logging.level.root=trace",
                        "application-local.properties: logging.level.org.springframework.jdbc.core=${JDBC_LEVEL}");
    }

    @Test
    void detectsWhatOtherSpellingsFilesAndDocumentsTurnOn(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("application.yml"), """
                spring:
                  jpa:
                    showSql: true
                logging:
                  group:
                    db: org.hibernate.SQL
                  level:
                    org.hibernate.orm.event: debug
                ---
                spring.config.activate.on-profile: local
                logging.level.org.hibernate.orm.jdbc.bind: trace
                ---
                spring.config.activate.on-profile: prod
                logging.level.org.hibernate.orm.jdbc.bind: warn
                """);
        Files.writeString(root.resolve("application-local.properties"), """
                spring.jpa.properties[hibernate.show_sql]=true
                logging.level.db=debug
                logging.level.web=debug
                logging.config=classpath:custom-logging.xml
                """);
        Files.writeString(root.resolve("logback-spring.xml"), "<configuration/>\n");

        assertThat(violations(root))
                .containsExactlyInAnyOrder(
                        "application.yml: spring.jpa.showSql=true",
                        "application.yml: logging.level.org.hibernate.orm.event=debug",
                        "application.yml: logging.level.org.hibernate.orm.jdbc.bind=trace",
                        "application-local.properties: spring.jpa.properties[hibernate.show_sql]=true",
                        "application-local.properties: logging.level.db=debug",
                        "application-local.properties: logging.level.web=debug",
                        "application-local.properties: logging.config=classpath:custom-logging.xml",
                        "logback-spring.xml: a logging system file, which this test does not read");
    }

    @Test
    void allowsQuietLevelsAndUnrelatedLoggers(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("application.yml"), """
                spring:
                  jpa:
                    show-sql: false
                debug: false
                logging:
                  level:
                    root: info
                    org.hibernate: info
                    org.hibernate.SQL: warn
                    org.hibernate.orm.jdbc.error: ${SQL_ERRORS_LEVEL:error}
                    org.postgresql: off
                    es.crisbr.dentasoft: debug
                    org.springframework.security: trace
                """);
        Files.writeString(root.resolve("notes.yml"), "logging.level.org.hibernate.SQL: debug\n");

        assertThat(violations(root)).isEmpty();
    }

    private static List<String> violations(Path root) {
        Map<Path, List<Map<String, String>>> files = new LinkedHashMap<>();
        for (Path file : files(root, CONFIGURATION_FILE)) {
            files.put(file, documents(file));
        }
        Map<String, List<String>> groups =
                groups(files.values().stream().flatMap(List::stream).toList());

        List<String> violations = new ArrayList<>();
        files.forEach((file, documents) -> {
            for (Map<String, String> document : documents) {
                document.forEach((key, value) -> {
                    if (isViolation(key, value, groups)) {
                        violations.add(file.getFileName() + ": " + key + "=" + value);
                    }
                });
            }
        });
        for (Path file : files(root, LOGGING_SYSTEM_FILE)) {
            violations.add(file.getFileName() + ": a logging system file, which this test does not read");
        }
        return violations;
    }

    private static boolean isViolation(String key, String value, Map<String, List<String>> groups) {
        String resolved = resolve(value);
        if (SQL_SWITCHES.contains(canonical(key))) {
            return resolved == null || !resolved.trim().equalsIgnoreCase("false");
        }
        if (canonical(key).equals("logging.config")) {
            return true;
        }
        String name = nameAfter(key, "logging.level");
        if (name == null) {
            return false;
        }
        List<String> loggers = groups.getOrDefault(name, List.of(name));
        LogLevel level = level(resolved);
        return loggers.stream().anyMatch(logger -> isTooVerbose(logger, level));
    }

    private static boolean isTooVerbose(String logger, LogLevel level) {
        if (level == null) {
            // Not a fixed level: an environment variable without default, or a typo.
            return SENSITIVE.stream()
                    .anyMatch(sensitive -> isAtOrBelow(logger, sensitive) || isAbove(logger, sensitive));
        }
        if (isAtOrBelow(logger, SQL_ERRORS) && level.compareTo(LogLevel.ERROR) < 0) {
            return true;
        }
        if (SENSITIVE.stream().anyMatch(sensitive -> isAtOrBelow(logger, sensitive))) {
            return level.compareTo(LogLevel.WARN) < 0;
        }
        return SENSITIVE.stream().anyMatch(sensitive -> isAbove(logger, sensitive))
                && level.compareTo(LogLevel.INFO) < 0;
    }

    /** Whether {@code logger} is {@code sensitive} or a logger below it, whose output it is part of. */
    private static boolean isAtOrBelow(String logger, String sensitive) {
        return logger.equals(sensitive) || logger.startsWith(sensitive + ".");
    }

    /** Whether {@code logger} is an ancestor of {@code sensitive}, which inherits its level unless set itself. */
    private static boolean isAbove(String logger, String sensitive) {
        return logger.equalsIgnoreCase("root") || sensitive.startsWith(logger + ".");
    }

    private static Map<String, LogLevel> levels(Path file) {
        Map<String, LogLevel> levels = new LinkedHashMap<>();
        documents(file).stream()
                .flatMap(document -> document.entrySet().stream())
                .forEach(entry -> {
                    String key = entry.getKey();
                    String value = entry.getValue();
                    String name = nameAfter(key, "logging.level");
                    if (name != null) {
                        levels.put(name, level(resolve(value)));
                    }
                });
        return levels;
    }

    private static Map<String, List<String>> groups(List<Map<String, String>> documents) {
        Map<String, List<String>> groups = new LinkedHashMap<>(PREDEFINED_GROUPS);
        documents.stream().flatMap(document -> document.entrySet().stream()).forEach(entry -> {
            String key = entry.getKey();
            String value = entry.getValue();
            String group = nameAfter(INDEX.matcher(key).replaceAll(""), "logging.group");
            if (group != null) {
                List<String> members = new ArrayList<>(groups.getOrDefault(group, List.of()));
                Arrays.stream(value.split(",")).map(String::trim).forEach(members::add);
                groups.put(group, members);
            }
        });
        return groups;
    }

    /** The value, or the default of a {@code ${VARIABLE:default}} placeholder; {@code null} without one. */
    private static String resolve(String value) {
        Matcher placeholder = PLACEHOLDER.matcher(value.trim());
        if (placeholder.matches()) {
            return placeholder.group(1);
        }
        return value;
    }

    private static LogLevel level(String value) {
        if (value == null) {
            return null;
        }
        if (value.trim().equalsIgnoreCase("false")) {
            // YAML reads an unquoted off as false, and Spring Boot takes it as OFF.
            return LogLevel.OFF;
        }
        try {
            return LogLevel.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    /**
     * {@code key} as Spring Boot's relaxed binding reads it: lower case, without dashes or underscores, and with a
     * bracketed part as a dotted one, so {@code spring.jpa.showSql} and {@code spring.jpa.properties[hibernate.show_sql]}
     * match too.
     */
    private static String canonical(String key) {
        return key.toLowerCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "")
                .replace("[", ".")
                .replace("]", "");
    }

    /**
     * The logger or group name that {@code key} sets under {@code prefix}, without the brackets that keep its dots;
     * {@code null} if {@code key} is not under {@code prefix}. A quoted {@code "[a.b]"} key in YAML comes as {@code
     * prefix[a.b]}, with no dot before it.
     */
    private static String nameAfter(String key, String prefix) {
        if (!key.startsWith(prefix + ".") && !key.startsWith(prefix + "[")) {
            return null;
        }
        String name = key.substring(prefix.length());
        return (name.startsWith(".") ? name.substring(1) : name)
                .replace("[", "")
                .replace("]", "");
    }

    private static List<Path> files(Path root, Pattern name) {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> name.matcher(file.getFileName().toString()).matches())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The properties of each document of the file, with their values as written. */
    private static List<Map<String, String>> documents(Path file) {
        PropertySourceLoader loader = file.toString().endsWith(".properties")
                ? new PropertiesPropertySourceLoader()
                : new YamlPropertySourceLoader();
        List<Map<String, String>> documents = new ArrayList<>();
        try {
            for (PropertySource<?> source : loader.load(file.toString(), new FileSystemResource(file))) {
                EnumerablePropertySource<?> enumerable = (EnumerablePropertySource<?>) source;
                Map<String, String> properties = new LinkedHashMap<>();
                for (String name : enumerable.getPropertyNames()) {
                    properties.put(name, String.valueOf(enumerable.getProperty(name)));
                }
                documents.add(properties);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return documents;
    }
}
