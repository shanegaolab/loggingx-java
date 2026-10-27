package com.loggingx.dao.monitor;

import com.loggingx.dao.support.Timestamps;

import java.time.Instant;

/**
 * Host resource monitoring of a log store's agents.
 *
 * @param intervalSeconds seconds between scrapes of CPU, memory, disks and network
 * @param processes       whether busy processes are reported (scraped half as often)
 * @param containers      whether containers and services (Linux control groups) are reported
 * @param updatedAt       when the config was last saved, or {@code null} for the defaults
 */
public record MonitorConfig(
        Long storeId,
        boolean enabled,
        int intervalSeconds,
        boolean processes,
        boolean containers,
        Instant updatedAt
) {

    public static final int MIN_INTERVAL_SECONDS = 5;
    public static final int MAX_INTERVAL_SECONDS = 300;

    public MonitorConfig {
        updatedAt = Timestamps.storable(updatedAt);
    }

    public static MonitorConfig defaults(Long storeId) {
        return new MonitorConfig(storeId, true, 15, true, true, null);
    }
}
