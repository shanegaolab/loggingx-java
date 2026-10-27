package com.loggingx.dao.log;

import com.loggingx.dao.support.Timestamps;

import java.time.Instant;

/**
 * A named collection of logs, typically one per application or environment.
 *
 * @param code          unique identifier producers use to address the store, e.g. {@code order-service}
 * @param retentionDays logs older than this many days are deleted
 * @param ingestToken   unique secret that authorizes writing to the store over HTTP
 */
public record LogStore(
        Long id,
        String code,
        String name,
        String description,
        int retentionDays,
        String ingestToken,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {

    public LogStore {
        createdAt = Timestamps.storable(createdAt);
        updatedAt = Timestamps.storable(updatedAt);
    }

    public LogStore withId(Long newId) {
        return new LogStore(newId, code, name, description, retentionDays, ingestToken, enabled, createdAt, updatedAt);
    }
}
