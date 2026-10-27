package com.loggingx.web.monitor;

import com.loggingx.biz.monitor.AlertService;
import com.loggingx.biz.monitor.ContainerUsage;
import com.loggingx.biz.monitor.HostStatus;
import com.loggingx.biz.monitor.MonitorService;
import com.loggingx.biz.monitor.SeriesPoint;
import com.loggingx.biz.notify.NotifyService;
import com.loggingx.biz.notify.NotifyService.RuleNotify;
import com.loggingx.common.api.ApiResponse;
import com.loggingx.common.util.DateTimes;
import com.loggingx.dao.log.LogStore;
import com.loggingx.dao.log.LogStoreRepository;
import com.loggingx.dao.monitor.AlertEvent;
import com.loggingx.dao.monitor.AlertRule;
import com.loggingx.dao.monitor.FilesystemSample;
import com.loggingx.dao.monitor.ProcessSample;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Host resource monitoring and alerts. Hosts are the agents of the log stores, identified by store id and host name.
 * Times are epoch milliseconds, for the charts, alongside formatted local times.
 */
@RestController
@RequestMapping("/monitor")
public class MonitorController {

    private static final String VIEWERS = "@permissionService.hasAnyMenu(authentication, 'HostMonitor', 'HostAlert')";
    private static final String ALERT_MANAGERS = "@permissionService.hasAnyMenu(authentication, 'HostAlert')";

    private final MonitorService monitorService;
    private final AlertService alertService;
    private final LogStoreRepository logStoreRepository;
    private final NotifyService notifyService;

    public MonitorController(MonitorService monitorService, AlertService alertService,
                             LogStoreRepository logStoreRepository, NotifyService notifyService) {
        this.monitorService = monitorService;
        this.alertService = alertService;
        this.logStoreRepository = logStoreRepository;
        this.notifyService = notifyService;
    }

    // ---------- responses ----------

    public record HostResponse(Long storeId, String storeCode, String storeName, String host, String hostname,
                               Long agentId, String platform, String version, String ip, boolean online,
                               String lastSeenTime, Long sampleTime, Double cpuPercent, Double cpuCores,
                               Double memTotal, Double memUsed, Double memPercent, Double swapTotal, Double swapUsed,
                               Double load1, Double load5, Double load15, Double diskMaxPercent, String diskMaxMount,
                               Double diskReadBps, Double diskWriteBps, Double netRxBps, Double netTxBps,
                               Double uptime, int alerts, String alertSeverity) {

        static HostResponse from(HostStatus host, LogStore store) {
            return new HostResponse(host.storeId(), store == null ? null : store.code(),
                    store == null ? null : store.name(), host.host(), host.hostname(), host.agentId(),
                    host.platform(), host.version(), host.ip(), host.online(), DateTimes.format(host.lastSeenAt()),
                    millis(host.sampleTime()), host.cpuPercent(), host.cpuCores(), host.memTotal(), host.memUsed(),
                    host.memPercent(), host.swapTotal(), host.swapUsed(), host.load1(), host.load5(), host.load15(),
                    host.diskMaxPercent(), host.diskMaxMount(), host.diskReadBps(), host.diskWriteBps(),
                    host.netRxBps(), host.netTxBps(), host.uptime(), host.alerts(), host.alertSeverity());
        }
    }

    public record SeriesResponse(long time, Double cpuPercent, Double memPercent, Double swapPercent, Double load1,
                                 Double load5, Double load15, Double diskReadBps, Double diskWriteBps,
                                 Double netRxBps, Double netTxBps, Double diskMaxPercent) {

        static SeriesResponse from(SeriesPoint point) {
            return new SeriesResponse(point.time().toEpochMilli(), point.cpuPercent(), point.memPercent(),
                    point.swapPercent(), point.load1(), point.load5(), point.load15(), point.diskReadBps(),
                    point.diskWriteBps(), point.netRxBps(), point.netTxBps(), point.diskMaxPercent());
        }
    }

    public record FilesystemResponse(String mountpoint, String fsType, double totalBytes, double usedBytes,
                                     double usedPercent) {
    }

