package com.loggingx;

import com.loggingx.biz.monitor.AlertService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Host monitoring end to end: agents report Vector {@code host_metrics} events, which become the host list, trends,
 * file systems, processes and containers, and alerts.
 */
class MonitorApiIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AlertService alertService;

    /**
     * One scrape of a two-CPU host, 25 seconds after the previous one: CPU 50% busy, memory 75% used, disk sda read
     * at 1 MB/s, eth0 receiving 100 kB/s, / 90% full, and a Docker container using half a core.
     */
    private static String scrape(String host, Instant time, int index) {
        List<String> events = new ArrayList<>();
        for (int cpu = 0; cpu < 2; cpu++) {
            events.add(counter("cpu_seconds_total", host, time, "\"cpu\":\"" + cpu + "\",\"mode\":\"idle\"",
                    1000 + 12.5 * index));
            events.add(counter("cpu_seconds_total", host, time, "\"cpu\":\"" + cpu + "\",\"mode\":\"user\"",
                    500 + 12.5 * index));
        }
        events.add(gauge("logical_cpus", host, time, "", 2));
        events.add(gauge("memory_total_bytes", host, time, "", 8e9));
        events.add(gauge("memory_available_bytes", host, time, "", 2e9));
        events.add(gauge("load1", host, time, "", 4));
        events.add(gauge("uptime", host, time, "", 3600 + 25 * index));
        events.add(counter("disk_read_bytes_total", host, time, "\"device\":\"sda\"", 1e9 + 25e6 * index));
        // Partitions and loopback would count twice; they are skipped.
        events.add(counter("disk_read_bytes_total", host, time, "\"device\":\"sda1\"", 9e12 * (index + 1)));
        events.add(counter("network_receive_bytes_total", host, time, "\"device\":\"eth0\"", 5e8 + 25e5 * index));
        events.add(counter("network_receive_bytes_total", host, time, "\"device\":\"lo\"", 9e12 * (index + 1)));
        events.add(gauge("filesystem_total_bytes", host, time, "\"mountpoint\":\"/\",\"filesystem\":\"ext4\"", 100e9));
        events.add(gauge("filesystem_used_bytes", host, time, "\"mountpoint\":\"/\",\"filesystem\":\"ext4\"", 90e9));
        events.add(gauge("filesystem_total_bytes", host, time, "\"mountpoint\":\"/run\",\"filesystem\":\"tmpfs\"", 1e9));
        events.add(gauge("filesystem_used_bytes", host, time, "\"mountpoint\":\"/run\",\"filesystem\":\"tmpfs\"", 1e9));
        events.add(gauge("process_cpu_usage", host, time, "\"pid\":\"100\",\"name\":\"java\"", 30));
        events.add(gauge("process_memory_usage", host, time, "\"pid\":\"100\",\"name\":\"java\"", 1e9));
        events.add(gauge("process_cpu_usage", host, time, "\"pid\":\"200\",\"name\":\"nginx\"", 1));
        String container = "/system.slice/docker-0123456789abcdef0123456789abcdef.scope";
        events.add(counter("cgroup_cpu_usage_seconds_total", host, time, "\"cgroup\":\"" + container + "\"",
                100 + 12.5 * index));
        events.add(gauge("cgroup_memory_current_bytes", host, time, "\"cgroup\":\"" + container + "\"", 2e8));
        events.add(counter("cgroup_cpu_usage_seconds_total", host, time, "\"cgroup\":\"/system.slice\"",
                900 + 50 * index));
        return String.join("\n", events);
    }

    private static String counter(String name, String host, Instant time, String tags, double value) {
        return event(name, host, time, tags, "counter", value);
    }

    private static String gauge(String name, String host, Instant time, String tags, double value) {
        return event(name, host, time, tags, "gauge", value);
    }

    private static String event(String name, String host, Instant time, String tags, String kind, double value) {
        return String.format(Locale.ROOT, "{\"name\":\"%s\",\"host\":\"%s\",\"timestamp\":\"%s\",\"tags\":{%s},"
                + "\"%s\":{\"value\":%s}}", name, host, time, tags, kind, value);
    }

    private HttpResponse<String> ingest(String token, String body, boolean gzip) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri("/api/metrics/ingest"))
                .header("X-Log-Token", token);
        if (gzip) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (GZIPOutputStream out = new GZIPOutputStream(bytes)) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            request.header("Content-Encoding", "gzip").POST(HttpRequest.BodyPublishers.ofByteArray(bytes.toByteArray()));
        } else {
            request.POST(HttpRequest.BodyPublishers.ofString(body));
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static double number(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\":(-?[0-9.Ee+-]+)").matcher(json);
        assertTrue(matcher.find(), field + " in " + json);
        return Double.parseDouble(matcher.group(1));
    }

    @Test
    void agentsReportHostMetricsThatBecomeTrendsAndAlerts() throws Exception {
        String token = login("super", DEMO_PASSWORD);
        String created = send("POST", "/api/logstore", token,
                "{\"code\":\"it-monitor\",\"name\":\"Monitor\",\"retentionDays\":7,\"enabled\":true}").body();
        String storeId = extract(created, "\"storeId\":(\\d+)");
        String ingestToken = extract(created, "\"ingestToken\":\"([^\"]+)\"");

        assertEquals(401, ingest("lx_wrong", "{}", false).statusCode());
        Instant now = Instant.now();
        for (int i = 0; i < 3; i++) {
            HttpResponse<String> response = ingest(ingestToken, scrape("Web-01", now.minusSeconds(50 - 25L * i), i),
                    i == 1);
            assertEquals(200, response.statusCode(), response.body());
            assertTrue(response.body().contains("\"samples\":1"), response.body());
        }

        // Current values come from the latest two scrapes.
        String hosts = send("GET", "/api/monitor/hosts?storeId=" + storeId, token, null).body();
        assertTrue(hosts.contains("\"host\":\"web-01\""), "host names are matched ignoring case: " + hosts);
        assertEquals(50, number(hosts, "cpuPercent"), 0.01);
        assertEquals(75, number(hosts, "memPercent"), 0.01);
        assertEquals(90, number(hosts, "diskMaxPercent"), 0.01);
        assertEquals(1e6, number(hosts, "diskReadBps"), 1);
        assertEquals(1e5, number(hosts, "netRxBps"), 1);

        String series = send("GET", "/api/monitor/hosts/series?storeId=" + storeId + "&host=web-01&from="
                + now.minusSeconds(3600).toEpochMilli() + "&to=" + now.plusSeconds(60).toEpochMilli(), token, null)
                .body();
        assertEquals(50, number(series.substring(series.lastIndexOf("{\"time\"")), "cpuPercent"), 0.01);

        String filesystems = send("GET", "/api/monitor/hosts/filesystems?storeId=" + storeId + "&host=web-01",
                token, null).body();
        assertTrue(filesystems.contains("\"mountpoint\":\"/\""), filesystems);
        assertFalse(filesystems.contains("/run"), "pseudo file systems are skipped");

        String processes = send("GET", "/api/monitor/hosts/processes?storeId=" + storeId + "&host=web-01", token,
                null).body();
        assertTrue(processes.indexOf("\"java\"") < processes.indexOf("\"nginx\""), "busiest first: " + processes);

        String containers = send("GET", "/api/monitor/hosts/containers?storeId=" + storeId + "&host=web-01", token,
                null).body();
        assertTrue(containers.contains("\"kind\":\"docker\",\"name\":\"0123456789ab\""), containers);
        assertEquals(50, number(containers, "cpuPercent"), 0.01);
        assertFalse(containers.contains("\"cgroup\":\"/system.slice\""), "parent groups are not listed");

        // The default disk rule (above 85%) and a CPU rule for this store fire; load (2 per core) stays at its limit.
        String rule = send("POST", "/api/monitor/alert-rules", token, "{\"name\":\"CPU above 40%\",\"metric\":\"cpu\","
                + "\"threshold\":40,\"durationMinutes\":1,\"severity\":\"critical\",\"storeId\":" + storeId + "}")
                .body();
        String ruleId = extract(rule, "\"id\":(\\d+)");
        alertService.evaluate(Instant.now());
        String firing = send("GET", "/api/monitor/alerts?status=firing&storeId=" + storeId, token, null).body();
        assertTrue(firing.contains("\"metric\":\"disk\""), firing);
        assertTrue(firing.contains("\"ruleName\":\"CPU above 40%\""), firing);
        assertFalse(firing.contains("\"metric\":\"load\""), firing);
        assertTrue(send("GET", "/api/monitor/hosts?storeId=" + storeId, token, null).body()
                .contains("\"alerts\":2,\"alertSeverity\":\"critical\""));

        // Disabling the rule resolves its alert.
        assertEquals(200, send("PUT", "/api/monitor/alert-rules/" + ruleId, token, "{\"name\":\"CPU above 40%\","
                + "\"metric\":\"cpu\",\"threshold\":40,\"durationMinutes\":1,\"severity\":\"critical\",\"storeId\":"
                + storeId + ",\"enabled\":false}").statusCode());
        alertService.evaluate(Instant.now());
        String resolved = send("GET", "/api/monitor/alerts?status=resolved&storeId=" + storeId, token, null).body();
        assertTrue(resolved.contains("\"ruleName\":\"CPU above 40%\""), resolved);
        assertEquals(200, send("DELETE", "/api/monitor/alert-rules/" + ruleId, token, null).statusCode());

        assertEquals(400, send("POST", "/api/monitor/alert-rules", token, "{\"name\":\"bad\",\"metric\":\"cpu\","
                + "\"threshold\":150,\"durationMinutes\":5,\"severity\":\"warning\"}").statusCode());
        assertEquals(200, send("DELETE", "/api/logstore/" + storeId, token, null).statusCode());
    }

    @Test
    void monitoringIsPartOfTheCollectConfigAgentsSync() throws Exception {
        String token = login("super", DEMO_PASSWORD);
        String created = send("POST", "/api/logstore", token,
                "{\"code\":\"it-monitor-config\",\"name\":\"Monitor\",\"retentionDays\":7,\"enabled\":true}").body();
        String storeId = extract(created, "\"storeId\":(\\d+)");
        String collect = send("GET", "/api/agent/collect?storeId=" + storeId, token, null).body();
        assertTrue(collect.contains("\"monitor\":{\"enabled\":true,\"intervalSeconds\":15"), collect);

        HttpResponse<String> config = send("GET", "/api/agent/config?platform=linux&storeId=" + storeId, token, null);
        assertTrue(config.body().contains("type: host_metrics"), config.body());
        assertEquals(200, send("PUT", "/api/agent/collect?storeId=" + storeId, token,
                "{\"paths\":[\"/var/log/a.log\"],\"windowsPaths\":[],\"multiline\":\"none\",\"transport\":\"http\","
                        + "\"monitor\":{\"enabled\":false,\"intervalSeconds\":30,\"processes\":false,"
                        + "\"containers\":false}}").statusCode());
        assertFalse(send("GET", "/api/agent/config?platform=linux&storeId=" + storeId, token, null).body()
                .contains("host_metrics"));
        assertEquals(400, send("PUT", "/api/agent/collect?storeId=" + storeId, token,
                "{\"paths\":[\"/var/log/a.log\"],\"multiline\":\"none\",\"transport\":\"http\","
                        + "\"monitor\":{\"enabled\":true,\"intervalSeconds\":1}}").statusCode());

        String userToken = login("user", DEMO_PASSWORD);
        assertEquals(200, send("GET", "/api/monitor/hosts", userToken, null).statusCode(), "users view hosts");
        assertEquals(403, send("POST", "/api/monitor/alert-rules", userToken, "{}").statusCode(),
                "only alert managers change rules");
        assertEquals(200, send("DELETE", "/api/logstore/" + storeId, token, null).statusCode());
    }
}
