package com.loggingx.biz.monitor;

import java.time.Instant;

/**
 * A monitored host's current state: its agent and its latest resource values. Rates and the CPU use come from the
 * latest two samples; values the host does not report are {@code null}.
 *
 * @param host          lower-case host name, the key of the host's metrics
 * @param hostname      the agent's host name as registered, or {@code host} when there is no agent record
 * @param agentId       the agent record, or {@code null} for hosts that report metrics without one
 * @param online        whether the agent synced (or, without an agent record, metrics arrived) recently
 * @param sampleTime    time of the latest sample, or {@code null} when the host has not reported metrics recently
 * @param diskMaxPercent use of the fullest file system
 * @param alerts        firing alerts of the host
 * @param alertSeverity {@code critical} when any firing alert is critical, else {@code warning}, or {@code null}
 */
public record HostStatus(
        Long storeId,
        String host,
        String hostname,
        Long agentId,
        String platform,
        String version,
        String ip,
        boolean online,
        Instant lastSeenAt,
        Instant sampleTime,
        Double cpuPercent,
        Double cpuCores,
        Double memTotal,
        Double memUsed,
        Double memPercent,
        Double swapTotal,
        Double swapUsed,
        Double load1,
        Double load5,
        Double load15,
        Double diskMaxPercent,
        String diskMaxMount,
        Double diskReadBps,
        Double diskWriteBps,
        Double netRxBps,
        Double netTxBps,
        Double uptime,
        int alerts,
        String alertSeverity
) {
}
