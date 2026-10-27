package com.loggingx.biz.monitor;

import com.loggingx.biz.notify.NotifyService;
import com.loggingx.biz.notify.NotifyService.AlertNotice;
import com.loggingx.biz.notify.NotifyService.RuleNotify;
import com.loggingx.common.exception.BadRequestException;
import com.loggingx.common.exception.NotFoundException;
import com.loggingx.dao.agent.LogAgent;
import com.loggingx.dao.agent.LogAgentRepository;
import com.loggingx.dao.log.LogStore;
import com.loggingx.dao.log.LogStoreRepository;
import com.loggingx.dao.monitor.AlertEvent;
import com.loggingx.dao.monitor.AlertRule;
import com.loggingx.dao.monitor.FilesystemSample;
import com.loggingx.dao.monitor.HostMetricRepository;
import com.loggingx.dao.monitor.HostSample;
import com.loggingx.dao.monitor.MonitorMapper;
import com.loggingx.dao.support.GeneratedKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Threshold alerts on host metrics. Every minute each enabled rule is evaluated for the hosts it watches; a host for
 * which the condition holds gets a firing alert (one per rule and host, updated while it keeps firing), which is
 * resolved once the condition no longer holds or the host stops reporting.
 * <p>
 * Conditions: {@code cpu} - average CPU use over the rule's duration; {@code memory} and {@code load} - the lowest
 * value over the duration, so the whole period was above the threshold; {@code disk} - the fullest file system now;
 * {@code offline} - the agent has not synced for the duration. Metric conditions need samples covering most of the
 * duration, so a host that just started reporting does not fire.
 */
@Service
public class AlertService {

