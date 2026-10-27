package com.loggingx.dao.monitor;

import com.loggingx.dao.support.Timestamps;

import java.time.Instant;

/**
 * An alert raised by a log rule.
 *
 * @param value  matching logs in the window at the latest evaluation
 * @param sample the newest matching message
 * @param status {@code firing} or {@code resolved}
 */
public record LogAlertEvent(Long id, Long ruleId, String ruleName, Long storeId, String severity, int threshold,
                            int windowMinutes, Long value, Long peakValue, String sample, String status,
                            Instant startedAt, Instant updatedAt, Instant resolvedAt) {

    public LogAlertEvent {
        startedAt = Timestamps.storable(startedAt);
        updatedAt = Timestamps.storable(updatedAt);
        resolvedAt = Timestamps.storable(resolvedAt);
    }

    public LogAlertEvent withId(Long newId) {
        return new LogAlertEvent(newId, ruleId, ruleName, storeId, severity, threshold, windowMinutes, value,
                peakValue, sample, status, startedAt, updatedAt, resolvedAt);
    }
}
