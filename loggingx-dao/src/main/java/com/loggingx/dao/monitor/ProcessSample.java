package com.loggingx.dao.monitor;

import java.time.Instant;

/**
 * A busy process in a scrape. Agents report only processes using a noticeable share of CPU or memory.
 *
 * @param cpuPercent CPU use in percent of one core (can exceed 100), or {@code null} when not reported
 * @param memBytes   resident memory, or {@code null} when below the agent's reporting threshold
 */
public record ProcessSample(
        Long storeId,
        String host,
        Instant time,
        long pid,
        String name,
        Double cpuPercent,
        Double memBytes
) {
}