    public static final Set<String> METRICS = Set.of("cpu", "memory", "disk", "load", "offline");
    public static final Set<String> SEVERITIES = Set.of("warning", "critical");

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);
    /** Share of a rule's duration the samples must cover. */
    private static final double COVERAGE = 0.6;
    /** A host whose latest sample is older than this has no current values. */
    private static final Duration STALE = Duration.ofMinutes(3);
    private static final Duration HISTORY = Duration.ofDays(30);

    private final MonitorMapper monitorMapper;
    private final HostMetricRepository metricRepository;
    private final LogAgentRepository agentRepository;
    private final LogStoreRepository storeRepository;
    private final NotifyService notifyService;

    public AlertService(MonitorMapper monitorMapper, HostMetricRepository metricRepository,
                        LogAgentRepository agentRepository, LogStoreRepository storeRepository,
                        NotifyService notifyService) {
        this.monitorMapper = monitorMapper;
        this.metricRepository = metricRepository;
        this.agentRepository = agentRepository;
        this.storeRepository = storeRepository;
        this.notifyService = notifyService;
    }

    // ---------- rules ----------

    public List<AlertRule> rules() {
        return monitorMapper.selectRules();
    }

    public AlertRule createRule(AlertRule rule, RuleNotify notify, String operatorUsername) {
        AlertRule valid = validate(rule);
        notifyService.validate(notify);
        Instant now = Instant.now();
        AlertRule toInsert = new AlertRule(null, valid.name(), valid.metric(), valid.threshold(),
                valid.durationMinutes(), valid.severity(), valid.storeId(), valid.enabled(), now, now);
        GeneratedKey key = new GeneratedKey();
        monitorMapper.insertRule(toInsert, key);
        notifyService.saveRuleNotify(NotifyService.RULE_HOST, key.getId(), notify);
        log.info("event=alert.rule.create operator={} ruleId={} metric={} threshold={}",
                operatorUsername, key.getId(), valid.metric(), valid.threshold());
        return toInsert.withId(key.getId());
    }

    public AlertRule updateRule(Long id, AlertRule rule, RuleNotify notify, String operatorUsername) {
        AlertRule existing = requireRule(id);
        AlertRule valid = validate(rule);
        notifyService.validate(notify);
        AlertRule updated = new AlertRule(id, valid.name(), valid.metric(), valid.threshold(),
                valid.durationMinutes(), valid.severity(), valid.storeId(), valid.enabled(), existing.createdAt(),
                Instant.now());
        monitorMapper.updateRule(updated);
        notifyService.saveRuleNotify(NotifyService.RULE_HOST, id, notify);
        if (!updated.enabled() || !updated.metric().equals(existing.metric())) {
            monitorMapper.resolveRuleEvents(id, Instant.now());
        }
        log.info("event=alert.rule.update operator={} ruleId={} enabled={} threshold={}",
                operatorUsername, id, updated.enabled(), updated.threshold());
        return updated;
    }

    public void deleteRule(Long id, String operatorUsername) {
        requireRule(id);
        monitorMapper.deleteRule(id);
        monitorMapper.resolveRuleEvents(id, Instant.now());
        notifyService.deleteRuleNotify(NotifyService.RULE_HOST, id);
        log.info("event=alert.rule.delete operator={} ruleId={}", operatorUsername, id);
    }

    private AlertRule requireRule(Long id) {
        AlertRule rule = monitorMapper.selectRule(id);
        if (rule == null) {
            throw new NotFoundException("Alert rule was not found");
        }
        return rule;
    }

    private static AlertRule validate(AlertRule rule) {
        String name = rule.name() == null ? "" : rule.name().trim();
        if (name.isEmpty() || name.length() > 64) {
            throw new BadRequestException("name must be 1-64 characters");
        }
        String metric = rule.metric() == null ? "" : rule.metric().trim().toLowerCase(Locale.ROOT);
        if (!METRICS.contains(metric)) {
            throw new BadRequestException("metric must be one of " + METRICS);
        }
        String severity = rule.severity() == null ? "" : rule.severity().trim().toLowerCase(Locale.ROOT);
        if (!SEVERITIES.contains(severity)) {
            throw new BadRequestException("severity must be warning or critical");
        }
        double threshold = rule.threshold();
        boolean percent = Set.of("cpu", "memory", "disk").contains(metric);
        if (!Double.isFinite(threshold) || (percent && (threshold <= 0 || threshold >= 100))
                || ("load".equals(metric) && (threshold <= 0 || threshold > 100))) {
            throw new BadRequestException(percent ? "threshold must be a percentage between 0 and 100"
                    : "threshold must be a positive load per CPU core");
        }
        int duration = rule.durationMinutes();
        if (duration < ("disk".equals(metric) ? 0 : 1) || duration > 1440) {
            throw new BadRequestException("durationMinutes must be between 1 and 1440");
        }
        return new AlertRule(rule.id(), name, metric, "offline".equals(metric) ? 0 : threshold, duration, severity,
                rule.storeId(), rule.enabled(), rule.createdAt(), rule.updatedAt());
    }

    /**
     * Adds the default rules when there are none, so monitoring alerts out of the box. Rules deleted later come back
     * only when all of them are deleted.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void seedDefaultRules() {
        try {
            if (!monitorMapper.selectRules().isEmpty()) {
                return;
            }
            Instant now = Instant.now();
            List<AlertRule> defaults = List.of(
                    new AlertRule(null, "CPU 使用率过高", "cpu", 90, 5, "critical", null, true, now, now),
                    new AlertRule(null, "内存使用率过高", "memory", 90, 5, "warning", null, true, now, now),
                    new AlertRule(null, "磁盘空间不足", "disk", 85, 0, "warning", null, true, now, now),
                    new AlertRule(null, "系统负载过高", "load", 2, 10, "warning", null, true, now, now),
                    new AlertRule(null, "主机离线", "offline", 0, 3, "critical", null, true, now, now));
            for (AlertRule rule : defaults) {
                monitorMapper.insertRule(rule, new GeneratedKey());
            }
            log.info("Added {} default host alert rules", defaults.size());
        } catch (RuntimeException e) {
            log.warn("Cannot add the default host alert rules: {}", e.getMessage());
        }
    }

    // ---------- events ----------

    /**
     * @param status {@code firing}, {@code resolved} or {@code null} for both
     * @param days   events started within this many days (firing events are always included)
     */
    public List<AlertEvent> events(String status, Long storeId, String host, int days, int limit) {
        String cleanStatus = status == null || status.isBlank() ? null : status.trim().toLowerCase(Locale.ROOT);
        if (cleanStatus != null && !Set.of(AlertEvent.FIRING, AlertEvent.RESOLVED).contains(cleanStatus)) {
            throw new BadRequestException("status must be firing or resolved");
        }
        Instant since = Instant.now().minus(Duration.ofDays(Math.max(1, Math.min(days, 30))));
        return monitorMapper.selectEvents(cleanStatus, storeId,
                host == null || host.isBlank() ? null : host.trim().toLowerCase(Locale.ROOT), since,
                Math.max(1, Math.min(limit, 500)));
    }

    // ---------- evaluation ----------

    private record HostKey(Long storeId, String host) {
    }

    @Scheduled(initialDelayString = "${loggingx.monitor.alert-initial-delay:30000}",
            fixedDelayString = "${loggingx.monitor.alert-interval:60000}")
    public void evaluateScheduled() {
        try {
            evaluate(Instant.now());
        } catch (RuntimeException e) {
            log.warn("Cannot evaluate host alert rules: {}", e.getMessage());
        }
    }

    /**
     * Evaluates every enabled rule at {@code now}, raising, updating and resolving alerts.
     */
    public void evaluate(Instant now) {
        List<AlertRule> rules = monitorMapper.selectRules().stream().filter(AlertRule::enabled).toList();
        Map<String, AlertEvent> firing = monitorMapper.selectFiringEvents().stream()
                .collect(Collectors.toMap(event -> eventKey(event.ruleId(), event.storeId(), event.hostname()),
                        event -> event, (a, b) -> a));
        int longest = rules.stream().filter(rule -> Set.of("cpu", "memory", "load").contains(rule.metric()))
                .mapToInt(AlertRule::durationMinutes).max().orElse(0);
        Map<HostKey, List<HostSample>> samples = longest == 0 ? Map.of()
                : metricRepository.samplesSince(now.minus(Duration.ofMinutes(longest + 1))).stream()
                .collect(Collectors.groupingBy(sample -> new HostKey(sample.storeId(), sample.host()),
                        LinkedHashMap::new, Collectors.toList()));
        Map<HostKey, Double> disks = rules.stream().noneMatch(rule -> "disk".equals(rule.metric())) ? Map.of()
                : metricRepository.latestFilesystems(now.minus(STALE)).stream()
                .collect(Collectors.toMap(sample -> new HostKey(sample.storeId(), sample.host()),
                        FilesystemSample::usedPercent, Math::max));
        List<LogAgent> agents = rules.stream().anyMatch(rule -> "offline".equals(rule.metric()))
                ? agentRepository.findByStore(null) : List.of();

        for (AlertRule rule : rules) {
            Map<HostKey, Double> values = switch (rule.metric()) {
                case "offline" -> offline(rule, agents, now);
                case "disk" -> disks;
                default -> windowed(rule, samples, now);
            };
            for (Map.Entry<HostKey, Double> entry : values.entrySet()) {
                HostKey host = entry.getKey();
                Double value = entry.getValue();
                if (rule.storeId() != null && !rule.storeId().equals(host.storeId())) {
                    continue;
                }
                boolean fires = value != null && ("offline".equals(rule.metric()) || value > rule.threshold());
                String key = eventKey(rule.id(), host.storeId(), host.host());
                AlertEvent open = firing.remove(key);
                if (fires) {
                    raise(rule, host, value, open, now);
                } else if (open != null) {
                    resolve(open, now);
                }
            }
            // Firing alerts of hosts that no longer report are resolved.
            for (String key : new ArrayList<>(firing.keySet())) {
                if (key.startsWith(rule.id() + "/")) {
                    resolve(firing.remove(key), now);
                }
            }
        }
        // Alerts of disabled or deleted rules.
        firing.values().forEach(event -> resolve(event, now));
        if (now.getEpochSecond() / 60 % 60 == 0) {
            monitorMapper.deleteEventsBefore(now.minus(HISTORY));
        }
    }

    /**
     * @return minutes since the last sync of every agent that has not synced for the rule's duration
     */
    private static Map<HostKey, Double> offline(AlertRule rule, List<LogAgent> agents, Instant now) {
        Map<HostKey, Double> values = new HashMap<>();
        for (LogAgent agent : agents) {
            if (agent.lastSeenAt() == null) {
                continue;
            }
            HostKey key = new HostKey(agent.storeId(), agent.hostname().toLowerCase(Locale.ROOT));
            double minutes = Duration.between(agent.lastSeenAt(), now).toSeconds() / 60.0;
            Double value = minutes >= rule.durationMinutes() ? minutes : null;
            // A host with several agent records is online when any of them is.
            if (!values.containsKey(key)) {
                values.put(key, value);
            } else if (values.get(key) != null) {
                values.put(key, value == null ? null : Math.min(values.get(key), value));
            }
        }
        return values;
    }

    /**
     * @return each host's value over the rule's duration, or {@code null} when its samples do not cover enough of it
     */
    private static Map<HostKey, Double> windowed(AlertRule rule, Map<HostKey, List<HostSample>> samples,
                                                 Instant now) {
        Duration window = Duration.ofMinutes(rule.durationMinutes());
        Instant start = now.minus(window);
        Map<HostKey, Double> values = new HashMap<>();
        samples.forEach((host, all) -> {
            List<HostSample> inWindow = all.stream().filter(sample -> !sample.time().isBefore(start)).toList();
            if (inWindow.size() < 2 || inWindow.get(inWindow.size() - 1).time().isBefore(now.minus(STALE))
                    || Duration.between(inWindow.get(0).time(), inWindow.get(inWindow.size() - 1).time()).toMillis()
                    < window.toMillis() * COVERAGE) {
                values.put(host, null);
                return;
            }
            values.put(host, switch (rule.metric()) {
                case "cpu" -> MonitorService.cpuPercent(inWindow.get(0), inWindow.get(inWindow.size() - 1));
                case "memory" -> inWindow.stream()
                        .map(sample -> MonitorService.percent(sample.memUsed(), sample.memTotal()))
                        .filter(Objects::nonNull).min(Double::compare).orElse(null);
                case "load" -> inWindow.stream()
                        .filter(sample -> sample.load1() != null && sample.cpuCores() != null
                                && sample.cpuCores() > 0)
                        .map(sample -> sample.load1() / sample.cpuCores())
                        .min(Double::compare).orElse(null);
                default -> null;
            });
        });
        return values;
    }

    private void raise(AlertRule rule, HostKey host, double value, AlertEvent open, Instant now) {
        if (open != null) {
            monitorMapper.updateEvent(new AlertEvent(open.id(), open.ruleId(), open.ruleName(), open.metric(),
                    open.severity(), open.threshold(), open.storeId(), open.hostname(), value,
                    Math.max(value, open.peakValue() == null ? value : open.peakValue()), AlertEvent.FIRING,
                    open.startedAt(), now, null));
            if (notifyService.repeatDue(NotifyService.RULE_HOST, open.id(), NotifyService.RULE_HOST, open.ruleId(),
                    now)) {
                notify(open, value, "repeat", null);
            }
            return;
        }
        AlertEvent event = new AlertEvent(null, rule.id(), rule.name(), rule.metric(), rule.severity(),
                rule.threshold(), host.storeId(), host.host(), value, value, AlertEvent.FIRING, now, now, null);
        GeneratedKey key = new GeneratedKey();
        monitorMapper.insertEvent(event, key);
        log.warn("event=alert.fire rule=\"{}\" storeId={} host={} value={}", rule.name(), host.storeId(),
                host.host(), String.format(Locale.ROOT, "%.1f", value));
        notify(event.withId(key.getId()), value, "firing", null);
    }

    private void resolve(AlertEvent event, Instant now) {
        monitorMapper.updateEvent(new AlertEvent(event.id(), event.ruleId(), event.ruleName(), event.metric(),
                event.severity(), event.threshold(), event.storeId(), event.hostname(), event.value(),
                event.peakValue(), AlertEvent.RESOLVED, event.startedAt(), now, now));
        log.info("event=alert.resolve rule=\"{}\" storeId={} host={}", event.ruleName(), event.storeId(),
                event.hostname());
        if (event.value() != null) {
            notify(event, event.value(), "resolved", now);
        }
    }

    private void notify(AlertEvent event, double value, String kind, Instant resolvedAt) {
        try {
            String store = event.storeId() == null ? null : storeRepository.findById(event.storeId())
                    .map(LogStore::name).orElse(null);
            String target = event.hostname() + (store == null ? "" : "（" + store + "）");
            notifyService.notify(new AlertNotice(NotifyService.RULE_HOST, event.id(), NotifyService.RULE_HOST,
                    event.ruleId(), event.ruleName(), event.severity(), kind, target,
                    detail(event.metric(), value, event.threshold(), false),
                    detail(event.metric(), value, event.threshold(), true), event.startedAt(), resolvedAt, null));
        } catch (RuntimeException e) {
            log.warn("Cannot notify host alert {}: {}", event.id(), e.getMessage());
        }
    }

    /**
     * @param brief for an SMS, whose template variables are short
     */
    static String detail(String metric, double value, double threshold, boolean brief) {
        String v = String.format(Locale.ROOT, "%.1f", value);
        String t = String.format(Locale.ROOT, "%s", threshold == Math.rint(threshold)
                ? String.valueOf((long) threshold) : String.valueOf(threshold));
        return switch (metric) {
            case "cpu" -> brief ? "CPU " + v + "%>" + t + "%" : "CPU 使用率 " + v + "%（阈值 " + t + "%）";
            case "memory" -> brief ? "内存 " + v + "%>" + t + "%" : "内存使用率 " + v + "%（阈值 " + t + "%）";
            case "disk" -> brief ? "磁盘 " + v + "%>" + t + "%" : "磁盘使用率 " + v + "%（阈值 " + t + "%）";
            case "load" -> brief ? "负载 " + String.format(Locale.ROOT, "%.2f", value) + "/核"
                    : "每核负载 " + String.format(Locale.ROOT, "%.2f", value) + "（阈值 " + t + "）";
            case "offline" -> brief ? Math.round(value) + "分钟未上报" : "Agent 已 " + Math.round(value) + " 分钟未上报";
            default -> metric + " " + v;
        };
    }

    private static String eventKey(Long ruleId, Long storeId, String host) {
        return ruleId + "/" + storeId + "/" + host.toLowerCase(Locale.ROOT);
    }
}
