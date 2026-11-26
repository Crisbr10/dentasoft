package es.crisbr.dentasoft.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class RegisteredUsersTests {

    private static final UUID FIRST = UUID.fromString("5f0c2b1e-0000-4000-8000-000000000301");
    private static final UUID SECOND = UUID.fromString("5f0c2b1e-0000-4000-8000-000000000302");
    private static final UUID THIRD = UUID.fromString("5f0c2b1e-0000-4000-8000-000000000303");

    @Test
    void remembersOnlyTheMostRecentlySeenUsers() {
        RegisteredUsers registered = new RegisteredUsers(2);
        registered.add(FIRST);
        registered.add(SECOND);

        assertThat(registered.contains(FIRST)).isTrue();
        registered.add(THIRD);

        assertThat(registered.contains(FIRST)).as("seen again, so kept").isTrue();
        assertThat(registered.contains(SECOND))
                .as("the least recently seen, so forgotten")
                .isFalse();
        assertThat(registered.contains(THIRD)).isTrue();
    }

    @Test
    void needsRoomForAtLeastOneUser() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RegisteredUsers(0));
    }
}
