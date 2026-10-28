package com.loggingx.web.dashboard;

import com.loggingx.biz.agent.AgentService;
import com.loggingx.biz.backup.BackupService;
import com.loggingx.biz.backup.BackupService.PolicyView;
import com.loggingx.biz.backup.BackupService.RunSummary;
import com.loggingx.biz.monitor.AlertService;
import com.loggingx.biz.monitor.LogAlertService;
import com.loggingx.biz.monitor.HostStatus;
import com.loggingx.biz.monitor.MonitorService;
import com.loggingx.biz.permission.PermissionService;
import com.loggingx.biz.permission.StoreAccessService;
import com.loggingx.biz.permission.StoreAccessService.Scope;
import com.loggingx.common.api.ApiResponse;
import com.loggingx.common.exception.BadRequestException;
import com.loggingx.common.util.DateTimes;
import com.loggingx.dao.agent.AgentCount;
import com.loggingx.dao.log.LevelBucket;
import com.loggingx.dao.log.LogRecord;
import com.loggingx.dao.log.LogRecordRepository;
import com.loggingx.dao.log.LogSearch;
import com.loggingx.dao.log.LogStore;
import com.loggingx.dao.log.LogStoreRepository;
import com.loggingx.dao.monitor.AlertEvent;
import com.loggingx.dao.monitor.LogAlertEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The console's overview of the whole platform: log volume and errors, agents, hosts, alerts and backups. Each part
 * is only included when the user may open the page it summarizes, so the console works for every role.
 */
@RestController
@RequestMapping("/dashboard")
public class DashboardController {

    /** Supported ranges in hours, and the bucket of each trend. */
    private static final Map<Integer, Duration> RANGES = Map.of(1, Duration.ofMinutes(2), 6, Duration.ofMinutes(15),
            24, Duration.ofHours(1), 168, Duration.ofHours(6));
    private static final Set<String> ERROR_LEVELS = Set.of("ERROR", "FATAL");
    private static final int RECENT_ERRORS = 8;
    private static final int TOP = 6;
    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    private final LogStoreRepository logStoreRepository;
    private final LogRecordRepository logRecordRepository;
    private final AgentService agentService;
    private final MonitorService monitorService;
    private final AlertService alertService;
    private final LogAlertService logAlertService;
    private final BackupService backupService;
    private final PermissionService permissionService;
    private final StoreAccessService storeAccessService;

    public DashboardController(LogStoreRepository logStoreRepository, LogRecordRepository logRecordRepository,
                               AgentService agentService, MonitorService monitorService, AlertService alertService,
                               LogAlertService logAlertService, BackupService backupService,
                               PermissionService permissionService, StoreAccessService storeAccessService) {
        this.logStoreRepository = logStoreRepository;
        this.logRecordRepository = logRecordRepository;
        this.agentService = agentService;
        this.monitorService = monitorService;
        this.alertService = alertService;
        this.logAlertService = logAlertService;
        this.backupService = backupService;
        this.permissionService = permissionService;
        this.storeAccessService = storeAccessService;
    }

    /**
     * @param from         start of the range, epoch milliseconds
     * @param bucketMillis width of a trend bucket
     */
    public record Overview(long from, long to, int hours, long bucketMillis, Logs logs, Agents agents, Hosts hosts,
                           Alerts alerts, Backup backup) {
    }

    /**
     * @param total          logs in the range
     * @param previousTotal  logs in the range before it, for the trend
     * @param errors         ERROR and FATAL logs in the range
     * @param levels         logs per level in the range
     * @param series         logs per bucket and level, oldest first, empty buckets included
     * @param available      whether the log storage answered; {@code false} leaves the counts empty
     */
    public record Logs(boolean available, long total, long previousTotal, long errors, long previousErrors,
                       Map<String, Long> levels, List<Point> series, int storeCount, int enabledStores,
                       List<StoreVolume> stores, List<ErrorLog> recentErrors) {
    }

    public record Point(long time, Map<String, Long> levels) {
    }

    public record StoreVolume(Long storeId, String code, String name, boolean enabled, long total, long errors) {
    }

    public record ErrorLog(String id, Long storeId, String storeName, String time, String level, String host,
                           String message) {
    }

    public record Agents(long total, long online) {
    }

    public record Hosts(int total, int online, int monitored, int alerting, List<HostUsage> top) {
    }

    public record HostUsage(Long storeId, String host, String hostname, boolean online, Double cpuPercent,
                            Double memPercent, Double diskMaxPercent, int alerts) {
    }

    public record Alerts(int firing, int critical, List<AlertItem> items) {
    }

    /**
     * @param type   {@code host} for a monitoring alert, {@code log} for a log alert
     * @param metric the host metric, or {@code log}
     * @param host   the host, or for a log alert the store name
     * @param value  the metric value, or for a log alert the matching logs in the window
     */
    public record AlertItem(String type, Long id, String ruleName, String metric, String severity, Long storeId,
                            String host, Double value, String startTime) {
    }

