package es.crisbr.dentasoft.identity.internal;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The users this instance has recently registered or found registered, so their next requests skip the insert. Only
 * the most recent {@code capacity} are kept. Forgetting one costs a single {@code INSERT … ON CONFLICT DO NOTHING};
 * each instance keeps its own, which is fine for the same reason.
 */
final class RegisteredUsers {

    private final Map<UUID, Boolean> recent;

    RegisteredUsers(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.recent = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
                return size() > capacity;
            }
        };
    }

    synchronized boolean contains(UUID user) {
        // get, not containsKey: it also makes the user the most recent, so the users seen most stay remembered.
        return recent.get(user) != null;
    }

    synchronized void add(UUID user) {
        recent.put(user, Boolean.TRUE);
    }
}
