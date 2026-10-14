package com.loggingx.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Locks a username for {@link #LOCK_DURATION} after {@link #MAX_FAILURES} failed logins within that duration, to
 * slow down password guessing. Unknown usernames are tracked too, so the lock does not reveal which accounts exist.
 * <p>
 * State lives in Redis, so it is shared by every instance and survives restarts; key expiry discards it. When
 * Redis is unreachable, logins are let through rather than locking everyone out, and a warning is logged.
 */
@Component
public class LoginAttemptLimiter {

    public static final int MAX_FAILURES = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptLimiter.class);

    /**
     * Counts a failure in a window that starts with the first failure; at the limit, replaces the count with a
     * lock. KEYS: failures, lock. ARGV: max failures, window and lock duration in milliseconds.
     */
    private static final RedisScript<Long> RECORD_FAILURE = RedisScript.of("""
            local failures = redis.call('INCR', KEYS[1])
            if failures == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            if failures >= tonumber(ARGV[1]) then
                redis.call('SET', KEYS[2], '1', 'PX', ARGV[2])
                redis.call('DEL', KEYS[1])
            end
            return failures
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Duration lockDuration;

    @Autowired
    public LoginAttemptLimiter(StringRedisTemplate redis) {
        this(redis, LOCK_DURATION);
    }

    LoginAttemptLimiter(StringRedisTemplate redis, Duration lockDuration) {
        this.redis = redis;
        this.lockDuration = lockDuration;
    }

    public boolean isLocked(String username) {
        try {
            return Boolean.TRUE.equals(redis.hasKey(lockKey(username)));
        } catch (DataAccessException e) {
            log.warn("Cannot check the login lock in Redis; allowing the attempt", e);
            return false;
        }
    }

    public void recordFailure(String username) {
        try {
            redis.execute(RECORD_FAILURE, List.of(failuresKey(username), lockKey(username)),
                    String.valueOf(MAX_FAILURES), String.valueOf(lockDuration.toMillis()));
        } catch (DataAccessException e) {
            log.warn("Cannot record a failed login in Redis", e);
        }
    }

    public void recordSuccess(String username) {
        try {
            redis.delete(List.of(failuresKey(username), lockKey(username)));
        } catch (DataAccessException e) {
            log.warn("Cannot clear failed logins in Redis", e);
        }
    }

    // The {hash tag} keeps both keys of a username in one cluster slot, as the script needs.
    private static String failuresKey(String username) {
        return "loggingx:login:{" + keyOf(username) + "}:failures";
    }

    private static String lockKey(String username) {
        return "loggingx:login:{" + keyOf(username) + "}:lock";
    }

    private static String keyOf(String username) {
        return username == null ? "" : username;
    }
}
