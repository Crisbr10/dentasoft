package es.crisbr.dentasoft.identity;

import static es.crisbr.dentasoft.TestPostgresConfiguration.connectAsOwner;
import static org.assertj.core.api.Assertions.assertThat;

import es.crisbr.dentasoft.IntegrationTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The clinic roles are one closed list, the same in the code, in the database and in the permission matrix of
 * {@code docs/plan/configuracion.md}: a role added to one of them and not to the others fails here.
 */
@IntegrationTest
class ClinicRoleTests {

    private static final Path PERMISSION_MATRIX = Path.of("docs", "plan", "configuracion.md");

    /** The realm role of the platform, which has a column in the matrix but is not a clinic role. */
    private static final String PLATFORM_ROLE = "PLATFORM_ADMIN";

    private static final List<String> ROLES =
            Arrays.stream(ClinicRole.values()).map(Enum::name).toList();

    @Test
    void theRolesAreTheClinicColumnsOfThePermissionMatrix() throws IOException {
        List<String> columns = matrixRoleColumns();

        assertThat(columns).as("role columns of %s", PERMISSION_MATRIX).startsWith(PLATFORM_ROLE);
        assertThat(columns.subList(1, columns.size())).containsExactlyElementsOf(ROLES);
    }

    @Test
    void theDatabaseAcceptsExactlyTheSameRoles() throws SQLException {
        try (Connection connection = connectAsOwner();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?")) {
            statement.setString(1, "clinic_membership_role_check");
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next())
                        .as("clinic_membership_role_check exists")
                        .isTrue();
                List<String> accepted = new ArrayList<>();
                Matcher literal = Pattern.compile("'([A-Z_]+)'").matcher(result.getString(1));
                while (literal.find()) {
                    accepted.add(literal.group(1));
                }
                assertThat(accepted).containsExactlyElementsOf(ROLES);
            }
        }
    }

    /** The columns of the matrix's header row that are roles: those in upper case. */
    private static List<String> matrixRoleColumns() throws IOException {
        String header = Files.readAllLines(fromRepositoryRoot(PERMISSION_MATRIX), StandardCharsets.UTF_8).stream()
                .filter(line -> line.startsWith("| Sección |"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no permission matrix in " + PERMISSION_MATRIX));
        return Arrays.stream(header.split("\\|"))
                .map(String::strip)
                .filter(cell -> cell.matches("[A-Z_]+"))
                .toList();
    }

    /** From the working directory or one of its parents: backend/ with Maven, the repository root in some IDEs. */
    private static Path fromRepositoryRoot(Path file) {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve(file))) {
                return dir.resolve(file);
            }
        }
        throw new IllegalStateException(file + " not found from " + Path.of("").toAbsolutePath() + " or its parents");
    }
}
