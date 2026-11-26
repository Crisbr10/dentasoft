package es.crisbr.dentasoft.shared;

import java.time.Duration;

/**
 * Limits on how often something may happen for one key, such as the invitations a clinic sends in a day: token buckets
 * shared by every instance through Redis, with the same per-instance fallback as the request rate limits when Redis
 * fails. Keys must not hold personal data: they end up in Redis.
 */
public interface Quotas {

    /**
     * Counts one use of {@code key} against {@code limit} uses per {@code period}, refilled gradually.
     *
     * @param name what is limited (a fixed name such as {@code invitation}); keys of different names never mix
     * @return whether it was allowed and, if not, how long until one more use is
     */
    Decision tryAcquire(String name, String key, int limit, Duration period);

    record Decision(boolean allowed, Duration retryAfter) {

        public static Decision allow() {
            return new Decision(true, Duration.ZERO);
        }
    }
}
