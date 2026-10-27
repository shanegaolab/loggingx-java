package com.loggingx.dao.monitor;

import com.loggingx.dao.support.Timestamps;

import java.time.Instant;

/**
 * An alert raised by a rule for a host.
 *
 * @param value     the metric's latest value while firing (minutes since the last sync for {@code offline})
 * @param peakValue the largest value seen while firing
 * @param status    {@code firing} or {@code resolved}
 */
public record AlertEvent(
        Long id,
        Long ruleId,
        String ruleName,
        String metric,
        String severity,
        double threshold,
        Long storeId,
        String hostname,
        Double value,
        Double peakValue,
        String status,
        Instant startedAt,
        Instant updatedAt,
        Instant resolvedAt
) {

    public static final String FIRING = "firing";
    public static final String RESOLVED = "resolved";

    public AlertEvent {
        startedAt = Timestamps.storable(startedAt);
        updatedAt = Timestamps.storable(updatedAt);
        resolvedAt = Timestamps.storable(resolvedAt);
    }

    public AlertEvent withId(Long newId) {
        return new AlertEvent(newId, ruleId, ruleName, metric, severity, threshold, storeId, hostname, value,
                peakValue, status, startedAt, updatedAt, resolvedAt);
    }
}
