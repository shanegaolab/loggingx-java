package com.loggingx.biz.monitor;

import java.time.Instant;
import java.util.Map;

/**
 * One metric value as an agent reports it (a Vector {@code host_metrics} event).
 *
 * @param name  e.g. {@code cpu_seconds_total}
 * @param host  the reporting host's name
 * @param time  the scrape's time; all metrics of one scrape share it
 * @param tags  e.g. {@code cpu}, {@code mode}, {@code device}, {@code mountpoint}, {@code pid}
 * @param value the counter or gauge value
 */
public record MetricPoint(String name, String host, Instant time, Map<String, String> tags, double value) {

    public String tag(String key) {
        return tags == null ? null : tags.get(key);
    }
}
