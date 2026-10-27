package com.loggingx.biz.monitor;

import com.loggingx.biz.agent.AgentService;
import com.loggingx.common.exception.BadRequestException;
import com.loggingx.common.exception.NotFoundException;
import com.loggingx.dao.agent.LogAgent;
import com.loggingx.dao.agent.LogAgentRepository;
import com.loggingx.dao.log.LogStoreRepository;
import com.loggingx.dao.monitor.AlertEvent;
import com.loggingx.dao.monitor.ContainerSample;
import com.loggingx.dao.monitor.FilesystemSample;
import com.loggingx.dao.monitor.FilesystemUsage;
import com.loggingx.dao.monitor.HostMetricRepository;
import com.loggingx.dao.monitor.HostSample;
import com.loggingx.dao.monitor.MonitorConfig;
import com.loggingx.dao.monitor.MonitorMapper;
import com.loggingx.dao.monitor.ProcessSample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Host resource monitoring: stores the metrics agents report and serves the host list, trend series, file systems,
 * processes and containers. A host is identified by its store and lower-case host name, which matches the host name
 * its agent registered with.
 */
@Service
public class MonitorService {

    /** A host whose latest sample is older than this is shown without current values. */
    static final Duration CURRENT = Duration.ofMinutes(5);
    /** Trend charts have about this many points. */
    private static final int SERIES_POINTS = 240;
    private static final List<Duration> BUCKETS = List.of(Duration.ofSeconds(15), Duration.ofSeconds(30),
            Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(5), Duration.ofMinutes(10),
            Duration.ofMinutes(15), Duration.ofMinutes(30), Duration.ofHours(1), Duration.ofHours(2));
    private static final Duration MAX_RANGE = Duration.ofDays(31);
    private static final int PROCESS_RETENTION_DAYS = 3;
    private static final Pattern DOCKER = Pattern.compile(".*/docker-([0-9a-f]{12})[0-9a-f]*\\.scope$");
    private static final Pattern KUBERNETES = Pattern.compile(
            ".*/kubepods.*?pod([0-9a-f_-]+)\\.slice/(?:cri-containerd|crio|docker)-([0-9a-f]{12})[0-9a-f]*\\.scope$");
    private static final Pattern SERVICE = Pattern.compile(".*/([^/]+\\.service)$");
    private static final Logger log = LoggerFactory.getLogger(MonitorService.class);

    private final HostMetricRepository metricRepository;
    private final MonitorMapper monitorMapper;
    private final LogAgentRepository agentRepository;
    private final LogStoreRepository logStoreRepository;
    private final int retentionDays;

    public MonitorService(HostMetricRepository metricRepository, MonitorMapper monitorMapper,
                          LogAgentRepository agentRepository, LogStoreRepository logStoreRepository,
                          @Value("${loggingx.monitor.retention-days:15}") int retentionDays) {
        this.metricRepository = metricRepository;
        this.monitorMapper = monitorMapper;
        this.agentRepository = agentRepository;
        this.logStoreRepository = logStoreRepository;
        this.retentionDays = retentionDays;
    }

    // ---------- config ----------

    /**
     * @return the store's monitoring config, or the defaults when it has not saved one
     */
    public MonitorConfig config(Long storeId) {
        MonitorConfig config = monitorMapper.selectConfig(storeId);
        return config != null ? config : MonitorConfig.defaults(storeId);
    }

    public MonitorConfig saveConfig(MonitorConfig config) {
        if (config.intervalSeconds() < MonitorConfig.MIN_INTERVAL_SECONDS
                || config.intervalSeconds() > MonitorConfig.MAX_INTERVAL_SECONDS) {
            throw new BadRequestException("The monitoring interval must be between "
                    + MonitorConfig.MIN_INTERVAL_SECONDS + " and " + MonitorConfig.MAX_INTERVAL_SECONDS + " seconds");
        }
        MonitorConfig saved = new MonitorConfig(config.storeId(), config.enabled(), config.intervalSeconds(),
                config.processes(), config.containers(), Instant.now());
        if (monitorMapper.updateConfig(saved) == 0) {
            try {
                monitorMapper.insertConfig(saved);
            } catch (DuplicateKeyException e) {
                monitorMapper.updateConfig(saved);
            }
        }
        return saved;
    }

    // ---------- ingest ----------

