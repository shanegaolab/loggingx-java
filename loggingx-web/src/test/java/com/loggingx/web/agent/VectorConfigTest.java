package com.loggingx.web.agent;

import com.loggingx.common.exception.BadRequestException;
import com.loggingx.dao.agent.CollectConfig;
import com.loggingx.dao.log.LogStore;
import com.loggingx.dao.monitor.MonitorConfig;
import com.loggingx.web.agent.AgentOptions.Platform;
import com.loggingx.web.agent.VectorConfig.Rendered;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the generated configs and writes one per platform to {@code target/agent-samples}, where they can be checked
 * with a real Vector: {@code vector validate --no-environment target/agent-samples/linux-http.yaml}.
 */
class VectorConfigTest {

    private static final LogStore STORE = new LogStore(1L, "order-service", "Orders", null, 7, "lx_test-token", true,
            Instant.now(), Instant.now());
    private static final Path SAMPLES = Path.of("target", "agent-samples");
    private static final MonitorConfig NO_METRICS = new MonitorConfig(1L, false, 15, false, false, null);

    private static AgentOptions options(String platform, String transport, List<String> paths, String multiline,
                                        String pattern) {
        return AgentOptions.of(platform, transport, paths, null, multiline, pattern, "http://localhost:8080",
                "kafka-1:9092,kafka-2:9092", "loggingx-logs");
    }

    private static String renderSample(String name, AgentOptions options) throws IOException {
        String yaml = VectorConfig.render(STORE, options);
        Files.createDirectories(SAMPLES);
        Files.writeString(SAMPLES.resolve(name + ".yaml"), yaml, StandardCharsets.UTF_8);
        return yaml;
    }

    @Test
    void rendersHttpConfigForLinuxFiles() throws IOException {
        String yaml = renderSample("linux-http", options("linux", "http", List.of("/var/log/app/*.log",
                "/var/log/nginx/error.log"), "stacktrace", null));

        assertTrue(yaml.contains("type: file"));
        assertTrue(yaml.contains("- '/var/log/app/*.log'"));
        assertTrue(yaml.contains("offset_key: offset"));
        assertTrue(yaml.contains("mode: continue_through"));
        assertTrue(yaml.contains("uri: 'http://localhost:8080/api/logs/ingest'"));
        assertTrue(yaml.contains("X-Log-Token: 'lx_test-token'"));
        assertTrue(yaml.contains("max_events: 1000"));
        assertFalse(yaml.contains("logstore"), "HTTP batches are filed by the token");

        // At least once: files rotated in while the agent was down are read from the start, checkpoints wait for
        // the disk buffer, failed requests are retried, and the ingest API drops retried batches.
        assertTrue(yaml.contains("read_from: beginning"));
        assertTrue(yaml.contains("ignore_older_secs: 86400"));
        assertTrue(yaml.contains("acknowledgements:\n      enabled: true"));
        assertTrue(yaml.contains("type: disk\n      max_size: 1073741824\n      when_full: block"));
        assertTrue(yaml.contains("retry_max_duration_secs: 60"));
        assertTrue(yaml.contains("X-Log-Dedup: batch"));
    }

    @Test
    void rendersKafkaConfigWithTheStoreCode() throws IOException {
        String yaml = renderSample("linux-kafka", options("linux", "kafka", List.of("/var/log/app/*.log"),
                "timestamp", null));

        assertTrue(yaml.contains("type: kafka"));
        assertTrue(yaml.contains("bootstrap_servers: 'kafka-1:9092,kafka-2:9092'"));
        assertTrue(yaml.contains("key_field: host"));
        assertTrue(yaml.contains(".logstore = \"order-service\""));
        assertFalse(yaml.contains("lx_test-token"), "Kafka needs no token");
    }

    @Test
    void rendersDockerKubernetesAndWindowsConfigs() throws IOException {
        String docker = renderSample("docker-http", options("docker", "http", List.of("/var/log/app/*.log"),
                "custom", "^\\d{4}-"));
        assertTrue(docker.contains("type: docker_logs"));
        assertTrue(docker.contains("- '/host/var/log/app/*.log'"));
        assertTrue(docker.contains("start_pattern: '^\\d{4}-'"));

        String kubernetes = renderSample("kubernetes-http", options("kubernetes", "http",
                List.of("/ignored/*.log"), "none", null));
        assertTrue(kubernetes.contains("type: kubernetes_logs"));
        assertTrue(kubernetes.contains("X-Log-Token: 'lx_test-token'"));
        assertFalse(kubernetes.contains("/ignored"));

        String windows = renderSample("windows-http", options("windows", "http",
                List.of("C:\\logs\\app\\*.log"), "none", null));
        assertTrue(windows.contains("data_dir: 'C:\\ProgramData\\LoggingX Agent\\data'"));
        assertTrue(windows.contains("- 'C:\\logs\\app\\*.log'"));
    }

    @Test
    void rejectsUnsafeOrIncompleteOptions() {
        assertThrows(BadRequestException.class, () -> options("linux", "http", List.of(), "none", null));
        assertThrows(BadRequestException.class, () -> options("linux", "http", List.of("relative/*.log"), "none",
                null));
        assertThrows(BadRequestException.class, () -> options("linux", "http", List.of("/var/log/$(reboot)"),
                "none", null));
        assertThrows(BadRequestException.class, () -> options("linux", "http", List.of("/var/log/a'b"), "none",
                null));
        assertThrows(BadRequestException.class, () -> options("linux", "http", List.of("/a.log"), "custom", "("));
        assertThrows(BadRequestException.class, () -> options("solaris", "http", List.of("/a.log"), "none", null));
        assertThrows(BadRequestException.class, () -> AgentOptions.of("linux", "http", List.of("/a.log"), null,
                "none", null, "ftp://x", null, null));
        assertThrows(BadRequestException.class, () -> AgentOptions.of("linux", "kafka", List.of("/a.log"), null,
                "none", null, null, "not a server", "t"));
    }

