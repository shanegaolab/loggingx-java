package com.loggingx.auth.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginAttemptLimiterTest {

    private Instant now = Instant.parse("2026-10-13T00:00:00Z");

    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    });

    private void fail(int times) {
        for (int i = 0; i < times; i++) {
            limiter.recordFailure("alice");
        }
    }

    @Test
    void locksAfterMaxFailuresAndUnlocksAfterLockDuration() {
        fail(LoginAttemptLimiter.MAX_FAILURES - 1);
        assertFalse(limiter.isLocked("alice"));

        fail(1);
        assertTrue(limiter.isLocked("alice"));
        assertFalse(limiter.isLocked("bob"));

        now = now.plus(LoginAttemptLimiter.LOCK_DURATION);
        assertFalse(limiter.isLocked("alice"));
        // A fresh window starts, so a single failure does not lock again.
        fail(1);
        assertFalse(limiter.isLocked("alice"));
    }

    @Test
    void failuresOutsideTheWindowDoNotAccumulate() {
        fail(LoginAttemptLimiter.MAX_FAILURES - 1);
        now = now.plus(LoginAttemptLimiter.LOCK_DURATION).plus(Duration.ofSeconds(1));

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
