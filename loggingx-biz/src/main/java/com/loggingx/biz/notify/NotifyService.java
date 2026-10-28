package com.loggingx.biz.notify;

import com.loggingx.biz.notify.SmsSender.SmsParams;
import com.loggingx.biz.support.SecretCipher;
import com.loggingx.common.exception.BadRequestException;
import com.loggingx.common.exception.ConflictException;
import com.loggingx.common.exception.NotFoundException;
import com.loggingx.dao.account.UserAccount;
import com.loggingx.dao.account.UserAccountRepository;
import com.loggingx.dao.notify.AlertNotify;
import com.loggingx.dao.notify.NotifyChannel;
import com.loggingx.dao.notify.NotifyGroup;
import com.loggingx.dao.notify.NotifyMapper;
import com.loggingx.dao.notify.NotifyRecord;
import com.loggingx.dao.support.GeneratedKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Alert notifications by email (SMTP) and SMS (Alibaba Cloud or Tencent Cloud).
 * <p>
 * Each alert rule chooses its channels and recipients (system users and notification groups, whose members are users
 * and other contacts). An alert is notified when it fires, optionally again every {@code repeatMinutes} while it keeps
 * firing, and when it resolves if the rule asks for it. Notifications are sent in the background, one at a time, and
 * every delivery is recorded with its outcome.
 */
@Service
public class NotifyService implements DisposableBean {

    public static final String EMAIL = "email";
    public static final String SMS = "sms";
    public static final Set<String> CHANNELS = Set.of(EMAIL, SMS);
    public static final Set<String> SMS_PROVIDERS = Set.of("aliyun", "tencent");
    /** Rule and event types: host monitoring alerts and log alerts. */
    public static final String RULE_HOST = "host";
    public static final String RULE_LOG = "log";
    /** AI diagnosis notifications: their settings are kept as rule {@code ai} / 0. */
    public static final String RULE_AI = "ai";
    /** The notice kind of an AI diagnosis. */
    public static final String DIAGNOSIS = "diagnosis";