    @Test
    void hostMonitoringAddsMetricSourcesAndASink() throws IOException {
        CollectConfig collect = CollectConfig.defaults(1L);
        MonitorConfig monitor = new MonitorConfig(1L, true, 15, true, true, null);
        Files.createDirectories(SAMPLES);
        for (Platform platform : Platform.values()) {
            String yaml = VectorConfig.render(STORE, collect, monitor, platform, "http://localhost:8080").text();
            Files.writeString(SAMPLES.resolve("metrics-" + platform.name().toLowerCase() + ".yaml"), yaml,
                    StandardCharsets.UTF_8);
            assertTrue(yaml.contains("type: host_metrics"), yaml);
            assertTrue(yaml.contains("collectors: [process]"), "busy processes are scraped separately");
            assertTrue(yaml.contains("uri: 'http://localhost:8080/api/metrics/ingest'"));
            assertTrue(yaml.contains("compression: gzip"));
            assertTrue(yaml.contains("when_full: drop_newest"), "metrics never block log collection");
            assertTrue(yaml.contains("cgroups") == (platform != Platform.WINDOWS), platform + ": " + yaml);
            assertTrue(yaml.contains("VECTOR_SELF_NODE_NAME") == (platform == Platform.KUBERNETES));
        }

        MonitorConfig quiet = new MonitorConfig(1L, true, 60, false, false, null);
        String yaml = VectorConfig.render(STORE, collect, quiet, Platform.LINUX, "http://localhost:8080").text();
        assertTrue(yaml.contains("scrape_interval_secs: 60"));
        assertFalse(yaml.contains("host_processes"));
        assertFalse(yaml.contains("cgroups"));
        String off = VectorConfig.render(STORE, collect, NO_METRICS, Platform.LINUX, "http://localhost:8080").text();
        assertFalse(off.contains("host_metrics"));
        assertNotEquals(VectorConfig.revision(yaml), VectorConfig.revision(off), "toggling monitoring is synced");
    }

    @Test
    void collectConfigsRenderPerPlatformWithARevision() {
        CollectConfig collect = AgentOptions.validate(new CollectConfig(1L, List.of(" /var/log/app/*.log ", ""),
                List.of("C:\\logs\\*.log"), true, "STACKTRACE", null, "Http", null, null, null));
        assertEquals(List.of("/var/log/app/*.log"), collect.paths());
        assertEquals("stacktrace", collect.multiline());
        assertEquals("http", collect.transport());

        Rendered linux = VectorConfig.render(STORE, collect, NO_METRICS, Platform.LINUX, "http://localhost:8080");
        Rendered windows = VectorConfig.render(STORE, collect, NO_METRICS, Platform.WINDOWS, "http://localhost:8080");
        assertTrue(linux.text().startsWith("# loggingx-revision: " + linux.revision() + "\n"));
        assertTrue(linux.revision().matches("[a-f0-9]{16}"));
        assertTrue(linux.text().contains("- '/var/log/app/*.log'"));
        assertTrue(windows.text().contains("- 'C:\\logs\\*.log'"));
        assertNotEquals(linux.revision(), windows.revision());
        assertEquals(linux.revision(), VectorConfig.render(STORE, collect, NO_METRICS, Platform.LINUX,
                "http://localhost:8080").revision(), "the same inputs give the same revision");

        // A new token or server address changes the config, so agents fetch it.
        LogStore rotated = new LogStore(1L, "order-service", "Orders", null, 7, "lx_new-token", true,
                Instant.now(), Instant.now());
        assertNotEquals(linux.revision(), VectorConfig.render(rotated, collect, NO_METRICS, Platform.LINUX,
                "http://localhost:8080").revision());
        assertNotEquals(linux.revision(), VectorConfig.render(STORE, collect, NO_METRICS, Platform.LINUX,
                "https://logs.example.com").revision());

        CollectConfig linuxOnly = new CollectConfig(1L, collect.paths(), List.of(), true, "none", null, "http",
                null, null, null);
        assertThrows(BadRequestException.class, () -> VectorConfig.render(STORE, linuxOnly, NO_METRICS, Platform.WINDOWS,
                "http://localhost:8080"));
        assertTrue(VectorConfig.render(STORE, linuxOnly, NO_METRICS, Platform.KUBERNETES, "http://localhost:8080").text()
                .contains("kubernetes_logs"));
        assertThrows(BadRequestException.class, () -> AgentOptions.validate(new CollectConfig(1L,
                List.of("relative.log"), List.of(), true, "none", null, "http", null, null, null)));
        assertThrows(BadRequestException.class, () -> AgentOptions.validate(new CollectConfig(1L,
                List.of(), List.of("/not/windows"), true, "none", null, "http", null, null, null)));
        assertThrows(BadRequestException.class, () -> AgentOptions.validate(new CollectConfig(1L,
                List.of(), List.of(), true, "none", null, "kafka", "", "t", null)));
    }
}
