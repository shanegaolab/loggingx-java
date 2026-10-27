package com.loggingx.dao.monitor;

import com.loggingx.dao.support.Timestamps;

import java.time.Instant;

/**
 * An alert on a store's logs: more than {@code threshold} logs within the last {@code windowMinutes} matching the
 * levels, keyword and host.
 *
 * @param levels  comma-separated levels, or {@code null} for all
 * @param keyword substring of the message, ignoring case, or {@code null}
 * @param host    exact host, or {@code null}
 */
public record LogAlertRule(Long id, String name, Long storeId, String levels, String keyword, String host,
                           int windowMinutes, int threshold, String severity, boolean enabled, Instant createdAt,
                           Instant updatedAt) {

    public LogAlertRule {
        createdAt = Timestamps.storable(createdAt);
        updatedAt = Timestamps.storable(updatedAt);
    }

    public LogAlertRule withId(Long newId) {
        return new LogAlertRule(newId, name, storeId, levels, keyword, host, windowMinutes, threshold, severity,
                enabled, createdAt, updatedAt);
    }
}
