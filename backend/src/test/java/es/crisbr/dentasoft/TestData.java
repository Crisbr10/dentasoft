package es.crisbr.dentasoft;

import java.util.List;
import java.util.UUID;

/**
 * Invented test data. Never use real patient data, not even locally: these names are made up and every email address
 * uses the reserved {@code .test} domain, so nothing can reach a real person.
 *
 * <p>Two clinics with fixed IDs are provided for the isolation tests every module with data must have (clinic A must
 * not read or change the data of clinic B).
 */
public final class TestData {

    public static final Clinic CLINIC_A =
            new Clinic(UUID.fromString("0192f0a0-0000-7000-8000-00000000000a"), "Clínica Prueba Norte");
    public static final Clinic CLINIC_B =
            new Clinic(UUID.fromString("0192f0a0-0000-7000-8000-00000000000b"), "Clínica Prueba Sur");

    private static final List<String> GIVEN_NAMES = List.of("Ana", "Luis", "Marta", "Jorge", "Elena", "Pablo");
    private static final List<String> FAMILY_NAMES = List.of("Ejemplo", "Prueba", "Ficticio", "Inventado");

    private TestData() {}

    /** Invented person number {@code n} (0, 1, 2…). The same number always returns the same person. */
    public static Person person(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("n must not be negative");
        }
        String givenName = GIVEN_NAMES.get(n % GIVEN_NAMES.size());
        String familyName = FAMILY_NAMES.get(n % FAMILY_NAMES.size());
        String email = "persona%d@pacientes.test".formatted(n);
        return new Person(givenName, familyName, email);
    }

    public record Clinic(UUID id, String name) {}

    public record Person(String givenName, String familyName, String email) {}
}
