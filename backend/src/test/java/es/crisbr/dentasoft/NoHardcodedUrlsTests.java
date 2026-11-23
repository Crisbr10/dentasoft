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
 * and environment variables, never from {@code src/main}.
 */
class NoHardcodedUrlsTests {

    private static final Pattern FORBIDDEN = Pattern.compile("crisbr\\.es|https://", Pattern.CASE_INSENSITIVE);

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
        return java.util.stream.IntStream.range(0, lines.size())
                .filter(i -> FORBIDDEN.matcher(lines.get(i)).find())
                .mapToObj(i -> name + ":" + (i + 1) + ": " + lines.get(i).strip());
    }
}