    /**
     * Stores the metrics an agent of the store reported.
     *
     * @return the number of host samples stored
     */
    public int ingest(Long storeId, List<MetricPoint> points) {
        HostSnapshots.Batch batch = HostSnapshots.build(storeId, points);
        metricRepository.insert(batch.hosts(), batch.filesystems(), List.of(), batch.containers(), retentionDays);
        if (!batch.processes().isEmpty()) {
            metricRepository.insert(List.of(), List.of(), batch.processes(), List.of(),
                    Math.min(retentionDays, PROCESS_RETENTION_DAYS));
        }
        return batch.hosts().size();
    }

    public void deleteStore(Long storeId) {
        try {
            metricRepository.deleteByStore(storeId);
        } catch (RuntimeException e) {
            log.warn("Cannot delete the host metrics of deleted store {}; they expire with their retention", storeId,
                    e);
        }
    }

    // ---------- reads ----------

    /**
     * @param storeId the store, or {@code null} for the hosts of all stores
     * @return every host with an agent or with recent metrics, those with alerts first
     */
    public List<HostStatus> hosts(Long storeId) {
        Instant now = Instant.now();
        Instant since = now.minus(CURRENT);
        Map<String, List<HostSample>> pairs = metricRepository.latestPairs(since).stream()
                .filter(sample -> storeId == null || storeId.equals(sample.storeId()))
                .collect(Collectors.groupingBy(sample -> key(sample.storeId(), sample.host()), LinkedHashMap::new,
                        Collectors.toList()));
        Map<String, FilesystemSample> fullest = metricRepository.latestFilesystems(since).stream()
                .filter(sample -> storeId == null || storeId.equals(sample.storeId()))
                .collect(Collectors.toMap(sample -> key(sample.storeId(), sample.host()), Function.identity(),
                        (a, b) -> a.usedPercent() >= b.usedPercent() ? a : b));
        Map<String, List<AlertEvent>> alerts = monitorMapper.selectFiringEvents().stream()
                .collect(Collectors.groupingBy(event -> key(event.storeId(), event.hostname())));

        Map<String, HostStatus> hosts = new LinkedHashMap<>();
        for (LogAgent agent : agentRepository.findByStore(storeId)) {
            String key = key(agent.storeId(), agent.hostname());
            if (hosts.containsKey(key) && hosts.get(key).online()) {
                continue;
            }
            hosts.put(key, status(agent.storeId(), agent.hostname().toLowerCase(Locale.ROOT), agent,
                    pairs.get(key), fullest.get(key), alerts.get(key)));
        }
        pairs.forEach((key, samples) -> {
            if (!hosts.containsKey(key)) {
                HostSample latest = samples.get(0);
                hosts.put(key, status(latest.storeId(), latest.host(), null, samples, fullest.get(key),
                        alerts.get(key)));
            }
        });
        return hosts.values().stream()
                .sorted(Comparator.comparing((HostStatus host) -> host.alerts() == 0)
                        .thenComparing(host -> !host.online())
                        .thenComparing(HostStatus::hostname))
                .toList();
    }

    /**
     * @return the host's trend over {@code [from, to)}, about {@value #SERIES_POINTS} points
     */
    public List<SeriesPoint> series(Long storeId, String host, Instant from, Instant to) {
        if (!to.isAfter(from) || Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw new BadRequestException("The time range must be positive and at most 31 days");
        }
        String key = host.toLowerCase(Locale.ROOT);
        Duration bucket = bucket(Duration.between(from, to));
        // One bucket before the range gives the first point its rate.
        List<HostSample> samples = metricRepository.series(storeId, key, from.minus(bucket), to, bucket);
        Map<Long, Double> disks = metricRepository.filesystemSeries(storeId, key, from, to, bucket).stream()
                .collect(Collectors.toMap(usage -> usage.time().toEpochMilli() / bucket.toMillis(),
                        FilesystemUsage::maxUsedPercent, Math::max));
        List<SeriesPoint> points = new ArrayList<>();
        HostSample previous = null;
        for (HostSample sample : samples) {
            if (!sample.time().isBefore(from)) {
                // A gap of more than three buckets (agent stopped) leaves the rates empty.
                HostSample base = previous != null
                        && Duration.between(previous.time(), sample.time()).compareTo(bucket.multipliedBy(3)) <= 0
                        ? previous : null;
                long index = sample.time().toEpochMilli() / bucket.toMillis();
                points.add(new SeriesPoint(Instant.ofEpochMilli(index * bucket.toMillis()), cpuPercent(base, sample),
                        percent(sample.memUsed(), sample.memTotal()), percent(sample.swapUsed(), sample.swapTotal()),
                        sample.load1(), sample.load5(), sample.load15(),
                        rate(base, sample, HostSample::diskReadBytes), rate(base, sample, HostSample::diskWriteBytes),
                        rate(base, sample, HostSample::netRxBytes), rate(base, sample, HostSample::netTxBytes),
                        disks.get(index)));
            }
            previous = sample;
        }
        return points;
    }