    /**
     * @param configured    whether the backup storage is set up
     * @param enabledStores stores with archiving on
     * @param pendingDays   days past their retention waiting for an archive, over all stores
     */
    public record Backup(boolean configured, int enabledStores, int storeCount, long archives, long archivedBytes,
                         long rawBytes, long failed, int pendingDays, boolean running, String lastRunTime,
                         Integer lastArchived, Integer lastFailed, String lastError) {
    }

    @GetMapping("/overview")
    public ApiResponse<Overview> overview(@RequestParam(defaultValue = "24") int hours,
                                          Authentication authentication) {
        Duration bucket = RANGES.get(hours);
        if (bucket == null) {
            throw new BadRequestException("hours must be one of " + new TreeMap<>(RANGES).keySet());
        }
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofHours(hours));
        // Every section counts only the stores the user may access.
        Scope scope = storeAccessService.scopeOf(authentication.getName());
        List<LogStore> stores = logStoreRepository.findAll().stream().filter(store -> scope.includes(store.id()))
                .toList();
        Map<Long, LogStore> byId = new HashMap<>();
        stores.forEach(store -> byId.put(store.id(), store));

        Logs logs = can(authentication, "LogQuery", "LogTail", "LogStore")
                ? logs(stores, byId, from, to, bucket, scope) : null;
        Agents agents = null;
        if (can(authentication, "LogStore", "LogAgent")) {
            long total = 0;
            long online = 0;
            for (Map.Entry<Long, AgentCount> entry : agentService.counts().entrySet()) {
                if (!scope.includes(entry.getKey())) {
                    continue;
                }
                AgentCount count = entry.getValue();
                total += count.total();
                online += count.online();
            }
            agents = new Agents(total, online);
        }
        Hosts hosts = can(authentication, "HostMonitor", "AlertEvent", "HostAlert") ? hosts(scope) : null;
        Alerts alerts = can(authentication, "HostMonitor", "AlertEvent", "HostAlert") ? alerts(scope) : null;
        Backup backup = can(authentication, "LogBackup", "BackupArchive") ? backup(scope) : null;
        return ApiResponse.success(new Overview(from.toEpochMilli(), to.toEpochMilli(), hours, bucket.toMillis(),
                logs, agents, hosts, alerts, backup));
    }

    private Logs logs(List<LogStore> stores, Map<Long, LogStore> byId, Instant from, Instant to, Duration bucket,
                      Scope scope) {
        int enabled = (int) stores.stream().filter(LogStore::enabled).count();
        List<LevelBucket> buckets;
        try {
            buckets = logRecordRepository.levelHistogram(from.minus(Duration.between(from, to)), to, bucket);
        } catch (RuntimeException e) {
            log.warn("Cannot read log volume for the dashboard, the log storage is unavailable: {}", e.getMessage());
            return new Logs(false, 0, 0, 0, 0, Map.of(), List.of(), stores.size(), enabled, List.of(), List.of());
        }
        long size = bucket.toMillis();
        long firstBucket = from.toEpochMilli() / size;
        long lastBucket = (to.toEpochMilli() - 1) / size;
        Map<Long, Map<String, Long>> series = new TreeMap<>();
        for (long index = firstBucket; index <= lastBucket; index++) {
            series.put(index * size, new LinkedHashMap<>());
        }
        Map<String, Long> levels = new LinkedHashMap<>();
        Map<Long, long[]> perStore = new HashMap<>();
        long total = 0;
        long previousTotal = 0;
        long errors = 0;
        long previousErrors = 0;
        for (LevelBucket item : buckets) {
            if (!scope.includes(item.storeId())) {
                continue;
            }
            boolean error = ERROR_LEVELS.contains(item.level());
            if (item.time().toEpochMilli() < firstBucket * size) {
                // The bucket that straddles the range start counts as previous; the trend is still fair.
                previousTotal += item.count();
                previousErrors += error ? item.count() : 0;
                continue;
            }
            total += item.count();
            errors += error ? item.count() : 0;
            levels.merge(item.level(), item.count(), Long::sum);
            series.computeIfAbsent(item.time().toEpochMilli(), time -> new LinkedHashMap<>())
                    .merge(item.level(), item.count(), Long::sum);
            long[] storeCounts = perStore.computeIfAbsent(item.storeId(), id -> new long[2]);
            storeCounts[0] += item.count();
            storeCounts[1] += error ? item.count() : 0;
        }
        List<StoreVolume> volumes = stores.stream()
                .map(store -> {
                    long[] counts = perStore.getOrDefault(store.id(), new long[2]);
                    return new StoreVolume(store.id(), store.code(), store.name(), store.enabled(), counts[0],
                            counts[1]);
                })
                .sorted(Comparator.comparingLong(StoreVolume::total).reversed())
                .limit(TOP)
                .toList();
        List<Point> points = series.entrySet().stream()
                .map(entry -> new Point(entry.getKey(), entry.getValue()))
                .toList();
        return new Logs(true, total, previousTotal, errors, previousErrors, levels, points, stores.size(), enabled,
                volumes, recentErrors(stores, byId, from, to));
    }

    private List<ErrorLog> recentErrors(List<LogStore> stores, Map<Long, LogStore> byId, Instant from, Instant to) {
        List<LogRecord> records = new ArrayList<>();
        for (LogStore store : stores) {
            records.addAll(logRecordRepository.search(new LogSearch(store.id(), from, to,
                    List.copyOf(ERROR_LEVELS), null, null, null), 0, RECENT_ERRORS));
        }
        return records.stream()
                .sorted(LogRecord.ORDER.reversed())
                .limit(RECENT_ERRORS)
                .map(record -> new ErrorLog(String.valueOf(record.id()), record.storeId(),
                        byId.get(record.storeId()) == null ? null : byId.get(record.storeId()).name(),
                        DateTimes.format(record.time()), record.level(), record.host(),
                        record.message().length() > 300 ? record.message().substring(0, 300) : record.message()))
                .toList();
    }

    private Hosts hosts(Scope scope) {
        List<HostStatus> hosts = monitorService.hosts(null).stream().filter(host -> scope.includes(host.storeId()))
                .toList();
        List<HostUsage> top = hosts.stream()
                .filter(host -> host.sampleTime() != null)
                .sorted(Comparator.comparing((HostStatus host) -> host.cpuPercent() == null ? -1 : host.cpuPercent())
                        .reversed())
                .limit(TOP)
                .map(host -> new HostUsage(host.storeId(), host.host(), host.hostname(), host.online(),
                        host.cpuPercent(), host.memPercent(), host.diskMaxPercent(), host.alerts()))
                .toList();
        return new Hosts(hosts.size(), (int) hosts.stream().filter(HostStatus::online).count(),
                (int) hosts.stream().filter(host -> host.sampleTime() != null).count(),
                (int) hosts.stream().filter(host -> host.alerts() > 0).count(), top);
    }

    private record Firing(AlertItem item, Instant startedAt) {
    }

    private Alerts alerts(Scope scope) {
        List<Firing> firing = new ArrayList<>();
        for (AlertEvent event : alertService.events(AlertEvent.FIRING, null, null, 30, 500)) {
            if (!scope.includes(event.storeId())) {
                continue;
            }
            firing.add(new Firing(new AlertItem("host", event.id(), event.ruleName(), event.metric(),
                    event.severity(), event.storeId(), event.hostname(), event.value(),
                    DateTimes.format(event.startedAt())), event.startedAt()));
        }
        Map<Long, String> storeNames = new HashMap<>();
        logStoreRepository.findAll().forEach(store -> storeNames.put(store.id(), store.name()));
        for (LogAlertEvent event : logAlertService.events(LogAlertService.FIRING, null, 30, 500)) {
            if (!scope.includes(event.storeId())) {
                continue;
            }
            firing.add(new Firing(new AlertItem("log", event.id(), event.ruleName(), "log", event.severity(),
                    event.storeId(), storeNames.get(event.storeId()),
                    event.value() == null ? null : event.value().doubleValue(), DateTimes.format(event.startedAt())),
                    event.startedAt()));
        }
        List<AlertItem> items = firing.stream()
                .sorted(Comparator.comparing((Firing f) -> !"critical".equals(f.item().severity()))
                        .thenComparing(Firing::startedAt, Comparator.reverseOrder()))
                .limit(TOP)
                .map(Firing::item)
                .toList();
        return new Alerts(firing.size(), (int) firing.stream()
                .filter(f -> "critical".equals(f.item().severity())).count(), items);
    }

    private Backup backup(Scope scope) {
        List<PolicyView> policies = backupService.policies().stream()
                .filter(policy -> scope.includes(policy.store().id())).toList();
        int enabled = 0;
        int pending = 0;
        long archives = 0;
        long bytes = 0;
        long raw = 0;
        long failed = 0;
        for (PolicyView view : policies) {
            boolean on = view.policy() != null && view.policy().enabled();
            enabled += on ? 1 : 0;
            pending += on ? backupService.pendingDays(view.store().id()) : 0;
            archives += view.archives();
            bytes += view.archivedBytes();
            raw += view.rawBytes();
            failed += view.failed();
        }
        BackupService.RunStatus status = backupService.status();
        RunSummary last = status.lastRun();
        return new Backup(backupService.storage() != null, enabled, policies.size(), archives, bytes, raw, failed,
                pending, status.running(), last == null ? null : DateTimes.format(last.finishedAt()),
                last == null ? null : last.archived(), last == null ? null : last.failed(),
                last == null ? null : last.error());
    }

    private boolean can(Authentication authentication, String... menus) {
        return permissionService.hasAnyMenu(authentication, menus);
    }
}
