package com.loggingx.biz.monitor;

import com.loggingx.biz.notify.NotifyService;
import com.loggingx.biz.notify.NotifyService.AlertNotice;
import com.loggingx.biz.notify.NotifyService.RuleNotify;
import com.loggingx.common.exception.BadRequestException;
import com.loggingx.common.exception.NotFoundException;
import com.loggingx.dao.log.LogLevel;
import com.loggingx.dao.log.LogRecord;
import com.loggingx.dao.log.LogRecordRepository;
import com.loggingx.dao.log.LogSearch;
import com.loggingx.dao.log.LogStore;
import com.loggingx.dao.log.LogStoreRepository;
import com.loggingx.dao.monitor.LogAlertEvent;
import com.loggingx.dao.monitor.LogAlertMapper;
import com.loggingx.dao.monitor.LogAlertRule;
import com.loggingx.dao.support.GeneratedKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Alerts on logs: a rule fires when more than {@code threshold} logs of a store match its levels, keyword and host
 * within the last {@code windowMinutes}. Every minute each enabled rule is counted; a rule whose count is above the
 * threshold has one firing alert, updated while it keeps firing and resolved once the count drops back.
 */
@Service
public class LogAlertService {

    public static final String FIRING = "firing";
    public static final String RESOLVED = "resolved";

    private static final Logger log = LoggerFactory.getLogger(LogAlertService.class);
    private static final Duration HISTORY = Duration.ofDays(30);
    private static final int SAMPLE_LENGTH = 500;

    private final LogAlertMapper mapper;
    private final LogRecordRepository recordRepository;
    private final LogStoreRepository storeRepository;
    private final NotifyService notifyService;

    public LogAlertService(LogAlertMapper mapper, LogRecordRepository recordRepository,
                           LogStoreRepository storeRepository, NotifyService notifyService) {
        this.mapper = mapper;
        this.recordRepository = recordRepository;
        this.storeRepository = storeRepository;
        this.notifyService = notifyService;
    }

    // ---------- rules ----------

    public List<LogAlertRule> rules() {
        return mapper.selectRules();
    }

    public LogAlertRule createRule(LogAlertRule rule, RuleNotify notify, String operatorUsername) {
        LogAlertRule valid = validate(rule);
        notifyService.validate(notify);
        Instant now = Instant.now();
        LogAlertRule toInsert = new LogAlertRule(null, valid.name(), valid.storeId(), valid.levels(),
                valid.keyword(), valid.host(), valid.windowMinutes(), valid.threshold(), valid.severity(),
                valid.enabled(), now, now);
        GeneratedKey key = new GeneratedKey();
        mapper.insertRule(toInsert, key);
        notifyService.saveRuleNotify(NotifyService.RULE_LOG, key.getId(), notify);
        log.info("event=log-alert.rule.create operator={} ruleId={} storeId={} threshold={}", operatorUsername,
                key.getId(), valid.storeId(), valid.threshold());
        return toInsert.withId(key.getId());
    }

    public LogAlertRule updateRule(Long id, LogAlertRule rule, RuleNotify notify, String operatorUsername) {
        LogAlertRule existing = requireRule(id);
        LogAlertRule valid = validate(rule);
        notifyService.validate(notify);
        LogAlertRule updated = new LogAlertRule(id, valid.name(), valid.storeId(), valid.levels(), valid.keyword(),
                valid.host(), valid.windowMinutes(), valid.threshold(), valid.severity(), valid.enabled(),
                existing.createdAt(), Instant.now());
        mapper.updateRule(updated);
        notifyService.saveRuleNotify(NotifyService.RULE_LOG, id, notify);
        if (!updated.enabled() || !updated.storeId().equals(existing.storeId())) {
            mapper.resolveRuleEvents(id, Instant.now());
        }
        log.info("event=log-alert.rule.update operator={} ruleId={} enabled={} threshold={}", operatorUsername, id,
                updated.enabled(), updated.threshold());
        return updated;
    }

    public void deleteRule(Long id, String operatorUsername) {
        requireRule(id);
        mapper.deleteRule(id);
        mapper.resolveRuleEvents(id, Instant.now());
        notifyService.deleteRuleNotify(NotifyService.RULE_LOG, id);
        log.info("event=log-alert.rule.delete operator={} ruleId={}", operatorUsername, id);
    }

