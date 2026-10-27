package com.loggingx.dao.monitor;

import com.loggingx.dao.support.Timestamps;

import java.time.Instant;

/**
 * A threshold alert on host metrics.
 *
 * @param metric          {@code cpu}, {@code memory}, {@code disk} (percent), {@code load} (load average per core) or
 *                        {@code offline}
 * @param threshold       the rule fires above it; ignored for {@code offline}
 * @param durationMinutes how long the condition must hold; for {@code offline}, how long the agent has not synced
 * @param severity        {@code warning} or {@code critical}
 * @param storeId         the store whose hosts the rule watches, or {@code null} for all stores
 */
public record AlertRule(
        Long id,
        String name,
        String metric,
        double threshold,
        int durationMinutes,
        String severity,
        Long storeId,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {

    public AlertRule {
        createdAt = Timestamps.storable(createdAt);
        updatedAt = Timestamps.storable(updatedAt);
    }

    public AlertRule withId(Long newId) {
        return new AlertRule(newId, name, metric, threshold, durationMinutes, severity, storeId, enabled, createdAt,
                updatedAt);
    }
}
