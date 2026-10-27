package com.loggingx.web.agent;

import com.loggingx.dao.agent.CollectConfig;
import com.loggingx.dao.log.LogStore;
import com.loggingx.dao.monitor.MonitorConfig;
import com.loggingx.web.agent.AgentOptions.Multiline;
import com.loggingx.web.agent.AgentOptions.Platform;
import com.loggingx.web.agent.AgentOptions.Transport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Renders the Vector configuration (YAML) for one log store.
 * <p>
 * Pipeline: the platform's sources (log files, Docker container output or Kubernetes pod logs) feed one
 * {@code remap} transform that turns each event into the LoggingX ingest format (see {@code LogPayloads}): the message,
 * a level and time when the line starts with them, the host, the source (file or container), and the byte offset as
 * {@code seq}, which keeps lines with the same timestamp in file order. Lines that are JSON objects are merged in, so
 * their own {@code level}, {@code time}, {@code traceId} and other fields are used. The sink sends batches to the HTTP
 * ingest API with the store's token, or publishes to Kafka with the store's code.
 * <p>
 * Delivery is at least once: events wait in a disk buffer while LoggingX is unreachable and survive restarts and
 * power loss; with end-to-end acknowledgements a file's checkpoint (in {@code data_dir}) only moves past lines that
 * reached the buffer, and when the buffer is full the sources stop reading, so the log files themselves hold the
 * backlog. Files without a checkpoint - including files rotated in while the agent was down - are read from the
 * beginning, except files not written for {@value #IGNORE_OLDER_SECS} seconds. Lines read twice after a crash can be
 * delivered twice; the ingest API drops exact repeats of a batch.
 */
final class VectorConfig {

    static final String DATA_DIR_LINUX = "/var/lib/loggingx-agent";
    static final String DATA_DIR_WINDOWS = "C:\\ProgramData\\LoggingX Agent\\data";
    /** The Docker container mounts the host's data directory here. */
    static final String DATA_DIR_DOCKER = "/var/lib/vector";
    static final String DATA_DIR_KUBERNETES = "/vector-data-dir";
    /** Docker mounts host log directories below this path. */
    static final String DOCKER_HOST_ROOT = "/host";
    /** Name of the agent's own container and namespace, whose output is not collected. */
    static final String AGENT_NAME = "loggingx-agent";
    static final String KUBERNETES_NAMESPACE = "loggingx";

    /** Batches never exceed the ingest API's limit of 1000 logs per request. */
    private static final int BATCH_EVENTS = 1000;
    /** Disk buffer per sink: 1 GiB, enough for hours of a busy host while LoggingX is unreachable. */
    static final long DISK_BUFFER_BYTES = 1_073_741_824L;
    /** Files untouched for longer (1 day) are not collected when first seen, so installing does not import archives. */
    static final int IGNORE_OLDER_SECS = 86_400;

    private VectorConfig() {
    }

    /** First line of every config an agent runs, followed by the config's revision. */
    static final String REVISION_PREFIX = "# loggingx-revision: ";

    /**
     * A config as agents receive it.
     *
     * @param text     the config, starting with {@value #REVISION_PREFIX} and the revision
     * @param revision fingerprint of the config; agents report the one they run and receive a config when it differs
     */
    record Rendered(AgentOptions options, String text, String revision) {
    }

    /**
     * Renders the config an agent on {@code platform} runs under the store's collect config.
     *
     * @throws com.loggingx.common.exception.BadRequestException when the collect config has nothing to collect there
     */
    static Rendered render(LogStore store, CollectConfig collect, MonitorConfig monitor, Platform platform,
                           String serverUrl) {
        AgentOptions options = AgentOptions.of(collect, platform, serverUrl);
        String body = render(store, options, monitor);
        String revision = revision(body);
        return new Rendered(options, REVISION_PREFIX + revision + "\n" + body, revision);
    }

    /**
     * @return the first 16 hex digits of the config's SHA-256; it covers the token and the server address too
     */
    static String revision(String body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String render(LogStore store, AgentOptions options) {
        return render(store, options, null);
    }

    /**
     * @param monitor host resource monitoring, or {@code null} for none
     */
    static String render(LogStore store, AgentOptions options, MonitorConfig monitor) {
        boolean metrics = monitor != null && monitor.enabled();
        StringBuilder yaml = new StringBuilder();
        yaml.append("# LoggingX log collection agent (Vector) for log store '").append(store.code()).append("'.\n")
                .append("# Managed by LoggingX: the agent fetches it on every sync, so change the store's collect config\n")
                .append("# in LoggingX instead of editing this file.\n")
                .append("data_dir: ").append(quote(dataDir(options.platform()))).append("\n\n");

        List<String> sources = new ArrayList<>();
        yaml.append("sources:\n");
        if (options.platform() == Platform.KUBERNETES) {
            sources.add("pods");
            yaml.append("""
                      pods:
                        type: kubernetes_logs
                        # The agent's own namespace and the system namespace are skipped.
                        extra_namespace_label_selector: 'kubernetes.io/metadata.name notin (kube-system,%s)'
                    """.formatted(KUBERNETES_NAMESPACE));
        }
        if (!options.paths().isEmpty()) {
            sources.add("files");
            yaml.append("  files:\n")
                    .append("    type: file\n")
                    .append("    include:\n");
            for (String path : options.paths()) {
                String collected = options.platform() == Platform.DOCKER ? DOCKER_HOST_ROOT + path : path;
                yaml.append("      - ").append(quote(collected)).append('\n');
            }
            yaml.append("    # Files without a checkpoint (new, or rotated while the agent was down) are read from the start,\n")
                    .append("    # so nothing written while the agent was stopped is skipped; files idle for a day are not.\n")
                    .append("    read_from: beginning\n")
                    .append("    ignore_older_secs: ").append(IGNORE_OLDER_SECS).append('\n')
                    .append("    # The byte offset of each line orders lines with the same timestamp.\n")
                    .append("    offset_key: offset\n");
            appendMultiline(yaml, options, "    ");
        }
        if (options.containers()) {
            sources.add("containers");
            yaml.append("  containers:\n")
                    .append("    type: docker_logs\n")
                    .append("    exclude_containers:\n")
                    .append("      - ").append(quote(AGENT_NAME)).append('\n');
            appendMultiline(yaml, options, "    ");
        }
        if (metrics) {
            yaml.append(HostMetricsConfig.sources(options.platform(), monitor));
        }

        yaml.append("\ntransforms:\n")
                .append("  normalize:\n")
                .append("    type: remap\n")
                .append("    inputs: ").append(inputs(sources)).append('\n')
                .append("    source: |\n")
                .append(indent(remap(store, options), "      "));
        if (metrics) {
            yaml.append(HostMetricsConfig.transforms(options.platform(), monitor));
        }

        yaml.append("\nsinks:\n");
        if (options.transport() == Transport.HTTP) {
            // The token is inline; the config is therefore readable by root only, or kept in a Kubernetes Secret.
            String token = store.ingestToken();
            yaml.append("  loggingx:\n")
                    .append("    type: http\n")
                    .append("    inputs: [normalize]\n")
                    .append("    uri: ").append(quote(options.serverUrl() + "/api/logs/ingest")).append('\n')
                    .append("    method: post\n")
                    .append("    encoding:\n")
                    .append("      codec: json\n")
                    .append("    request:\n")
                    .append("      # Network errors, timeouts, 5xx and 429 are retried until they succeed.\n")
                    .append("      retry_max_duration_secs: 60\n")
                    .append("      timeout_secs: 30\n")
                    .append("      headers:\n")
                    .append("        X-Log-Token: ").append(quote(token)).append('\n')
                    .append("        # A retried batch (its answer was lost) is not stored twice.\n")
                    .append("        X-Log-Dedup: batch\n")
                    .append("    # The ingest API needs a token, so the default health check (a plain GET) would fail.\n")
                    .append("    healthcheck:\n")
                    .append("      enabled: false\n");
        } else {
            yaml.append("  loggingx:\n")
                    .append("    type: kafka\n")
                    .append("    inputs: [normalize]\n")
                    .append("    bootstrap_servers: ").append(quote(options.kafkaServers())).append('\n')
                    .append("    topic: ").append(quote(options.kafkaTopic())).append('\n')
                    .append("    # Keyed by host, so each host's lines stay in one partition and in order.\n")
                    .append("    key_field: host\n")
                    .append("    encoding:\n")
                    .append("      codec: json\n");
        }
        yaml.append("    batch:\n")
                .append("      max_events: ").append(BATCH_EVENTS).append('\n')
                .append("      timeout_secs: 1\n")
                .append("    # A file's checkpoint only moves past lines that reached the disk buffer.\n")
                .append("    acknowledgements:\n")
                .append("      enabled: true\n")
                .append("    # Events wait on disk while LoggingX is unreachable and survive restarts and power loss; when\n")
                .append("    # the buffer is full the sources pause and unread lines stay in the log files.\n")
                .append("    buffer:\n")
                .append("      type: disk\n")
                .append("      max_size: ").append(DISK_BUFFER_BYTES).append('\n')
                .append("      when_full: block\n");
        if (metrics) {
            yaml.append(HostMetricsConfig.sink(store, options.serverUrl()));
        }
        return yaml.toString();
    }

    static String dataDir(Platform platform) {
        return switch (platform) {
            case LINUX -> DATA_DIR_LINUX;
            case DOCKER -> DATA_DIR_DOCKER;
            case KUBERNETES -> DATA_DIR_KUBERNETES;
            case WINDOWS -> DATA_DIR_WINDOWS;
        };
    }

    private static void appendMultiline(StringBuilder yaml, AgentOptions options, String indent) {
        String start;
        String condition;
        String mode;
        switch (options.multiline()) {
            case STACKTRACE -> {
                // An entry starts with an unindented line; indented lines ("at ...", "... 12 more"), "Caused by:"
                // and exception lines such as "java.net.SocketTimeoutException: Read timed out" continue it.
                start = "^[^\\s]";
                condition = "^(\\s+|Caused by:|Suppressed:|[\\w$.]+(Exception|Error|Throwable)\\b)";
                mode = "continue_through";
            }
            case TIMESTAMP -> {
                start = "^\\[?\\d{4}-\\d{2}-\\d{2}";
                condition = start;
                mode = "halt_before";
            }
            case CUSTOM -> {
                start = options.multilinePattern();
                condition = start;
                mode = "halt_before";
            }
            default -> {
                return;
            }
        }
        yaml.append(indent).append("# Merges continuation lines (e.g. stack traces) into the entry they belong to.\n")
                .append(indent).append("multiline:\n")
                .append(indent).append("  start_pattern: ").append(quote(start)).append('\n')
                .append(indent).append("  condition_pattern: ").append(quote(condition)).append('\n')
                .append(indent).append("  mode: ").append(mode).append('\n')
                .append(indent).append("  timeout_ms: 1000\n");
    }

    /**
     * The VRL program that turns a Vector event into a LoggingX log entry.
     */
    private static String remap(LogStore store, AgentOptions options) {
        StringBuilder vrl = new StringBuilder("""
                # Vector's own time, used when the line carries none.
                received = .timestamp
                del(.timestamp)
                del(.source_type)
                msg = to_string(.message) ?? ""
                .message = msg

                # JSON lines: their own fields (level, time, traceId, ...) are used as they are.
                if starts_with(msg, "{") {
                  parsed, err = parse_json(msg)
                  if err == null && is_object(parsed) {
                    . = merge(., object!(parsed))
                  }
                }

                # A level near the start of the line, e.g. "2026-10-23 10:00:00.123 ERROR [main] ...".
                if !exists(.level) {
                  head = slice(msg, 0, 160) ?? msg
                  found, err = parse_regex(head, r'\\b(?P<level>TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL|CRITICAL)\\b')
                  if err == null {
                    .level = found.level
                  }
                }

                # A timestamp at the start of the line; without a zone it is in the agent host's time zone.
                if !exists(.time) && !exists(.timestamp) && !exists(."@timestamp") {
                  found, err = parse_regex(msg, r'^\\[?(?P<at>\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?)(?P<zone>Z|[+-]\\d{2}:?\\d{2})?')
                  if err == null {
                    at = replace(replace(to_string(found.at), ",", "."), "T", " ")
                    zone = to_string(found.zone)
                    parsed_at, err = parse_timestamp(at + zone, "%Y-%m-%d %H:%M:%S%.f%#z")
                    if err != null {
                      parsed_at, err = parse_timestamp(at, "%Y-%m-%d %H:%M:%S%.f", timezone: "local")
                    }
                    if err == null {
                      .time = format_timestamp!(parsed_at, format: "%+")
                    }
                  }
                  if !exists(.time) {
                    .time = format_timestamp!(timestamp(received) ?? now(), format: "%+")
                  }
                }
                """);
        switch (options.platform()) {
            case KUBERNETES -> vrl.append("""

                    # Pod logs: the pod is the host, namespace/container the source.
                    .host = .kubernetes.pod_name
                    .source = join!(compact([.kubernetes.pod_namespace, .kubernetes.container_name]), "/")
                    .namespace = .kubernetes.pod_namespace
                    .node = .kubernetes.pod_node_name
                    if exists(.file) {
                      .source = del(.file)
                      .seq = del(.offset)
                    }
                    del(.kubernetes)
                    """);
            case DOCKER -> vrl.append("""

                    # Container output: the container is the source. File lines: the host path is the source.
                    if exists(.container_name) {
                      .source = .container_name
                      del(.container_id)
                      del(.container_created_at)
                      del(.label)
                    } else {
                      .source = replace(to_string(.file) ?? "", "%s", "", 1)
                      .seq = .offset
                    }
                    del(.file)
                    del(.offset)
                    """.formatted(DOCKER_HOST_ROOT));
            default -> vrl.append("""

                    .source = del(.file)
                    .seq = del(.offset)
                    """);
        }
        if (options.transport() == Transport.KAFKA) {
            vrl.append("\n# The log store LoggingX files these logs under.\n")
                    .append(".logstore = ").append(vrlString(store.code())).append('\n');
        }
        return vrl.toString();
    }

    private static String inputs(List<String> sources) {
        return sources.stream().collect(Collectors.joining(", ", "[", "]"));
    }

    private static String indent(String text, String prefix) {
        return text.lines().map(line -> line.isEmpty() ? "" : prefix + line)
                .collect(Collectors.joining("\n", "", "\n"));
    }

    /**
     * @return a single-quoted YAML scalar, in which only {@code '} needs escaping (backslashes stay literal)
     */
    static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String vrlString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
