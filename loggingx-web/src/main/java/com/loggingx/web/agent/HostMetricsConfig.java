package com.loggingx.web.agent;

import com.loggingx.dao.log.LogStore;
import com.loggingx.dao.monitor.MonitorConfig;
import com.loggingx.web.agent.AgentOptions.Platform;

/**
 * The host resource monitoring part of the Vector config: {@code host_metrics} sources, a transform that keeps only
 * the metrics LoggingX uses, and an HTTP sink to {@code /api/metrics/ingest}.
 * <p>
 * Processes are scraped at most every 30 seconds and only those using at least 0.5% CPU or 50 MiB of memory are sent,
 * which keeps a scrape to a few hundred small events (a host has hundreds of processes). Metrics are sent compressed,
 * in memory buffers that drop the newest metrics when LoggingX is unreachable: unlike logs, old metrics are not worth
 * keeping on disk. In containers, the host's {@code /proc} and {@code /sys} are mounted below {@code /host}, and the
 * Kubernetes agent reports under its node's name, which it registered with.
 */
final class HostMetricsConfig {

    static final String INGEST_PATH = "/api/metrics/ingest";
    /** Processes are scraped at most this often. */
    private static final int PROCESS_INTERVAL_SECONDS = 30;
    private static final String USED_METRICS = String.join(", ",
            "\"cpu_seconds_total\"", "\"logical_cpus\"", "\"memory_total_bytes\"", "\"memory_available_bytes\"",
            "\"memory_swap_total_bytes\"", "\"memory_swap_used_bytes\"", "\"load1\"", "\"load5\"", "\"load15\"",
            "\"uptime\"", "\"disk_read_bytes_total\"", "\"disk_written_bytes_total\"",
            "\"network_receive_bytes_total\"", "\"network_transmit_bytes_total\"", "\"filesystem_total_bytes\"",
            "\"filesystem_used_bytes\"", "\"process_cpu_usage\"", "\"process_memory_usage\"",
            "\"cgroup_cpu_usage_seconds_total\"", "\"cgroup_memory_current_bytes\"");

    private HostMetricsConfig() {
    }

    static String sources(Platform platform, MonitorConfig monitor) {
        boolean cgroups = monitor.containers() && platform != Platform.WINDOWS;
        StringBuilder yaml = new StringBuilder()
                .append("  # Host resources: CPU, memory, disks, file systems, network and load.\n")
                .append("  host_metrics:\n")
                .append("    type: host_metrics\n")
                .append("    namespace: host\n")
                .append("    scrape_interval_secs: ").append(monitor.intervalSeconds()).append('\n')
                .append("    collectors: [cpu, memory, disk, filesystem, load, host, network")
                .append(cgroups ? ", cgroups" : "").append("]\n");
        if (cgroups) {
            yaml.append("    # Containers and services, three levels deep (e.g. /system.slice/docker-<id>.scope).\n")
                    .append("    cgroups:\n")
                    .append("      levels: 5\n");
        }
        if (monitor.processes()) {
            yaml.append("  host_processes:\n")
                    .append("    type: host_metrics\n")
                    .append("    namespace: host\n")
                    .append("    scrape_interval_secs: ")
                    .append(Math.max(PROCESS_INTERVAL_SECONDS, monitor.intervalSeconds())).append('\n')
                    .append("    collectors: [process]\n");
        }
        return yaml.toString();
    }

    static String transforms(Platform platform, MonitorConfig monitor) {
        String inputs = monitor.processes() ? "[host_metrics, host_processes]" : "[host_metrics]";
        String host = platform == Platform.KUBERNETES
                ? "# Pods report under their node's name, which the agent registered with.\n"
                + ".host = get_env_var(\"VECTOR_SELF_NODE_NAME\") ?? .host\n"
                : "";
        return """
                  metrics_as_logs:
                    type: metric_to_log
                    inputs: %s
                  metrics_used:
                    type: remap
                    inputs: [metrics_as_logs]
                    drop_on_abort: true
                    source: |
                      name = string(.name) ?? ""
                      if !includes([%s], name) {
                        abort
                      }
                      value = to_float(.gauge.value) ?? 0.0
                      # Only busy processes: at least 0.5%% CPU or 50 MiB of memory.
                      if name == "process_cpu_usage" && value < 0.5 {
                        abort
                      }
                      if name == "process_memory_usage" && value < 52428800.0 {
                        abort
                      }
                      del(.namespace)
                      del(.kind)
                      del(.tags.collector)
                      del(.tags.command)
                %s""".formatted(inputs, USED_METRICS, indent(host));
    }

    static String sink(LogStore store, String serverUrl) {
        return """
                  loggingx_metrics:
                    type: http
                    inputs: [metrics_used]
                    uri: %s
                    method: post
                    compression: gzip
                    encoding:
                      codec: json
                    request:
                      headers:
                        X-Log-Token: %s
                    healthcheck:
                      enabled: false
                    # A scrape is sent in one request, so LoggingX sees it whole.
                    batch:
                      max_events: 10000
                      max_bytes: 10485760
                      timeout_secs: 2
                    buffer:
                      type: memory
                      max_events: 20000
                      when_full: drop_newest
                """.formatted(VectorConfig.quote(serverUrl + INGEST_PATH), VectorConfig.quote(store.ingestToken()));
    }

    private static String indent(String text) {
        return text.lines().map(line -> "      " + line + "\n").reduce("", String::concat);
    }
}