    private static final Logger log = LoggerFactory.getLogger(NotifyService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern EMAIL_ADDRESS = Pattern.compile("[^\\s@<>,;]+@[^\\s@<>,;]+\\.[^\\s@<>,;]+");
    private static final Pattern PHONE = Pattern.compile("\\+?[\\d\\s()-]{6,20}");
    private static final Duration RECORDS_KEPT = Duration.ofDays(90);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final NotifyMapper notifyMapper;
    private final UserAccountRepository userRepository;
    private final SecretCipher cipher;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "loggingx-notify");
        thread.setDaemon(true);
        return thread;
    });

    public NotifyService(NotifyMapper notifyMapper, UserAccountRepository userRepository, SecretCipher cipher) {
        this.notifyMapper = notifyMapper;
        this.userRepository = userRepository;
        this.cipher = cipher;
    }

    @Override
    public void destroy() {
        worker.shutdownNow();
    }

    // ---------- channel settings ----------

    /**
     * SMTP settings; {@code password} is never returned ({@code passwordSaved} tells whether one is saved).
     *
     * @param security {@code ssl} (port 465), {@code starttls} (587) or {@code none} (25)
     */
    public record EmailSettings(Boolean enabled, String host, Integer port, String security, String username,
                                String password, Boolean passwordSaved, String from, String fromName,
                                Instant updatedAt) {

        public EmailSettings {
            enabled = Boolean.TRUE.equals(enabled);
            port = port == null ? 465 : port;
            passwordSaved = Boolean.TRUE.equals(passwordSaved);
        }
    }

    /**
     * SMS settings; {@code secret} is never returned.
     *
     * @param provider    {@code aliyun} or {@code tencent}
     * @param accessKeyId Alibaba Cloud AccessKey ID or Tencent Cloud SecretId
     * @param secret      Alibaba Cloud AccessKey Secret or Tencent Cloud SecretKey
     * @param templateId  Alibaba Cloud template code (SMS_123) or Tencent Cloud template id
     * @param sdkAppId    Tencent Cloud only: the SMS application id
     * @param region      Tencent Cloud only, e.g. ap-guangzhou
     * @param endpoint    API endpoint override (private network or proxy); empty for the public one
     */
    public record SmsSettings(Boolean enabled, String provider, String accessKeyId, String secret,
                              Boolean secretSaved, String signName, String templateId, String sdkAppId,
                              String region, String endpoint, Instant updatedAt) {

        public SmsSettings {
            enabled = Boolean.TRUE.equals(enabled);
            secretSaved = Boolean.TRUE.equals(secretSaved);
        }
    }

    private record EmailConfig(String host, int port, String security, String username, String from,
                               String fromName) {
    }

    private record SmsConfig(String provider, String accessKeyId, String signName, String templateId,
                             String sdkAppId, String region, String endpoint) {
    }

    public EmailSettings email() {
        NotifyChannel channel = notifyMapper.selectChannel(EMAIL);
        if (channel == null) {
            return new EmailSettings(false, "", 465, "ssl", "", null, false, "", "LoggingX", null);
        }
        EmailConfig config = read(channel.config(), EmailConfig.class);
        return new EmailSettings(channel.enabled(), config.host(), config.port(), config.security(),
                config.username(), null, channel.secret() != null, config.from(), config.fromName(),
                channel.updatedAt());
    }

    public SmsSettings sms() {
        NotifyChannel channel = notifyMapper.selectChannel(SMS);
        if (channel == null) {
            return new SmsSettings(false, "aliyun", "", null, false, "", "", "", "ap-guangzhou", "", null);
        }
        SmsConfig config = read(channel.config(), SmsConfig.class);
        return new SmsSettings(channel.enabled(), config.provider(), config.accessKeyId(), null,
                channel.secret() != null, config.signName(), config.templateId(), config.sdkAppId(),
                config.region(), config.endpoint(), channel.updatedAt());
    }

    /**
     * @param settings {@code password} empty keeps the saved one
     */
    public EmailSettings saveEmail(EmailSettings settings, String operatorUsername) {
        EmailConfig config = validate(settings);
        String secret = secret(EMAIL, settings.password());
        saveChannel(new NotifyChannel(EMAIL, settings.enabled(), JSON.writeValueAsString(config), secret,
                Instant.now()));
        log.info("event=notify.email.update operator={} host={} enabled={}", operatorUsername, config.host(),
                settings.enabled());
        return email();
    }

    /**
     * @param settings {@code secret} empty keeps the saved one
     */
    public SmsSettings saveSms(SmsSettings settings, String operatorUsername) {
        SmsConfig config = validate(settings);
        String secret = secret(SMS, settings.secret());
        if (secret == null) {
            throw new BadRequestException("The access key secret is required");
        }
        saveChannel(new NotifyChannel(SMS, settings.enabled(), JSON.writeValueAsString(config), secret,
                Instant.now()));
        log.info("event=notify.sms.update operator={} provider={} enabled={}", operatorUsername, config.provider(),
                settings.enabled());
        return sms();
    }

    /**
     * Sends a test message with the given settings (not saved), to one address or phone number.
     *
     * @throws BadRequestException with the server's or provider's answer when it fails
     */
    public void testEmail(EmailSettings settings, String to) {
        EmailConfig config = validate(settings);
        String password = settings.password() == null || settings.password().isEmpty()
                ? savedSecret(EMAIL) : settings.password();
        if (to == null || !EMAIL_ADDRESS.matcher(to.trim()).matches()) {
            throw new BadRequestException("Enter the email address to send the test to");
        }
        try {
            smtp(config, password).send(List.of(to.trim()), "[LoggingX] 告警通知测试",
                    html("测试", "#2563eb", "这是一封测试邮件", List.of(
                            Map.entry("说明", "收到这封邮件，说明 LoggingX 的邮件告警配置正确。"),
                            Map.entry("发送时间", TIME.format(Instant.now())))));
            record("test", null, "测试", "test", EMAIL, to.trim(), null);
        } catch (SmtpClient.SmtpException e) {
            record("test", null, "测试", "test", EMAIL, to.trim(), e.getMessage());
            throw new BadRequestException(e.getMessage());
        }
    }

    public void testSms(SmsSettings settings, String phone) {
        SmsConfig config = validate(settings);
        String secret = settings.secret() == null || settings.secret().isEmpty() ? savedSecret(SMS)
                : settings.secret();
        if (phone == null || !PHONE.matcher(phone.trim()).matches()) {
            throw new BadRequestException("Enter the phone number to send the test to");
        }
        try {
            sender(config, secret).send(List.of(phone.trim()), new SmsParams("测试", "告警通知测试", "LoggingX",
                    "测试", "短信配置正确"));
            record("test", null, "测试", "test", SMS, phone.trim(), null);
        } catch (SmsSender.SmsException e) {
            record("test", null, "测试", "test", SMS, phone.trim(), e.getMessage());
            throw new BadRequestException(e.getMessage());
        }
    }

    private EmailConfig validate(EmailSettings settings) {
        String host = trim(settings.host());
        if (host.isEmpty() || !host.matches("[\\w.-]+")) {
            throw new BadRequestException("The SMTP host is required, e.g. smtp.qq.com");
        }
        if (settings.port() < 1 || settings.port() > 65535) {
            throw new BadRequestException("The SMTP port must be between 1 and 65535");
        }
        String security = trim(settings.security()).isEmpty() ? "ssl" : trim(settings.security());
        if (!Set.of("ssl", "starttls", "none").contains(security)) {
            throw new BadRequestException("security must be ssl, starttls or none");
        }
        String from = trim(settings.from());
        if (!EMAIL_ADDRESS.matcher(from).matches()) {
            throw new BadRequestException("The sender address is not an email address");
        }
        return new EmailConfig(host, settings.port(), security, trim(settings.username()), from,
                trim(settings.fromName()).isEmpty() ? "LoggingX" : trim(settings.fromName()));
    }

    private SmsConfig validate(SmsSettings settings) {
        String provider = trim(settings.provider());
        if (!SMS_PROVIDERS.contains(provider)) {
            throw new BadRequestException("provider must be aliyun or tencent");
        }
        String endpoint = trim(settings.endpoint()).replaceAll("/+$", "");
        if (!endpoint.isEmpty() && !endpoint.matches("https?://[\\w.:-]+")) {
            throw new BadRequestException("endpoint must be an http(s) address without a path");
        }
        if (trim(settings.accessKeyId()).isEmpty() || trim(settings.signName()).isEmpty()
                || trim(settings.templateId()).isEmpty()) {
            throw new BadRequestException("The access key, signature name and template are required");
        }
        if ("tencent".equals(provider) && trim(settings.sdkAppId()).isEmpty()) {
            throw new BadRequestException("The SMS SdkAppId is required for Tencent Cloud");
        }
        return new SmsConfig(provider, trim(settings.accessKeyId()), trim(settings.signName()),
                trim(settings.templateId()), trim(settings.sdkAppId()),
                trim(settings.region()).isEmpty() ? "ap-guangzhou" : trim(settings.region()), endpoint);
    }

    /**
     * @return the new secret encrypted, or the saved one when none is given
     */
    private String secret(String channel, String plain) {
        if (plain != null && !plain.isEmpty()) {
            return cipher.encrypt(plain);
        }
        NotifyChannel saved = notifyMapper.selectChannel(channel);
        return saved == null ? null : saved.secret();
    }

    private String savedSecret(String channel) {
        NotifyChannel saved = notifyMapper.selectChannel(channel);
        if (saved == null || saved.secret() == null) {
            return "";
        }
        try {
            return cipher.decrypt(saved.secret());
        } catch (IllegalStateException e) {
            throw new BadRequestException(e.getMessage());
        }
    }

    private void saveChannel(NotifyChannel channel) {
        if (notifyMapper.updateChannel(channel) == 0) {
            try {
                notifyMapper.insertChannel(channel);
            } catch (DuplicateKeyException e) {
                notifyMapper.updateChannel(channel);
            }
        }
    }

    private static SmtpClient smtp(EmailConfig config, String password) {
        return new SmtpClient(new SmtpClient.Server(config.host(), config.port(), config.security(),
                config.username(), password, config.from(), config.fromName()));
    }

    private static SmsSender sender(SmsConfig config, String secret) {
        String endpoint = config.endpoint() == null || config.endpoint().isEmpty() ? null : config.endpoint();
        return "tencent".equals(config.provider())
                ? new TencentSmsSender(endpoint, config.region(), config.accessKeyId(), secret, config.sdkAppId(),
                config.signName(), config.templateId())
                : new AliyunSmsSender(endpoint, config.accessKeyId(), secret, config.signName(),
                config.templateId());
    }

    // ---------- groups ----------

    public record Contact(String name, String email, String phone) {
    }

    public record GroupView(Long id, String name, String description, List<Long> userIds, List<Contact> contacts,
                            Instant updatedAt) {
    }

    public List<GroupView> groups() {
        return notifyMapper.selectGroups().stream().map(NotifyService::view).toList();
    }

    public GroupView saveGroup(Long id, String name, String description, List<Long> userIds, List<Contact> contacts,
                               String operatorUsername) {
        String cleanName = trim(name);
        if (cleanName.isEmpty() || cleanName.length() > 64) {
            throw new BadRequestException("name must be 1-64 characters");
        }
        List<Contact> cleanContacts = new ArrayList<>();
        for (Contact contact : contacts == null ? List.<Contact>of() : contacts) {
            String email = trim(contact.email());
            String phone = trim(contact.phone());
            if (email.isEmpty() && phone.isEmpty()) {
                continue;
            }
            if (!email.isEmpty() && !EMAIL_ADDRESS.matcher(email).matches()) {
                throw new BadRequestException("'" + email + "' is not an email address");
            }
            if (!phone.isEmpty() && !PHONE.matcher(phone).matches()) {
                throw new BadRequestException("'" + phone + "' is not a phone number");
            }
            cleanContacts.add(new Contact(trim(contact.name()), email, phone));
        }
        List<Long> users = userIds == null ? List.of() : userIds.stream().distinct().toList();
        Instant now = Instant.now();
        String description1 = trim(description).isEmpty() ? null : trim(description);
        try {
            if (id == null) {
                NotifyGroup group = new NotifyGroup(null, cleanName, description1, JSON.writeValueAsString(users),
                        JSON.writeValueAsString(cleanContacts), now, now);
                GeneratedKey key = new GeneratedKey();
                notifyMapper.insertGroup(group, key);
                id = key.getId();
            } else {
                NotifyGroup existing = requireGroup(id);
                notifyMapper.updateGroup(new NotifyGroup(id, cleanName, description1, JSON.writeValueAsString(users),
                        JSON.writeValueAsString(cleanContacts), existing.createdAt(), now));
            }
        } catch (DuplicateKeyException e) {
            throw new ConflictException("A notification group named '" + cleanName + "' already exists");
        }
        log.info("event=notify.group.save operator={} groupId={} users={} contacts={}", operatorUsername, id,
                users.size(), cleanContacts.size());
        return view(requireGroup(id));
    }

    public void deleteGroup(Long id, String operatorUsername) {
        requireGroup(id);
        notifyMapper.deleteGroup(id);
        log.info("event=notify.group.delete operator={} groupId={}", operatorUsername, id);
    }

    private NotifyGroup requireGroup(Long id) {
        NotifyGroup group = notifyMapper.selectGroup(id);
        if (group == null) {
            throw new NotFoundException("Notification group was not found");
        }
        return group;
    }

    private static GroupView view(NotifyGroup group) {
        return new GroupView(group.id(), group.name(), group.description(), longs(group.userIds()),
                read(group.contacts(), new TypeReference<List<Contact>>() {
                }, List.of()), group.updatedAt());
    }

    // ---------- rule notification settings ----------

    /**
     * How a rule notifies.
     *
     * @param channels       {@code email} and/or {@code sms}; empty for no notification
     * @param repeatMinutes  minutes between reminders while the alert fires; 0 for none
     */
    public record RuleNotify(Set<String> channels, List<Long> userIds, List<Long> groupIds, Boolean notifyResolved,
                             Integer repeatMinutes) {

        public RuleNotify {
            channels = channels == null ? Set.of() : new LinkedHashSet<>(channels);
            userIds = userIds == null ? List.of() : userIds.stream().filter(java.util.Objects::nonNull).toList();
            groupIds = groupIds == null ? List.of() : groupIds.stream().filter(java.util.Objects::nonNull).toList();
            notifyResolved = notifyResolved == null || notifyResolved;
            repeatMinutes = repeatMinutes == null ? 0 : repeatMinutes;
        }

        public static RuleNotify none() {
            return new RuleNotify(Set.of(), List.of(), List.of(), true, 0);
        }
    }

    public RuleNotify ruleNotify(String ruleType, Long ruleId) {
        AlertNotify notify = notifyMapper.selectRuleNotify(ruleType, ruleId);
        return notify == null ? RuleNotify.none() : view(notify);
    }

    public Map<Long, RuleNotify> ruleNotifies(String ruleType) {
        Map<Long, RuleNotify> map = new HashMap<>();
        notifyMapper.selectRuleNotifies(ruleType).forEach(notify -> map.put(notify.ruleId(), view(notify)));
        return map;
    }

    /**
     * @throws BadRequestException when a channel or the repeat interval is invalid, or channels lack recipients
     */
    public void validate(RuleNotify notify) {
        if (notify == null) {
            return;
        }
        for (String channel : notify.channels()) {
            if (!CHANNELS.contains(channel)) {
                throw new BadRequestException("channels may only be email and sms");
            }
        }
        if (notify.repeatMinutes() < 0 || notify.repeatMinutes() > 1440) {
            throw new BadRequestException("repeatMinutes must be between 0 and 1440");
        }
        if (!notify.channels().isEmpty() && notify.userIds().isEmpty() && notify.groupIds().isEmpty()) {
            throw new BadRequestException("Choose who is notified: users or notification groups");
        }
    }

    public void saveRuleNotify(String ruleType, Long ruleId, RuleNotify notify) {
        validate(notify);
        if (notify == null) {
            return;
        }
        AlertNotify row = new AlertNotify(ruleType, ruleId, String.join(",", new LinkedHashSet<>(notify.channels())),
                JSON.writeValueAsString(notify.userIds()), JSON.writeValueAsString(notify.groupIds()),
                notify.notifyResolved(), notify.repeatMinutes());
        if (notifyMapper.updateRuleNotify(row) == 0) {
            try {
                notifyMapper.insertRuleNotify(row);
            } catch (DuplicateKeyException e) {
                notifyMapper.updateRuleNotify(row);
            }
        }
    }

    public void deleteRuleNotify(String ruleType, Long ruleId) {
        notifyMapper.deleteRuleNotify(ruleType, ruleId);
    }

    private static RuleNotify view(AlertNotify notify) {
        Set<String> channels = new LinkedHashSet<>();
        if (notify.channels() != null && !notify.channels().isBlank()) {
            channels.addAll(Arrays.asList(notify.channels().split(",")));
        }
        return new RuleNotify(channels, longs(notify.userIds()), longs(notify.groupIds()), notify.notifyResolved(),
                notify.repeatMinutes());
    }

    // ---------- sending ----------

    /**
     * An alert to notify.
     *
     * @param eventType {@code host} or {@code log}
     * @param kind      {@code firing}, {@code resolved} or {@code repeat}
     * @param target    what fired, e.g. the host and its store
     * @param detail    the condition and value, e.g. {@code CPU 使用率 95.2%（阈值 90%）}
     * @param extra     more context for the email, e.g. the newest matching log line; may be {@code null}
     */
    public record AlertNotice(String eventType, Long eventId, String ruleType, Long ruleId, String ruleName,
                              String severity, String kind, String target, String detail, String smsDetail,
                              Instant startedAt, Instant resolvedAt, String extra) {
    }

    /**
     * Queues the notification of an alert by its rule's channels; nothing happens when the rule does not notify, or
     * when it does not notify resolved alerts and the alert resolved.
     */
    public void notify(AlertNotice notice) {
        RuleNotify config = ruleNotify(notice.ruleType(), notice.ruleId());
        if (config.channels().isEmpty() || ("resolved".equals(notice.kind()) && !config.notifyResolved())) {
            return;
        }
        worker.submit(() -> {
            try {
                deliver(notice, config);
            } catch (RuntimeException e) {
                log.warn("Cannot notify alert {} {}: {}", notice.eventType(), notice.eventId(), e.getMessage(), e);
            }
        });
    }

    /**
     * @return whether a firing alert is due for a reminder: its rule repeats, and it was last notified at least
     * {@code repeatMinutes} ago
     */
    public boolean repeatDue(String eventType, Long eventId, String ruleType, Long ruleId, Instant now) {
        RuleNotify config = ruleNotify(ruleType, ruleId);
        if (config.channels().isEmpty() || config.repeatMinutes() <= 0) {
            return false;
        }
        Instant last = notifyMapper.selectLastSent(eventType, eventId);
        return last != null && !last.plus(Duration.ofMinutes(config.repeatMinutes())).isAfter(now);
    }

    private void deliver(AlertNotice notice, RuleNotify config) {
        Set<String> emails = new LinkedHashSet<>();
        Set<String> phones = new LinkedHashSet<>();
        recipients(config, emails, phones);
        if (config.channels().contains(EMAIL)) {
            deliverEmail(notice, emails);
        }
        if (config.channels().contains(SMS)) {
            deliverSms(notice, phones);
        }
    }

    private void recipients(RuleNotify config, Set<String> emails, Set<String> phones) {
        Set<Long> userIds = new LinkedHashSet<>(config.userIds());
        for (Long groupId : config.groupIds()) {
            NotifyGroup group = notifyMapper.selectGroup(groupId);
            if (group == null) {
                continue;
            }
            GroupView view = view(group);
            userIds.addAll(view.userIds());
            for (Contact contact : view.contacts()) {
                addIfPresent(emails, contact.email());
                addIfPresent(phones, contact.phone());
            }
        }
        for (Long userId : userIds) {
            userRepository.findById(userId)
                    .filter(user -> !UserAccount.STATUS_DEACTIVATED.equals(user.status()))
                    .ifPresent(user -> {
                        addIfPresent(emails, user.email());
                        addIfPresent(phones, user.phone());
                    });
        }
    }

    private void deliverEmail(AlertNotice notice, Set<String> emails) {
        NotifyChannel channel = notifyMapper.selectChannel(EMAIL);
        if (emails.isEmpty()) {
            record(notice, EMAIL, "-", "No recipient has an email address");
            return;
        }
        if (channel == null || !channel.enabled()) {
            emails.forEach(email -> record(notice, EMAIL, email, "Email notification is not configured or is off"));
            return;
        }
        EmailConfig config = read(channel.config(), EmailConfig.class);
        String status = statusText(notice.kind());
        String color = "resolved".equals(notice.kind()) ? "#16a34a"
                : DIAGNOSIS.equals(notice.kind()) ? "#7c3aed"
                : "critical".equals(notice.severity()) ? "#dc2626" : "#d97706";
        Map<String, String> rows = new LinkedHashMap<>();
        if (DIAGNOSIS.equals(notice.kind())) {
            rows.put("错误", notice.ruleName());
            rows.put("日志库", notice.target());
            rows.put("结论", notice.detail());
            rows.put("出错时间", TIME.format(notice.startedAt()));
            if (notice.extra() != null && !notice.extra().isBlank()) {
                rows.put("修改建议", notice.extra());
            }
        } else {
            alertRows(notice, rows);
        }
        String subject = DIAGNOSIS.equals(notice.kind())
                ? "[LoggingX][AI 诊断] " + notice.ruleName() + " - " + notice.target()
                : "[LoggingX][" + severityText(notice.severity()) + "][" + status + "] " + notice.ruleName()
                + " - " + notice.target();
        String body = html(status, color, notice.ruleName(), new ArrayList<>(rows.entrySet()));
        String password;
        try {
            password = channel.secret() == null ? "" : cipher.decrypt(channel.secret());
        } catch (IllegalStateException e) {
            emails.forEach(email -> record(notice, EMAIL, email, e.getMessage()));
            return;
        }
        SmtpClient client = smtp(config, password);
        for (String email : emails) {
            try {
                client.send(List.of(email), subject, body);
                record(notice, EMAIL, email, null);
            } catch (SmtpClient.SmtpException e) {
                record(notice, EMAIL, email, e.getMessage());
            }
        }
    }

    private static void alertRows(AlertNotice notice, Map<String, String> rows) {
        rows.put("告警规则", notice.ruleName());
        rows.put("级别", severityText(notice.severity()));
        rows.put("对象", notice.target());
        rows.put("详情", notice.detail());
        rows.put("开始时间", TIME.format(notice.startedAt()));
        if (notice.resolvedAt() != null) {
            rows.put("恢复时间", TIME.format(notice.resolvedAt()));
            rows.put("持续", duration(Duration.between(notice.startedAt(), notice.resolvedAt())));
        } else {
            rows.put("已持续", duration(Duration.between(notice.startedAt(), Instant.now())));
        }
        if (notice.extra() != null && !notice.extra().isBlank()) {
            rows.put("示例日志", notice.extra());
        }
    }

    private void deliverSms(AlertNotice notice, Set<String> phones) {
        NotifyChannel channel = notifyMapper.selectChannel(SMS);
        if (phones.isEmpty()) {
            record(notice, SMS, "-", "No recipient has a phone number");
            return;
        }
        if (channel == null || !channel.enabled()) {
            phones.forEach(phone -> record(notice, SMS, phone, "SMS notification is not configured or is off"));
            return;
        }
        SmsConfig config = read(channel.config(), SmsConfig.class);
        String error = null;
        try {
            sender(config, cipher.decrypt(channel.secret())).send(new ArrayList<>(phones),
                    new SmsParams(severityText(notice.severity()), notice.ruleName(), notice.target(),
                            statusText(notice.kind()), notice.smsDetail()));
        } catch (SmsSender.SmsException | IllegalStateException e) {
            error = e.getMessage();
        }
        for (String phone : phones) {
            record(notice, SMS, phone, error);
        }
    }

    // ---------- records ----------

    public List<NotifyRecord> records(String eventType, Long eventId, int limit) {
        return notifyMapper.selectRecords(eventType == null || eventType.isBlank() ? null : eventType, eventId,
                Math.max(1, Math.min(limit, 1000)));
    }

    @Scheduled(cron = "0 15 4 * * *")
    public void purgeRecords() {
        notifyMapper.deleteRecordsBefore(Instant.now().minus(RECORDS_KEPT));
    }

    private void record(AlertNotice notice, String channel, String recipient, String error) {
        record(notice.eventType(), notice.eventId(), notice.ruleName(), notice.kind(), channel, recipient, error);
    }

    private void record(String eventType, Long eventId, String ruleName, String kind, String channel,
                        String recipient, String error) {
        notifyMapper.insertRecord(new NotifyRecord(null, eventType, eventId, ruleName, kind, channel, recipient,
                error == null ? "success" : "failed", error == null ? null : truncate(error), Instant.now()));
        if (error != null) {
            log.warn("event=notify.failed channel={} recipient={} rule=\"{}\" error={}", channel, recipient,
                    ruleName, error);
        }
    }

    // ---------- content ----------

    /**
     * A small, inline-styled HTML mail (mail clients ignore style sheets).
     */
    static String html(String badge, String color, String title, List<Map.Entry<String, String>> rows) {
        StringBuilder table = new StringBuilder();
        for (Map.Entry<String, String> row : rows) {
            boolean code = "示例日志".equals(row.getKey()) || "修改建议".equals(row.getKey());
            table.append("<tr><td style=\"padding:8px 12px;color:#64748b;white-space:nowrap;vertical-align:top;"
                            + "border-top:1px solid #eef2f7\">").append(escape(row.getKey()))
                    .append("</td><td style=\"padding:8px 12px;color:#0f172a;border-top:1px solid #eef2f7;")
                    .append(code ? "font-family:Consolas,monospace;font-size:12px;white-space:pre-wrap;"
                            + "word-break:break-all" : "").append("\">")
                    .append(escape(row.getValue() == null ? "-" : row.getValue()))
                    .append("</td></tr>");
        }
        return "<div style=\"font-family:-apple-system,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;"
                + "background:#f6f8fb;padding:24px\"><div style=\"max-width:640px;margin:0 auto;background:#fff;"
                + "border:1px solid #e5e9f0;border-radius:10px;overflow:hidden\">"
                + "<div style=\"padding:16px 20px;border-top:4px solid " + color + "\">"
                + "<span style=\"display:inline-block;padding:2px 8px;border-radius:4px;color:#fff;font-size:12px;"
                + "background:" + color + "\">" + escape(badge) + "</span>"
                + "<div style=\"margin-top:8px;font-size:18px;font-weight:600;color:#0f172a\">"
                + escape(title) + "</div></div>"
                + "<table style=\"width:100%;border-collapse:collapse;font-size:14px\">" + table + "</table>"
                + "<div style=\"padding:12px 20px;color:#94a3b8;font-size:12px;background:#fafbfc\">"
                + "此邮件由 LoggingX 告警中心自动发送</div></div></div>";
    }

    static String statusText(String kind) {
        return switch (kind) {
            case "resolved" -> "已恢复";
            case "repeat" -> "持续告警";
            case "test" -> "测试";
            case DIAGNOSIS -> "AI 建议";
            default -> "告警";
        };
    }

    static String severityText(String severity) {
        return switch (severity == null ? "" : severity) {
            case "critical" -> "严重";
            case "info" -> "提示";
            default -> "警告";
        };
    }

    static String duration(Duration duration) {
        long minutes = Math.max(0, duration.toMinutes());
        if (minutes < 60) {
            return minutes + " 分钟";
        }
        if (minutes < 1440) {
            return minutes / 60 + " 小时 " + minutes % 60 + " 分";
        }
        return minutes / 1440 + " 天 " + minutes % 1440 / 60 + " 小时";
    }

    // ---------- helpers ----------

    private static List<Long> longs(String json) {
        return read(json, new TypeReference<List<Long>>() {
        }, List.of());
    }

    private static <T> T read(String json, TypeReference<T> type, T fallback) {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            return JSON.readValue(json, type);
        } catch (JacksonException e) {
            return fallback;
        }
    }

    private static <T> T read(String json, Class<T> type) {
        return JSON.readValue(json, type);
    }

    static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static void addIfPresent(Set<String> values, String value) {
        if (value != null && !value.isBlank()) {
            values.add(value.trim());
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String truncate(String message) {
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    /**
     * @return the system users that can be chosen as recipients, with whether they have an email address and phone
     */
    public List<UserAccount> users() {
        return userRepository.findAll();
    }
}
