package com.loggingx.auth.service;

import com.loggingx.dao.support.TestRedis;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginAttemptLimiterTest {

    /** Short enough to wait out; expiry is Redis' job, so the tests use real time. */
    private static final Duration LOCK_DURATION = Duration.ofMillis(300);

    private static final TestRedis REDIS = new TestRedis();

    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(REDIS.template, LOCK_DURATION);

    @BeforeEach
    void clearRedis() {
        REDIS.flushAll();
    }

    @AfterAll
    static void stopRedis() throws Exception {
        REDIS.close();
    }

    private void fail(int times) {
        for (int i = 0; i < times; i++) {
            limiter.recordFailure("alice");
        }
    }

    private static void waitOutLockDuration() throws InterruptedException {
        Thread.sleep(LOCK_DURATION.plusMillis(100).toMillis());
    }

    @Test
    void locksAfterMaxFailuresAndUnlocksAfterLockDuration() throws Exception {
        fail(LoginAttemptLimiter.MAX_FAILURES - 1);
        assertFalse(limiter.isLocked("alice"));

        fail(1);
        assertTrue(limiter.isLocked("alice"));
        assertFalse(limiter.isLocked("bob"));

        waitOutLockDuration();
        assertFalse(limiter.isLocked("alice"));
        // A fresh window starts, so a single failure does not lock again.
        fail(1);
        assertFalse(limiter.isLocked("alice"));
    }

    @Test
    void failuresOutsideTheWindowDoNotAccumulate() throws Exception {
        fail(LoginAttemptLimiter.MAX_FAILURES - 1);
        waitOutLockDuration();

        fail(1);

        assertFalse(limiter.isLocked("alice"));
    }

    @Test
    void successResetsFailures() {
        fail(LoginAttemptLimiter.MAX_FAILURES - 1);
        limiter.recordSuccess("alice");

        fail(1);

        assertFalse(limiter.isLocked("alice"));
    }
}
