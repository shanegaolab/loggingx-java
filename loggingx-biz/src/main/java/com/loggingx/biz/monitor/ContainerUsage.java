package com.loggingx.biz.monitor;

import java.time.Instant;

/**
 * A container or service's current resource use.
 *
 * @param kind       {@code docker}, {@code kubernetes}, {@code service} or {@code other}
 * @param name       short name: the container id or the service name
 * @param cpuPercent CPU use in percent of one core over the last scrape interval, or {@code null} with one scrape
 */
public record ContainerUsage(String cgroup, String kind, String name, Double cpuPercent, Double memBytes,
                             Instant time) {
}