    /**
     * @return the file systems of the host's latest scrape, fullest first
     */
    public List<FilesystemSample> filesystems(Long storeId, String host) {
        String key = host.toLowerCase(Locale.ROOT);
        return metricRepository.latestFilesystems(Instant.now().minus(CURRENT)).stream()
                .filter(sample -> storeId.equals(sample.storeId()) && key.equals(sample.host()))
                .sorted(Comparator.comparingDouble(FilesystemSample::usedPercent).reversed())
                .toList();
    }

    /**
     * @param at the time to show the processes at; the latest scrape at or before it is used
     * @return the busiest processes of that scrape, by CPU
     */
    public List<ProcessSample> processes(Long storeId, String host, Instant at) {
        return metricRepository.processesAt(storeId, host.toLowerCase(Locale.ROOT), at, Duration.ofMinutes(10))
                .stream()
                .sorted(Comparator.comparing((ProcessSample sample) -> sample.cpuPercent() == null ? -1
                        : sample.cpuPercent()).reversed())
                .toList();
    }

    /**
     * @return the host's containers and services with their current use, busiest first
     */
    public List<ContainerUsage> containers(Long storeId, String host) {
        List<ContainerSample> samples = metricRepository.latestContainers(storeId, host.toLowerCase(Locale.ROOT),
                Instant.now().minus(CURRENT));
        if (samples.isEmpty()) {
            return List.of();
        }
        Instant latest = samples.stream().map(ContainerSample::time).max(Comparator.naturalOrder()).orElseThrow();
        Map<String, ContainerSample> previous = new HashMap<>();
        List<ContainerSample> current = new ArrayList<>();
        for (ContainerSample sample : samples) {
            if (sample.time().equals(latest)) {
                current.add(sample);
            } else {
                previous.put(sample.cgroup(), sample);
            }
        }
        List<String> paths = current.stream().map(ContainerSample::cgroup).toList();
        List<ContainerUsage> usages = new ArrayList<>();
        for (ContainerSample sample : current) {
            String path = sample.cgroup();
            // Parent groups (system.slice, kubepods) add up their children; only the leaves are listed.
            if ("/".equals(path) || paths.stream().anyMatch(other -> other.startsWith(path + "/"))) {
                continue;
            }
            ContainerSample before = previous.get(path);
            Double cpu = null;
            if (before != null && before.cpuSeconds() != null && sample.cpuSeconds() != null
                    && sample.cpuSeconds() >= before.cpuSeconds()) {
                double seconds = Duration.between(before.time(), sample.time()).toMillis() / 1000.0;
                cpu = seconds > 0 ? (sample.cpuSeconds() - before.cpuSeconds()) * 100 / seconds : null;
            }
            String[] kindAndName = describe(path);
            usages.add(new ContainerUsage(path, kindAndName[0], kindAndName[1], cpu, sample.memBytes(),
                    sample.time()));
        }
        usages.sort(Comparator.comparing((ContainerUsage usage) -> usage.cpuPercent() == null ? -1
                : usage.cpuPercent()).reversed().thenComparing(usage -> usage.memBytes() == null ? 0
                : -usage.memBytes()));
        return usages;
    }

    public void requireStore(Long storeId) {
        if (storeId == null || logStoreRepository.findById(storeId).isEmpty()) {
            throw new NotFoundException("Log store was not found");
        }
    }

    // ---------- helpers ----------