    private LogAlertRule requireRule(Long id) {
        LogAlertRule rule = mapper.selectRule(id);
        if (rule == null) {
            throw new NotFoundException("Log alert rule was not found");
        }
        return rule;
    }

    private LogAlertRule validate(LogAlertRule rule) {
        String name = trim(rule.name());
        if (name.isEmpty() || name.length() > 64) {
            throw new BadRequestException("name must be 1-64 characters");
        }
        if (rule.storeId() == null || storeRepository.findById(rule.storeId()).isEmpty()) {
            throw new BadRequestException("Choose the log store the rule watches");
        }
        Set<String> levels = new LinkedHashSet<>();
        for (String level : trim(rule.levels()).split(",")) {
            if (level.isBlank()) {
                continue;
            }
            String clean = level.trim().toUpperCase(Locale.ROOT);
            if (Arrays.stream(LogLevel.values()).noneMatch(value -> value.name().equals(clean))) {
                throw new BadRequestException("Unknown log level " + clean);
            }
            levels.add(clean);
        }
        String keyword = trim(rule.keyword());
        if (keyword.length() > 200) {
            throw new BadRequestException("keyword must be at most 200 characters");
        }
        String host = trim(rule.host());
        if (levels.isEmpty() && keyword.isEmpty()) {
            throw new BadRequestException("Choose levels or a keyword to match");
        }
        if (rule.windowMinutes() < 1 || rule.windowMinutes() > 1440) {
            throw new BadRequestException("windowMinutes must be between 1 and 1440");
        }
        if (rule.threshold() < 0 || rule.threshold() > 100_000_000) {
            throw new BadRequestException("threshold must be 0 or more");
        }
        String severity = trim(rule.severity()).toLowerCase(Locale.ROOT);
        if (!AlertService.SEVERITIES.contains(severity)) {
            throw new BadRequestException("severity must be warning or critical");
        }
        return new LogAlertRule(rule.id(), name, rule.storeId(), levels.isEmpty() ? null : String.join(",", levels),
                keyword.isEmpty() ? null : keyword, host.isEmpty() ? null : host, rule.windowMinutes(),
                rule.threshold(), severity, rule.enabled(), rule.createdAt(), rule.updatedAt());
    }

    // ---------- events ----------

    /**
     * @param status {@code firing}, {@code resolved} or {@code null} for both
     * @param days   events started within this many days (firing events are always included)
     */
    public List<LogAlertEvent> events(String status, Long storeId, int days, int limit) {
        String cleanStatus = status == null || status.isBlank() ? null : status.trim().toLowerCase(Locale.ROOT);
        if (cleanStatus != null && !Set.of(FIRING, RESOLVED).contains(cleanStatus)) {
            throw new BadRequestException("status must be firing or resolved");
        }
        Instant since = Instant.now().minus(Duration.ofDays(Math.max(1, Math.min(days, 30))));
        return mapper.selectEvents(cleanStatus, storeId, since, Math.max(1, Math.min(limit, 500)));
    }

    // ---------- evaluation ----------

    @Scheduled(initialDelayString = "${loggingx.monitor.alert-initial-delay:30000}",
            fixedDelayString = "${loggingx.monitor.alert-interval:60000}")
    public void evaluateScheduled() {
        try {
            evaluate(Instant.now());
        } catch (RuntimeException e) {
            log.warn("Cannot evaluate log alert rules: {}", e.getMessage());
        }
    }

    /**
     * Counts every enabled rule's matching logs in its window ending at {@code now}, raising, updating and resolving
     * alerts.
     */
    public void evaluate(Instant now) {
        Map<Long, LogAlertEvent> firing = mapper.selectFiringEvents().stream()
                .collect(Collectors.toMap(LogAlertEvent::ruleId, Function.identity(), (a, b) -> a));
        for (LogAlertRule rule : mapper.selectRules()) {
            LogAlertEvent open = firing.remove(rule.id());
            if (!rule.enabled()) {
                if (open != null) {
                    resolve(open, now);
                }
                continue;
            }
            LogSearch search = search(rule, now);
            long count;
            try {
                count = recordRepository.count(search);
            } catch (RuntimeException e) {
                log.warn("Cannot count the logs of alert rule {}: {}", rule.id(), e.getMessage());
                continue;
            }
            if (count > rule.threshold()) {
                raise(rule, count, sample(search), open, now);
            } else if (open != null) {
                resolve(open, now);
            }
        }
        firing.values().forEach(event -> resolve(event, now));
        if (now.getEpochSecond() / 60 % 60 == 0) {
            mapper.deleteEventsBefore(now.minus(HISTORY));
        }
    }

