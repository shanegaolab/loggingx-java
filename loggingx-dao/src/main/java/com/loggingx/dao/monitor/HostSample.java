package com.loggingx.dao.monitor;

import java.time.Instant;

/**
 * One scrape of a host's resources, as reported by its agent. Counters ({@code cpu*}, {@code disk*}, {@code net*})
 * are cumulative since boot, so rates come from the difference between two samples; the other values are gauges.
 * Values a host does not report (load on Windows, for example) are {@code null}.
 * <p>
 * A scrape reaching LoggingX in several requests is stored as several rows with the same time, each holding part of
 * the values; reads merge them by taking the largest value of each column.
 *
 * @param host     host name, lower case, as the host reports it (equals the agent's host name ignoring case)
 * @param cpuTotal CPU seconds of all CPUs in all modes
 * @param cpuIdle  CPU seconds of all CPUs spent idle or waiting for I/O
 * @param memUsed  memory in use: total minus available
 */
public record HostSample(
        Long storeId,
        String host,
        Instant time,
        Double cpuTotal,
        Double cpuIdle,
        Double cpuCores,
        Double memTotal,
        Double memUsed,
        Double swapTotal,
        Double swapUsed,
        Double load1,
        Double load5,
        Double load15,
        Double diskReadBytes,
        Double diskWriteBytes,
        Double netRxBytes,
        Double netTxBytes,
        Double uptime
) {

    /**
     * @return a sample holding, for each value, the larger of this one and {@code other}'s (absent values ignored)
     */
    public HostSample merge(HostSample other) {
        return new HostSample(storeId, host, time.isAfter(other.time) ? time : other.time,
                max(cpuTotal, other.cpuTotal), max(cpuIdle, other.cpuIdle), max(cpuCores, other.cpuCores),
                max(memTotal, other.memTotal), max(memUsed, other.memUsed), max(swapTotal, other.swapTotal),
                max(swapUsed, other.swapUsed), max(load1, other.load1), max(load5, other.load5),
                max(load15, other.load15), max(diskReadBytes, other.diskReadBytes),
                max(diskWriteBytes, other.diskWriteBytes), max(netRxBytes, other.netRxBytes),
                max(netTxBytes, other.netTxBytes), max(uptime, other.uptime));
    }

    static Double max(Double a, Double b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : Math.max(a, b);
    }
}