    private HostStatus status(Long storeId, String host, LogAgent agent, List<HostSample> pair,
                              FilesystemSample fullest, List<AlertEvent> alerts) {
        HostSample latest = pair == null || pair.isEmpty() ? null : pair.get(0);
        HostSample previous = pair == null || pair.size() < 2 ? null : pair.get(1);
        boolean online = agent != null ? AgentService.isOnline(agent)
                : latest != null && latest.time().isAfter(Instant.now().minus(Duration.ofMinutes(2)));
        int alertCount = alerts == null ? 0 : alerts.size();
        String severity = alertCount == 0 ? null : alerts.stream().anyMatch(alert -> "critical".equals(
                alert.severity())) ? "critical" : "warning";
        return new HostStatus(storeId, host, agent != null ? agent.hostname() : host,
                agent == null ? null : agent.id(), agent == null ? null : agent.platform(),
                agent == null ? null : agent.version(), agent == null ? null : agent.ip(), online,
                agent == null ? (latest == null ? null : latest.time()) : agent.lastSeenAt(),
                latest == null ? null : latest.time(), cpuPercent(previous, latest),
                latest == null ? null : latest.cpuCores(), latest == null ? null : latest.memTotal(),
                latest == null ? null : latest.memUsed(),
                latest == null ? null : percent(latest.memUsed(), latest.memTotal()),
                latest == null ? null : latest.swapTotal(), latest == null ? null : latest.swapUsed(),
                latest == null ? null : latest.load1(), latest == null ? null : latest.load5(),
                latest == null ? null : latest.load15(),
                fullest == null ? null : fullest.usedPercent(), fullest == null ? null : fullest.mountpoint(),
                rate(previous, latest, HostSample::diskReadBytes), rate(previous, latest, HostSample::diskWriteBytes),
                rate(previous, latest, HostSample::netRxBytes), rate(previous, latest, HostSample::netTxBytes),
                latest == null ? null : latest.uptime(), alertCount, severity);
    }

    /**
     * @return the CPU use between two samples in percent, or {@code null} without two comparable samples
     */
    static Double cpuPercent(HostSample before, HostSample after) {
        if (before == null || after == null || before.cpuTotal() == null || after.cpuTotal() == null
                || before.cpuIdle() == null || after.cpuIdle() == null) {
            return null;
        }
        double total = after.cpuTotal() - before.cpuTotal();
        double idle = after.cpuIdle() - before.cpuIdle();
        if (total <= 0 || idle < 0) {
            return null;
        }
        return Math.max(0, Math.min(100, (1 - idle / total) * 100));
    }

    /**
     * @return the per-second increase of a counter between two samples, or {@code null} across a reset
     */
    static Double rate(HostSample before, HostSample after, Function<HostSample, Double> counter) {
        if (before == null || after == null) {
            return null;
        }
        Double start = counter.apply(before);
        Double end = counter.apply(after);
        double seconds = Duration.between(before.time(), after.time()).toMillis() / 1000.0;
        if (start == null || end == null || end < start || seconds <= 0) {
            return null;
        }
        return (end - start) / seconds;
    }

    static Double percent(Double used, Double total) {
        return used == null || total == null || total <= 0 ? null : used * 100 / total;
    }

    /**
     * @return the smallest bucket that keeps the chart within {@value #SERIES_POINTS} points
     */
    static Duration bucket(Duration range) {
        for (Duration bucket : BUCKETS) {
            if (range.toMillis() / bucket.toMillis() <= SERIES_POINTS) {
                return bucket;
            }
        }
        return BUCKETS.get(BUCKETS.size() - 1);
    }

    /**
     * @return kind and short name of a control group path
     */
    static String[] describe(String cgroup) {
        Matcher docker = DOCKER.matcher(cgroup);
        if (docker.matches()) {
            return new String[]{"docker", docker.group(1)};
        }
        Matcher kubernetes = KUBERNETES.matcher(cgroup);
        if (kubernetes.matches()) {
            return new String[]{"kubernetes", kubernetes.group(2)};
        }
        Matcher service = SERVICE.matcher(cgroup);
        if (service.matches()) {
            return new String[]{"service", service.group(1)};
        }
        int slash = cgroup.lastIndexOf('/');
        return new String[]{"other", slash >= 0 && slash < cgroup.length() - 1 ? cgroup.substring(slash + 1) : cgroup};
    }

    static String key(Long storeId, String host) {
        return storeId + "/" + Objects.requireNonNullElse(host, "").toLowerCase(Locale.ROOT);
    }
}