    private static LogSearch search(LogAlertRule rule, Instant now) {
        List<String> levels = rule.levels() == null ? List.of() : Arrays.asList(rule.levels().split(","));
        return new LogSearch(rule.storeId(), now.minus(Duration.ofMinutes(rule.windowMinutes())), now, levels,
                rule.keyword(), rule.host(), null);
    }

    private String sample(LogSearch search) {
        try {
            List<LogRecord> newest = recordRepository.search(search, 0, 1);
            if (newest.isEmpty()) {
                return null;
            }
            LogRecord record = newest.get(0);
            String line = "[" + record.level() + "] " + (record.host() == null ? "" : record.host() + " ")
                    + record.message();
            return line.length() > SAMPLE_LENGTH ? line.substring(0, SAMPLE_LENGTH) + "…" : line;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void raise(LogAlertRule rule, long count, String sample, LogAlertEvent open, Instant now) {
        if (open != null) {
            LogAlertEvent updated = new LogAlertEvent(open.id(), open.ruleId(), rule.name(), open.storeId(),
                    rule.severity(), rule.threshold(), rule.windowMinutes(), count,
                    Math.max(count, open.peakValue() == null ? count : open.peakValue()),
                    sample == null ? open.sample() : sample, FIRING, open.startedAt(), now, null);
            mapper.updateEvent(updated);
            if (notifyService.repeatDue(NotifyService.RULE_LOG, open.id(), NotifyService.RULE_LOG, rule.id(), now)) {
                notify(updated, "repeat", null);
            }
            return;
        }
        LogAlertEvent event = new LogAlertEvent(null, rule.id(), rule.name(), rule.storeId(), rule.severity(),
                rule.threshold(), rule.windowMinutes(), count, count, sample, FIRING, now, now, null);
        GeneratedKey key = new GeneratedKey();
        mapper.insertEvent(event, key);
        log.warn("event=log-alert.fire rule=\"{}\" storeId={} count={} threshold={}", rule.name(), rule.storeId(),
                count, rule.threshold());
        notify(event.withId(key.getId()), "firing", null);
    }

    private void resolve(LogAlertEvent event, Instant now) {
        LogAlertEvent resolved = new LogAlertEvent(event.id(), event.ruleId(), event.ruleName(), event.storeId(),
                event.severity(), event.threshold(), event.windowMinutes(), event.value(), event.peakValue(),
                event.sample(), RESOLVED, event.startedAt(), now, now);
        mapper.updateEvent(resolved);
        log.info("event=log-alert.resolve rule=\"{}\" storeId={}", event.ruleName(), event.storeId());
        notify(resolved, "resolved", now);
    }

    private void notify(LogAlertEvent event, String kind, Instant resolvedAt) {
        try {
            String store = storeRepository.findById(event.storeId()).map(LogStore::name)
                    .orElse("#" + event.storeId());
            String detail;
            String brief;
            if ("resolved".equals(kind)) {
                detail = "最近 " + event.windowMinutes() + " 分钟匹配日志已不超过 " + event.threshold() + " 条（峰值 "
                        + event.peakValue() + " 条）";
                brief = event.windowMinutes() + "分钟内已恢复";
            } else {
                detail = "最近 " + event.windowMinutes() + " 分钟匹配日志 " + event.value() + " 条（阈值 "
                        + event.threshold() + " 条）";
                brief = event.windowMinutes() + "分钟" + event.value() + "条>" + event.threshold();
            }
            notifyService.notify(new AlertNotice(NotifyService.RULE_LOG, event.id(), NotifyService.RULE_LOG,
                    event.ruleId(), event.ruleName(), event.severity(), kind, "日志库 " + store, detail, brief,
                    event.startedAt(), resolvedAt, "resolved".equals(kind) ? null : event.sample()));
        } catch (RuntimeException e) {
            log.warn("Cannot notify log alert {}: {}", event.id(), e.getMessage());
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
