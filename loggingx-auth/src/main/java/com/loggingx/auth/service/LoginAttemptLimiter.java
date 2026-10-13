package com.loggingx.auth.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Locks a username for {@link #LOCK_DURATION} after {@link #MAX_FAILURES} failed logins within that duration, to
 * slow down password guessing. Unknown usernames are tracked too, so the lock does not reveal which accounts exist.
 * <p>
 * State is kept in memory, so it is per instance and lost on restart.
 */
@Component
public class LoginAttemptLimiter {

    public static final int MAX_FAILURES = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    /** Above this many tracked usernames, expired entries are dropped so the map cannot grow without bound. */
    private static final int PRUNE_THRESHOLD = 10_000;

    private final Clock clock;
    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    public LoginAttemptLimiter() {
        this(Clock.systemUTC());
    }

    LoginAttemptLimiter(Clock clock) {
        this.clock = clock;
    }

    public boolean isLocked(String username) {
        Attempts current = attempts.get(keyOf(username));
        return current != null && current.lockedUntil() != null && clock.instant().isBefore(current.lockedUntil());
    }

    public void recordFailure(String username) {
        Instant now = clock.instant();
        attempts.compute(keyOf(username), (key, current) -> {
            Attempts window = current == null || current.isExpired(now) ? new Attempts(0, now, null) : current;
            int failures = window.failures() + 1;
            return new Attempts(failures, window.windowStart(),
                    failures >= MAX_FAILURES ? now.plus(LOCK_DURATION) : null);
        });
        if (attempts.size() > PRUNE_THRESHOLD) {
            attempts.values().removeIf(entry -> entry.isExpired(now));
        }
    }

    public void recordSuccess(String username) {
        attempts.remove(keyOf(username));
    }

    private static String keyOf(String username) {
        return username == null ? "" : username;
    }

    private record Attempts(int failures, Instant windowStart, Instant lockedUntil) {

        boolean isExpired(Instant now) {
            Instant end = lockedUntil != null ? lockedUntil : windowStart.plus(LOCK_DURATION);
            return !now.isBefore(end);
        }
    }
}
