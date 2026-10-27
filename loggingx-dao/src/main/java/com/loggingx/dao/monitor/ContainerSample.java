package com.loggingx.dao.monitor;

import java.time.Instant;

/**
 * A container (or systemd service) in a scrape, from its control group (Linux, cgroups v2).
 *
 * @param cgroup     control group path, e.g. {@code /system.slice/docker-3f2a....scope}
 * @param cpuSeconds cumulative CPU seconds; the CPU use comes from two samples
 * @param memBytes   current memory use
 */
public record ContainerSample(
        Long storeId,
        String host,
        Instant time,
        String cgroup,
        Double cpuSeconds,
        Double memBytes
) {
}
