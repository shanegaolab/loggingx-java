package com.loggingx.web.monitor;

import com.loggingx.biz.monitor.LogAlertService;
import com.loggingx.biz.notify.NotifyService;
import com.loggingx.biz.notify.NotifyService.Contact;
import com.loggingx.biz.notify.NotifyService.EmailSettings;
import com.loggingx.biz.notify.NotifyService.GroupView;
import com.loggingx.biz.notify.NotifyService.RuleNotify;
import com.loggingx.biz.notify.NotifyService.SmsSettings;
import com.loggingx.biz.permission.StoreAccessService;
import com.loggingx.common.api.ApiResponse;
import com.loggingx.common.util.DateTimes;
import com.loggingx.dao.account.UserAccount;
import com.loggingx.dao.log.LogStore;
import com.loggingx.dao.log.LogStoreRepository;
import com.loggingx.dao.monitor.LogAlertEvent;
import com.loggingx.dao.monitor.LogAlertRule;
import com.loggingx.dao.notify.NotifyRecord;
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

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The alert center beyond host monitoring: log alert rules and events, and how alerts are notified - email and SMS
 * settings, notification groups and the delivery records.
 */
@RestController
@RequestMapping("/alert-center")
public class AlertCenterController {

    /** Any page of the alert center: the store filter and the log rules are shown on the events page too. */
    private static final String ALERT_PAGES =
            "@permissionService.hasAnyMenu(authentication, 'AlertEvent', 'HostAlert', 'AlertNotify')";
    private static final String EVENTS = "@permissionService.hasAnyMenu(authentication, 'AlertEvent')";
    private static final String RULES = "@permissionService.hasAnyMenu(authentication, 'HostAlert')";
    private static final String NOTIFY = "@permissionService.hasAnyMenu(authentication, 'AlertNotify')";
    /** The records are also opened from an event, on the events page. */
    private static final String RECORD_READERS =
            "@permissionService.hasAnyMenu(authentication, 'AlertEvent', 'AlertNotify')";
    /** Users and groups are chosen as recipients of alert rules and of AI diagnosis notifications. */
    private static final String RECIPIENT_PICKERS =
            "@permissionService.hasAnyMenu(authentication, 'HostAlert', 'AlertNotify', 'AiSettings')";

    private final LogAlertService logAlertService;
    private final NotifyService notifyService;
    private final LogStoreRepository logStoreRepository;
    private final StoreAccessService storeAccessService;

    public AlertCenterController(LogAlertService logAlertService, NotifyService notifyService,
                                 LogStoreRepository logStoreRepository, StoreAccessService storeAccessService) {
        this.logAlertService = logAlertService;
        this.notifyService = notifyService;
        this.logStoreRepository = logStoreRepository;
        this.storeAccessService = storeAccessService;
    }

    // ---------- stores and users to choose from ----------

    public record StoreOption(Long id, String code, String name) {
    }

    /**
     * @param email masked, e.g. {@code zh***@example.com}, or {@code null} when the user has none
     * @param phone masked, e.g. {@code 138****8000}, or {@code null}
     */
    public record UserOption(Long id, String username, String nickName, String email, String phone,
                             boolean disabled) {
    }

    @GetMapping("/stores")
    @PreAuthorize(ALERT_PAGES)
    public ApiResponse<List<StoreOption>> stores(Authentication authentication) {
        return ApiResponse.success(storeAccessService.filter(authentication.getName(), logStoreRepository.findAll(),
                        LogStore::id).stream()
                .sorted(Comparator.comparing(LogStore::id))
                .map(store -> new StoreOption(store.id(), store.code(), store.name()))
                .toList());
    }

    @GetMapping("/users")
    @PreAuthorize(RECIPIENT_PICKERS)
    public ApiResponse<List<UserOption>> users() {
        return ApiResponse.success(notifyService.users().stream()
                .sorted(Comparator.comparing(UserAccount::id))
                .map(user -> new UserOption(user.id(), user.username(), user.nickName(), maskEmail(user.email()),
                        maskPhone(user.phone()), UserAccount.STATUS_DEACTIVATED.equals(user.status())))
                .toList());
    }

    static String maskEmail(String email) {
        if (email == null || email.isBlank() || !email.contains("@")) {
            return null;
        }
        int at = email.indexOf('@');
        return email.substring(0, Math.min(2, at)) + "***" + email.substring(at);
    }

