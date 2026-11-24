package es.crisbr.dentasoft;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Public domains are temporary and change per environment: they must come from {@code dentasoft.urls.*}
 * and environment variables, never from {@code src/main}. Neither {@code https://} nor {@code http://} URLs are
 * allowed; the only exception is {@code http://localhost} in {@code application-local.yml}.
 */
class NoHardcodedUrlsTests {

    private static final Pattern FORBIDDEN = Pattern.compile("crisbr\\.es|https?://", Pattern.CASE_INSENSITIVE);

    /** Only the local profile may point at {@code http://localhost}, optionally followed by a port or a path. */
    private static final String LOCAL_PROFILE = "application-local.yml";

    private static final Pattern LOCALHOST = Pattern.compile("http://localhost(?=[:/\\s]|$)", Pattern.CASE_INSENSITIVE);

    @Test
    void mainSourcesContainNoFixedDomainOrHttpsUrl() {
        assertThat(violations(Path.of("src/main"))).isEmpty();
    }

    @Test
    void detectsFixedDomainsAndHttpsUrls(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("application-prod.yml"), "url: https://dentasoft-api.example.org\n");
        Files.writeString(root.resolve("Links.java"), "String host = \"dentasoft.crisbr.es\";\n");
        Files.writeString(root.resolve("application-local.yml"), "url: http://localhost:8080\n");

        assertThat(violations(root))
                .containsExactlyInAnyOrder(
                        "application-prod.yml:1: url: https://dentasoft-api.example.org",
                        "Links.java:1: String host = \"dentasoft.crisbr.es\";");
    }

    @Test
    void detectsHttpUrlsExceptLocalhostInTheLocalProfile(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("Links.java"), "String api = \"http://dentasoft-api.example.org\";\n");
        Files.writeString(root.resolve("application.yml"), "url: http://localhost:8080\n");
        Files.writeString(
                root.resolve("application-local.yml"),
                String.join(
                        "\n",
                        "app: http://localhost:4200",
                        "api: HTTP://LOCALHOST/api",
                        "auth: http://localhost",
                        "mail: http://mail.dentasoft.example.org",
                        "trap: http://localhost.example.org",
                        "both: http://localhost:8080 http://dentasoft.example.org",
                        ""));

        assertThat(violations(root))
                .containsExactlyInAnyOrder(
                        "Links.java:1: String api = \"http://dentasoft-api.example.org\";",
                        "application.yml:1: url: http://localhost:8080",
                        "application-local.yml:4: mail: http://mail.dentasoft.example.org",
                        "application-local.yml:5: trap: http://localhost.example.org",
                        "application-local.yml:6: both: http://localhost:8080 http://dentasoft.example.org");
    }

    private static List<String> violations(Path root) {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .flatMap(file -> violationsIn(root, file))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Stream<String> violationsIn(Path root, Path file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String name = root.relativize(file).toString().replace('\\', '/');
        boolean localProfile = file.getFileName().toString().equals(LOCAL_PROFILE);
        return java.util.stream.IntStream.range(0, lines.size())
                .filter(i -> isForbidden(lines.get(i), localProfile))
                .mapToObj(i -> name + ":" + (i + 1) + ": " + lines.get(i).strip());
    }

    private static boolean isForbidden(String line, boolean localProfile) {
        String checked = localProfile ? LOCALHOST.matcher(line).replaceAll("") : line;
        return FORBIDDEN.matcher(checked).find();
    }
}
