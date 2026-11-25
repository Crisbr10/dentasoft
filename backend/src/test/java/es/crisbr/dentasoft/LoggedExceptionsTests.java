package es.crisbr.dentasoft;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * No exception reaches a logger with its messages, which may carry personal or clinical data (#123, #126): code logs
 * {@code Logging.redacted(e)}, and at most {@code e.getClass()}, never {@code e} itself, its message or its {@code
 * toString()}.
 *
 * <p>It reads the source, so it cannot know every type: an exception counts as one when its variable is declared with
 * a type named {@code *Exception}, {@code *Error} or {@code Throwable} in the same file ({@code var} included, when it
 * is given a {@code new} one), or has one of the usual names ({@link #USUAL_NAMES}). The fluent API ({@code
 * log.atError()}), {@code printStackTrace()} and {@code System.out} or {@code System.err} are refused outright. A
 * justified exception goes in {@link #ALLOWED}, with the reason.
 *
 * <p>It misses an exception it cannot name: under an unusual name with no declared type (an untyped lambda parameter,
 * or one returned by a method), in a message built in a variable before the call, or logged through a logger reached
 * other than by a variable declared {@code Logger} (or named {@code log} or {@code logger}).
 */
class LoggedExceptionsTests {

    /**
     * Calls allowed to log an exception as is: "{@code File.java: call}", with its whitespace collapsed and its string
     * literals emptied, to the reason it carries no personal data.
     */
    private static final Map<String, String> ALLOWED = Map.of();

    /** Names given to exceptions whose type the file does not say, such as an untyped lambda parameter. */
    private static final Set<String> USUAL_NAMES =
            Set.of("e", "ex", "exc", "exception", "failure", "cause", "throwable", "t", "th", "err", "error");

    private static final Pattern THROWABLE_VARIABLE = Pattern.compile(
            "\\b(?:Throwable|[A-Z]\\w*(?:Exception|Error))(?:\\s*\\|\\s*[\\w.]+)*\\s+(\\w+)\\s*[,)=;:]");

    private static final Pattern NEW_THROWABLE_VARIABLE = Pattern.compile(
            "\\bvar\\s+(\\w+)\\s*=\\s*new\\s+(?:\\w+\\.)*(?:Throwable|[A-Z]\\w*(?:Exception|Error))\\s*[(<]");

    private static final Pattern LOGGER_VARIABLE = Pattern.compile("\\bLogger\\s+(\\w+)\\s*[=;]");

    private static final Pattern REDACTED = Pattern.compile("\\b(?:Logging\\.redacted|RedactedException\\.of)\\s*\\(");

    private static final Pattern MESSAGE = Pattern.compile("\\.\\s*get(?:Localized)?Message\\s*\\(");

    private static final Pattern REFUSED =
            Pattern.compile("\\.\\s*printStackTrace\\s*\\(\\s*\\)|\\bSystem\\s*\\.\\s*(?:out|err)\\b");

    @Test
    void mainSourcesLogNoExceptionWithItsMessages() {
        assertThat(violations(Path.of("src/main/java"))).isEmpty();
    }

    @Test
    void everyAllowedCallIsStillInTheCode() {
        Set<String> found = new HashSet<>();
        forEachJavaFile(Path.of("src/main/java"), (name, source) -> found.addAll(unredactedCalls(name, source)));
        assertThat(found).containsAll(ALLOWED.keySet());
    }

    @Test
    void detectsAnExceptionPassedToTheLogger() {
        assertThat(unredactedCalls("Job.java", """
                class Job {
                    private static final Logger log = LoggerFactory.getLogger(Job.class);
                    void run() {
                        try { work(); } catch (IllegalStateException | IllegalArgumentException e) {
                            log.error("Job failed", e);
                        }
                    }
                }
                """)).containsExactly("Job.java: log.error(\"\", e)");
    }

    @Test
    void detectsAnExceptionAsAPlaceholderConcatenatedOrByItsMessage() {
        assertThat(unredactedCalls("Job.java", """
                class Job {
                    void failed(RuntimeException problem, Object job) {
                        log.warn("Job {} failed: {}", job, problem);
                        log.info("Job failed: " + problem);
                        log.debug("Job failed: {}", problem.getMessage());
                        log.debug("Job failed: {}", job.toString(), () -> problem.getLocalizedMessage());
                    }
                }
                """)).hasSize(4);
    }

    @Test
    void detectsUntypedExceptionsByTheirUsualNamesAndOtherLoggerNames() {
        assertThat(unredactedCalls("Job.java", """
                class Job {
                    private static final Logger LOGGER = LoggerFactory.getLogger(Job.class);
                    void run() {
                        future.whenComplete((result, failure) -> LOGGER.error("Job failed", failure));
                    }
                }
                """)).containsExactly("Job.java: LOGGER.error(\"\", failure)");
    }

    @Test
    void detectsAVarGivenANewException() {
        assertThat(unredactedCalls("Job.java", """
                class Job {
                    void run() {
                        var problem = new java.lang.IllegalStateException("Job failed");
                        log.error("Job failed", problem);
                    }
                }
                """)).containsExactly("Job.java: log.error(\"\", problem)");
    }

    @Test
    void refusesTheFluentApiPrintStackTraceAndTheStandardStreams() {
        assertThat(unredactedCalls("Job.java", """
                class Job {
                    void failed(Exception e) {
                        log.atError().setCause(e).log("Job failed");
                        e.printStackTrace();
                        System.err.println("Job failed");
                        System.out.printf("Job %s failed%n", e);
                    }
                }
                """)).hasSize(4);
    }

    @Test
    void acceptsRedactedExceptionsTheirClassesAndCommentsOrStringsThatMentionThem() {
        assertThat(unredactedCalls("Job.java", """
                class Job {
                    /** Logs it as {@code log.error("Job failed", e)} would not. */
                    void failed(Exception e, Object job, String errorType) {
                        // log.error("Job failed", e);
                        log.error("Job {} failed: {}", job, e.getClass().getName(), Logging.redacted(e));
                        log.warn("Job {} failed: {}", job, errorType);
                        log.error("Unexpected error in a scheduled task", RedactedException.of(e));
                        log.info("e, failure: (", job);
                        log.info(\"""
                                Job failed with e
                                \""", job);
                    }
                }
                """)).isEmpty();
    }

    private static List<String> violations(Path root) {
        List<String> violations = new ArrayList<>();
        forEachJavaFile(
                root,
                (name, source) -> unredactedCalls(name, source).stream()
                        .filter(call -> !ALLOWED.containsKey(call))
                        .forEach(violations::add));
        return violations;
    }

    private static void forEachJavaFile(Path root, BiConsumer<String, String> action) {
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file :
                    files.filter(path -> path.toString().endsWith(".java")).toList()) {
                action.accept(file.getFileName().toString(), Files.readString(file, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Each logging call in {@code source} that lets an exception through, as "{@code File.java: call}". */
    static List<String> unredactedCalls(String fileName, String source) {
        String code = withoutCommentsAndStrings(source);
        Set<String> exceptions = new HashSet<>(USUAL_NAMES);
        collect(THROWABLE_VARIABLE, code, exceptions);
        collect(NEW_THROWABLE_VARIABLE, code, exceptions);
        Set<String> loggers = new HashSet<>(Set.of("log", "logger", "LOG", "LOGGER"));
        collect(LOGGER_VARIABLE, code, loggers);
        Pattern exception =
                Pattern.compile("\\b(?:" + String.join("|", exceptions) + ")\\b(?!\\s*\\.\\s*getClass\\s*\\(\\s*\\))");
        Pattern call = Pattern.compile(
                "\\b(?:" + String.join("|", loggers)
                        + ")\\s*\\.\\s*(?:(?:trace|debug|info|warn|error)\\s*\\(|(at(?:Trace|Debug|Info|Warn|Error|Level))\\b)");

        List<String> found = new ArrayList<>();
        Matcher calls = call.matcher(code);
        while (calls.find()) {
            if (calls.group(1) != null) {
                // The fluent API is refused: its cause and arguments are spread over the chain.
                found.add(describe(fileName, code.substring(calls.start(), statementEnd(code, calls.start()))));
                continue;
            }
            int open = calls.end() - 1;
            int close = matchingParenthesis(code, open);
            String arguments = removeRedacted(code.substring(open + 1, close));
            if (exception.matcher(arguments).find()
                    || MESSAGE.matcher(arguments).find()) {
                found.add(describe(fileName, code.substring(calls.start(), close + 1)));
            }
        }
        Matcher refused = REFUSED.matcher(code);
        while (refused.find()) {
            found.add(describe(fileName, code.substring(refused.start(), refused.end())));
        }
        return found;
    }

    private static void collect(Pattern pattern, String code, Set<String> names) {
        Matcher matcher = pattern.matcher(code);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
    }

    private static String describe(String fileName, String call) {
        return fileName + ": "
                + call.replaceAll("\\s+", " ").replaceAll("\\( ", "(").replaceAll(" \\)", ")");
    }

    /** {@code arguments} without the {@code Logging.redacted(...)} calls and what they are given. */
    private static String removeRedacted(String arguments) {
        StringBuilder rest = new StringBuilder(arguments);
        Matcher redacted = REDACTED.matcher(rest);
        while (redacted.find()) {
            int close = matchingParenthesis(rest, redacted.end() - 1);
            rest.delete(redacted.start(), close + 1);
            redacted = REDACTED.matcher(rest);
        }
        return rest.toString();
    }

    private static int matchingParenthesis(CharSequence code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        throw new IllegalArgumentException("Unbalanced parentheses after index " + open);
    }

    private static int statementEnd(String code, int from) {
        int end = code.indexOf(';', from);
        return end < 0 ? code.length() : end;
    }

    /**
     * {@code source} with comments removed and string, text block and character literals emptied, so that neither
     * counts as code; newlines are kept.
     */
    static String withoutCommentsAndStrings(String source) {
        StringBuilder code = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            if (source.startsWith("//", i)) {
                i = indexOrEnd(source, "\n", i);
            } else if (source.startsWith("/*", i)) {
                int end = indexOrEnd(source, "*/", i + 2);
                keepNewlines(source, i, end, code);
                i = Math.min(end + 2, source.length());
            } else if (source.startsWith("\"\"\"", i)) {
                int end = indexOrEnd(source, "\"\"\"", i + 3);
                code.append("\"\"");
                keepNewlines(source, i, end, code);
                i = Math.min(end + 3, source.length());
            } else if (source.charAt(i) == '"' || source.charAt(i) == '\'') {
                char quote = source.charAt(i);
                int j = i + 1;
                while (j < source.length() && source.charAt(j) != quote) {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                code.append(quote).append(quote);
                i = j + 1;
            } else {
                code.append(source.charAt(i));
                i++;
            }
        }
        return code.toString();
    }

    private static int indexOrEnd(String source, String token, int from) {
        int index = source.indexOf(token, from);
        return index < 0 ? source.length() : index;
    }

    private static void keepNewlines(String source, int from, int to, StringBuilder code) {
        source.substring(from, to).chars().filter(c -> c == '\n').forEach(c -> code.append('\n'));
    }
}