    public record ProcessResponse(long pid, String name, Double cpuPercent, Double memBytes, long time) {
    }

    public record ContainerResponse(String cgroup, String kind, String name, Double cpuPercent, Double memBytes) {
    }

    public record AlertEventResponse(Long id, Long ruleId, String ruleName, String metric, String severity,
                                     double threshold, Long storeId, String storeCode, String storeName,
                                     String host, Double value, Double peakValue, String status, long startedAt,
                                     String startTime, String updateTime, String resolveTime) {
    }

    public record AlertRuleResponse(Long id, String name, String metric, double threshold, int durationMinutes,
                                    String severity, Long storeId, String storeName, boolean enabled,
                                    String updateTime, RuleNotify notification) {
    }

    public record AlertRuleRequest(String name, String metric, Double threshold, Integer durationMinutes,
                                   String severity, Long storeId, Boolean enabled,
                                   RuleNotify notification) {

        AlertRule toRule() {
            return new AlertRule(null, name, metric, threshold == null ? Double.NaN : threshold,
                    durationMinutes == null ? -1 : durationMinutes, severity, storeId, enabled == null || enabled,
                    null, null);
        }
    }

    // ---------- hosts ----------

    /**
     * @param storeId the store, or none for the hosts of all stores
     */
    @GetMapping("/hosts")
    @PreAuthorize(VIEWERS)
    public ApiResponse<List<HostResponse>> hosts(@RequestParam(required = false) Long storeId) {
        Map<Long, LogStore> stores = stores();
        return ApiResponse.success(monitorService.hosts(storeId).stream()
                .map(host -> HostResponse.from(host, stores.get(host.storeId())))
                .toList());
    }

    /**
     * @param from inclusive, epoch milliseconds
     * @param to   exclusive, epoch milliseconds
     */
    @GetMapping("/hosts/series")
    @PreAuthorize(VIEWERS)
    public ApiResponse<List<SeriesResponse>> series(@RequestParam Long storeId, @RequestParam String host,
                                                    @RequestParam long from, @RequestParam long to) {
        return ApiResponse.success(monitorService.series(storeId, host, Instant.ofEpochMilli(from),
                Instant.ofEpochMilli(to)).stream().map(SeriesResponse::from).toList());
    }

    @GetMapping("/hosts/filesystems")
    @PreAuthorize(VIEWERS)
    public ApiResponse<List<FilesystemResponse>> filesystems(@RequestParam Long storeId, @RequestParam String host) {
        return ApiResponse.success(monitorService.filesystems(storeId, host).stream()
                .map((FilesystemSample fs) -> new FilesystemResponse(fs.mountpoint(), fs.fsType(), fs.totalBytes(),
                        fs.usedBytes(), fs.usedPercent()))
                .toList());
    }

    /**
     * @param at epoch milliseconds to show the processes at, by default now
     */
    @GetMapping("/hosts/processes")
    @PreAuthorize(VIEWERS)
    public ApiResponse<List<ProcessResponse>> processes(@RequestParam Long storeId, @RequestParam String host,
                                                        @RequestParam(required = false) Long at) {
        Instant time = at == null ? Instant.now() : Instant.ofEpochMilli(at);
        return ApiResponse.success(monitorService.processes(storeId, host, time).stream()
                .map((ProcessSample process) -> new ProcessResponse(process.pid(), process.name(),
                        process.cpuPercent(), process.memBytes(), process.time().toEpochMilli()))
                .toList());
    }

    @GetMapping("/hosts/containers")
    @PreAuthorize(VIEWERS)
    public ApiResponse<List<ContainerResponse>> containers(@RequestParam Long storeId, @RequestParam String host) {
        return ApiResponse.success(monitorService.containers(storeId, host).stream()
                .map((ContainerUsage usage) -> new ContainerResponse(usage.cgroup(), usage.kind(), usage.name(),
                        usage.cpuPercent(), usage.memBytes()))
                .toList());
    }

    // ---------- alerts ----------