    static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String digits = phone.trim();
        return digits.length() < 7 ? "***" : digits.substring(0, 3) + "****" + digits.substring(digits.length() - 4);
    }

    // ---------- log alert rules ----------

    public record LogAlertRuleRequest(String name, Long storeId, List<String> levels, String keyword, String host,
                                      Integer windowMinutes, Integer threshold, String severity, Boolean enabled,
                                      RuleNotify notification) {

        LogAlertRule toRule() {
            return new LogAlertRule(null, name, storeId, levels == null ? null : String.join(",", levels), keyword,
                    host, windowMinutes == null ? 5 : windowMinutes, threshold == null ? -1 : threshold, severity,
                    enabled == null || enabled, null, null);
        }
    }

    public record LogAlertRuleResponse(Long id, String name, Long storeId, String storeName, List<String> levels,
                                       String keyword, String host, int windowMinutes, int threshold,
                                       String severity, boolean enabled, String updateTime,
                                       RuleNotify notification) {
    }

    @GetMapping("/log-rules")
    @PreAuthorize(ALERT_PAGES)
    public ApiResponse<List<LogAlertRuleResponse>> logRules(Authentication authentication) {
        Map<Long, LogStore> stores = storeMap();
        Map<Long, RuleNotify> notifies = notifyService.ruleNotifies(NotifyService.RULE_LOG);
        return ApiResponse.success(storeAccessService.filter(authentication.getName(), logAlertService.rules(),
                        LogAlertRule::storeId).stream()
                .map(rule -> response(rule, stores, notifies.getOrDefault(rule.id(), RuleNotify.none())))
                .toList());
    }

    @PostMapping("/log-rules")
    @PreAuthorize(RULES)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<LogAlertRuleResponse> createLogRule(@RequestBody LogAlertRuleRequest request,
                                                           Authentication authentication) {
        storeAccessService.require(authentication.getName(), request.storeId());
        LogAlertRule rule = logAlertService.createRule(request.toRule(), request.notification(), authentication.getName());
        return ApiResponse.success(response(rule, storeMap(),
                notifyService.ruleNotify(NotifyService.RULE_LOG, rule.id())));
    }

    @PutMapping("/log-rules/{id}")
    @PreAuthorize(RULES)
    public ApiResponse<LogAlertRuleResponse> updateLogRule(@PathVariable Long id,
                                                           @RequestBody LogAlertRuleRequest request,
                                                           Authentication authentication) {
        requireRuleStore(authentication, id);
        storeAccessService.require(authentication.getName(), request.storeId());
        LogAlertRule rule = logAlertService.updateRule(id, request.toRule(), request.notification(),
                authentication.getName());
        return ApiResponse.success(response(rule, storeMap(), notifyService.ruleNotify(NotifyService.RULE_LOG, id)));
    }

    @DeleteMapping("/log-rules/{id}")
    @PreAuthorize(RULES)
    public ApiResponse<Void> deleteLogRule(@PathVariable Long id, Authentication authentication) {
        requireRuleStore(authentication, id);
        logAlertService.deleteRule(id, authentication.getName());
        return ApiResponse.success(null);
    }

    private void requireRuleStore(Authentication authentication, Long ruleId) {
        logAlertService.rules().stream().filter(rule -> rule.id().equals(ruleId)).findFirst()
                .ifPresent(rule -> storeAccessService.require(authentication.getName(), rule.storeId()));
    }

    private static LogAlertRuleResponse response(LogAlertRule rule, Map<Long, LogStore> stores,
                                                 RuleNotify notify) {
        LogStore store = stores.get(rule.storeId());
        return new LogAlertRuleResponse(rule.id(), rule.name(), rule.storeId(), store == null ? null : store.name(),
                rule.levels() == null ? List.of() : List.of(rule.levels().split(",")), rule.keyword(), rule.host(),
                rule.windowMinutes(), rule.threshold(), rule.severity(), rule.enabled(),
                DateTimes.format(rule.updatedAt()), notify);
    }

    // ---------- log alert events ----------

    public record LogAlertEventResponse(Long id, Long ruleId, String ruleName, Long storeId, String storeCode,
                                        String storeName, String severity, int threshold, int windowMinutes,
                                        Long value, Long peakValue, String sample, String status, long startedAt,
                                        Long resolvedAt, String startTime, String updateTime, String resolveTime) {
    }

    /**
     * @param status {@code firing}, {@code resolved}, or none for both
     * @param days   events started within this many days; firing events are always included
     */
    @GetMapping("/log-alerts")
    @PreAuthorize(EVENTS)
    public ApiResponse<List<LogAlertEventResponse>> logAlerts(@RequestParam(required = false) String status,
                                                              @RequestParam(required = false) Long storeId,
                                                              @RequestParam(defaultValue = "7") int days,
                                                              @RequestParam(defaultValue = "200") int limit,
                                                              Authentication authentication) {
        Map<Long, LogStore> stores = storeMap();
        return ApiResponse.success(storeAccessService.filter(authentication.getName(),
                        logAlertService.events(status, storeId, days, limit), LogAlertEvent::storeId).stream()
                .map((LogAlertEvent event) -> {
                    LogStore store = stores.get(event.storeId());
                    return new LogAlertEventResponse(event.id(), event.ruleId(), event.ruleName(), event.storeId(),
                            store == null ? null : store.code(), store == null ? null : store.name(),
                            event.severity(), event.threshold(), event.windowMinutes(), event.value(),
                            event.peakValue(), event.sample(), event.status(), event.startedAt().toEpochMilli(),
                            event.resolvedAt() == null ? null : event.resolvedAt().toEpochMilli(),
                            DateTimes.format(event.startedAt()), DateTimes.format(event.updatedAt()),
                            DateTimes.format(event.resolvedAt()));
                })
                .toList());
    }

    // ---------- notification channels ----------

    public record ChannelsResponse(EmailSettings email, SmsSettings sms) {
    }

    public record EmailTestRequest(EmailSettings settings, String to) {
    }

    public record SmsTestRequest(SmsSettings settings, String phone) {
    }

    @GetMapping("/notify/channels")
    @PreAuthorize(NOTIFY)
    public ApiResponse<ChannelsResponse> channels() {
        return ApiResponse.success(new ChannelsResponse(notifyService.email(), notifyService.sms()));
    }

    @PutMapping("/notify/email")
    @PreAuthorize(NOTIFY)
    public ApiResponse<EmailSettings> saveEmail(@RequestBody EmailSettings settings, Authentication authentication) {
        return ApiResponse.success(notifyService.saveEmail(settings, authentication.getName()));
    }

    @PutMapping("/notify/sms")
    @PreAuthorize(NOTIFY)
    public ApiResponse<SmsSettings> saveSms(@RequestBody SmsSettings settings, Authentication authentication) {
        return ApiResponse.success(notifyService.saveSms(settings, authentication.getName()));
    }

    @PostMapping("/notify/email/test")
    @PreAuthorize(NOTIFY)
    public ApiResponse<Void> testEmail(@RequestBody EmailTestRequest request) {
        notifyService.testEmail(request.settings(), request.to());
        return ApiResponse.success(null);
    }

    @PostMapping("/notify/sms/test")
    @PreAuthorize(NOTIFY)
    public ApiResponse<Void> testSms(@RequestBody SmsTestRequest request) {
        notifyService.testSms(request.settings(), request.phone());
        return ApiResponse.success(null);
    }

    // ---------- notification groups ----------

    public record GroupRequest(String name, String description, List<Long> userIds, List<Contact> contacts) {
    }

    public record GroupResponse(Long id, String name, String description, List<Long> userIds, List<Contact> contacts,
                                String updateTime) {

        static GroupResponse from(GroupView group) {
            return new GroupResponse(group.id(), group.name(), group.description(), group.userIds(),
                    group.contacts(), DateTimes.format(group.updatedAt()));
        }
    }

    @GetMapping("/notify/groups")
    @PreAuthorize(RECIPIENT_PICKERS)
    public ApiResponse<List<GroupResponse>> groups() {
        return ApiResponse.success(notifyService.groups().stream().map(GroupResponse::from).toList());
    }

    @PostMapping("/notify/groups")
    @PreAuthorize(NOTIFY)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<GroupResponse> createGroup(@RequestBody GroupRequest request, Authentication authentication) {
        return ApiResponse.success(GroupResponse.from(notifyService.saveGroup(null, request.name(),
                request.description(), request.userIds(), request.contacts(), authentication.getName())));
    }

    @PutMapping("/notify/groups/{id}")
    @PreAuthorize(NOTIFY)
    public ApiResponse<GroupResponse> updateGroup(@PathVariable Long id, @RequestBody GroupRequest request,
                                                  Authentication authentication) {
        return ApiResponse.success(GroupResponse.from(notifyService.saveGroup(id, request.name(),
                request.description(), request.userIds(), request.contacts(), authentication.getName())));
    }

    @DeleteMapping("/notify/groups/{id}")
    @PreAuthorize(NOTIFY)
    public ApiResponse<Void> deleteGroup(@PathVariable Long id, Authentication authentication) {
        notifyService.deleteGroup(id, authentication.getName());
        return ApiResponse.success(null);
    }

    // ---------- notification records ----------

    public record RecordResponse(Long id, String eventType, Long eventId, String ruleName, String kind,
                                 String channel, String recipient, String status, String error, String sendTime) {
    }

    /**
     * @param eventType {@code host}, {@code log}, {@code test}, or none for all
     * @param eventId   one alert's deliveries
     */
    @GetMapping("/notify/records")
    @PreAuthorize(RECORD_READERS)
    public ApiResponse<List<RecordResponse>> records(@RequestParam(required = false) String eventType,
                                                     @RequestParam(required = false) Long eventId,
                                                     @RequestParam(defaultValue = "200") int limit) {
        return ApiResponse.success(notifyService.records(eventType, eventId, limit).stream()
                .map((NotifyRecord record) -> new RecordResponse(record.id(), record.eventType(), record.eventId(),
                        record.ruleName(), record.kind(), record.channel(), record.recipient(), record.status(),
                        record.error(), DateTimes.format(record.sentAt())))
                .toList());
    }

    private Map<Long, LogStore> storeMap() {
        Map<Long, LogStore> stores = new HashMap<>();
        logStoreRepository.findAll().forEach(store -> stores.put(store.id(), store));
        return stores;
    }
}