    /**
     * @param status {@code firing}, {@code resolved}, or none for both
     * @param days   events started within this many days; firing events are always included
     */
    @GetMapping("/alerts")
    @PreAuthorize(VIEWERS)
    public ApiResponse<List<AlertEventResponse>> alerts(@RequestParam(required = false) String status,
                                                        @RequestParam(required = false) Long storeId,
                                                        @RequestParam(required = false) String host,
                                                        @RequestParam(defaultValue = "7") int days,
                                                        @RequestParam(defaultValue = "200") int limit) {
        Map<Long, LogStore> stores = stores();
        return ApiResponse.success(alertService.events(status, storeId, host, days, limit).stream()
                .map((AlertEvent event) -> {
                    LogStore store = stores.get(event.storeId());
                    return new AlertEventResponse(event.id(), event.ruleId(), event.ruleName(), event.metric(),
                            event.severity(), event.threshold(), event.storeId(),
                            store == null ? null : store.code(), store == null ? null : store.name(),
                            event.hostname(), event.value(), event.peakValue(), event.status(),
                            event.startedAt().toEpochMilli(), DateTimes.format(event.startedAt()),
                            DateTimes.format(event.updatedAt()), DateTimes.format(event.resolvedAt()));
                })
                .toList());
    }

    @GetMapping("/alert-rules")
    @PreAuthorize(VIEWERS)
    public ApiResponse<List<AlertRuleResponse>> rules() {
        Map<Long, LogStore> stores = stores();
        Map<Long, RuleNotify> notifies = notifyService.ruleNotifies(NotifyService.RULE_HOST);
        return ApiResponse.success(alertService.rules().stream()
                .map(rule -> ruleResponse(rule, stores, notifies.getOrDefault(rule.id(), RuleNotify.none())))
                .toList());
    }

    @PostMapping("/alert-rules")
    @PreAuthorize(ALERT_MANAGERS)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AlertRuleResponse> createRule(@RequestBody AlertRuleRequest request,
                                                     Authentication authentication) {
        requireStoreIfGiven(request.storeId());
        AlertRule rule = alertService.createRule(request.toRule(), request.notification(), authentication.getName());
        return ApiResponse.success(ruleResponse(rule, stores(),
                notifyService.ruleNotify(NotifyService.RULE_HOST, rule.id())));
    }

    @PutMapping("/alert-rules/{id}")
    @PreAuthorize(ALERT_MANAGERS)
    public ApiResponse<AlertRuleResponse> updateRule(@PathVariable Long id, @RequestBody AlertRuleRequest request,
                                                     Authentication authentication) {
        requireStoreIfGiven(request.storeId());
        AlertRule rule = alertService.updateRule(id, request.toRule(), request.notification(), authentication.getName());
        return ApiResponse.success(ruleResponse(rule, stores(), notifyService.ruleNotify(NotifyService.RULE_HOST, id)));
    }

    @DeleteMapping("/alert-rules/{id}")
    @PreAuthorize(ALERT_MANAGERS)
    public ApiResponse<Void> deleteRule(@PathVariable Long id, Authentication authentication) {
        alertService.deleteRule(id, authentication.getName());
        return ApiResponse.success(null);
    }

    private void requireStoreIfGiven(Long storeId) {
        if (storeId != null) {
            monitorService.requireStore(storeId);
        }
    }

    private static AlertRuleResponse ruleResponse(AlertRule rule, Map<Long, LogStore> stores,
                                                  RuleNotify notify) {
        LogStore store = rule.storeId() == null ? null : stores.get(rule.storeId());
        return new AlertRuleResponse(rule.id(), rule.name(), rule.metric(), rule.threshold(),
                rule.durationMinutes(), rule.severity(), rule.storeId(), store == null ? null : store.name(),
                rule.enabled(), DateTimes.format(rule.updatedAt()), notify);
    }

    private Map<Long, LogStore> stores() {
        Map<Long, LogStore> stores = new HashMap<>();
        logStoreRepository.findAll().forEach(store -> stores.put(store.id(), store));
        return stores;
    }

    private static Long millis(Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }
}
