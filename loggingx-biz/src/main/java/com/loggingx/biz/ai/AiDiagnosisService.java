package com.loggingx.biz.ai;

import com.loggingx.ai.AiChatCommand;
import com.loggingx.ai.AiChatReply;
import com.loggingx.ai.AiChatService;
import com.loggingx.ai.AiProvider;
import com.loggingx.biz.ai.CodeRepositories.CodeRef;
import com.loggingx.biz.ai.CodeRepositories.RepoException;
import com.loggingx.biz.ai.CodeRepositories.SyncResult;
import com.loggingx.biz.ai.StackTraces.Frame;
import com.loggingx.biz.ai.StackTraces.ParsedError;
import com.loggingx.biz.monitor.LogAlertFired;
import com.loggingx.biz.notify.NotifyService;
import com.loggingx.biz.notify.NotifyService.AlertNotice;
import com.loggingx.biz.notify.NotifyService.RuleNotify;
import com.loggingx.biz.support.SecretCipher;
import com.loggingx.common.exception.BadRequestException;
import com.loggingx.common.exception.NotFoundException;
import com.loggingx.common.util.DateTimes;
import com.loggingx.dao.ai.AiDiagnosis;
import com.loggingx.dao.ai.AiMapper;
import com.loggingx.dao.ai.AiSettings;
import com.loggingx.dao.ai.AiStoreConfig;
import com.loggingx.dao.ai.ErrorGroup;
import com.loggingx.dao.log.LogRecord;
import com.loggingx.dao.log.LogRecordRepository;
import com.loggingx.dao.log.LogSearch;
import com.loggingx.dao.log.LogStore;
import com.loggingx.dao.log.LogStoreRepository;
import com.loggingx.dao.support.GeneratedKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Analyses errors with an AI model and the code they come from, and suggests fixes.
 * <p>
 * Each store can link a Git repository. Its ERROR and FATAL logs are grouped by fingerprint (error type and the
 * method it was raised in, see {@link StackTraces}); a kind of error seen for the first time is analysed
 * automatically when the store asks for it, as is the newest matching log of a log alert that fires, and anyone may
 * ask for an analysis of a log. An analysis finds the stack frames' files in the repository, sends the log with the
 * code around those lines to the model, and keeps its answer: a one-line summary, the root cause, a suggestion and
 * a diff. Finished analyses are notified by the channels set for AI diagnosis. Automatic analyses are limited per day.
 */
@Service
public class AiDiagnosisService implements DisposableBean {

    public static final String AUTO = "auto";
    public static final String ALERT = "alert";
    public static final String MANUAL = "manual";
    public static final Set<String> TRIGGERS = Set.of(AUTO, ALERT, MANUAL);
    public static final Set<String> REVIEW_STATES = Set.of("open", "resolved", "ignored");

    private static final Logger log = LoggerFactory.getLogger(AiDiagnosisService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration RESYNC_AFTER = Duration.ofMinutes(5);
    /** Logs this recent may still be arriving; they are grouped on the next scan. */
    private static final Duration SCAN_DELAY = Duration.ofSeconds(10);
    private static final Duration FIRST_SCAN = Duration.ofMinutes(5);
    private static final int SCAN_PAGE = 500;
    private static final int SCAN_MAX = 2000;
    private static final int LOG_TEXT_MAX = 8000;
    private static final int CODE_REFS = 5;
    private static final int CODE_CONTEXT = 30;
    private static final int CODE_CHARS = 24_000;
    private static final Pattern BRANCH = Pattern.compile("[\\w./-]{1,128}");
    private static final ZoneId ZONE = ZoneId.systemDefault();

    static final String SYSTEM_PROMPT = """
            你是一名资深软件工程师，负责排查线上错误。根据错误日志和相关源代码，找出最可能的根本原因，并给出具体的修改建议。
            要求：
            - 只依据给出的日志和代码推断；信息不足以确定时如实说明，并降低置信度。
            - 修改建议要具体到文件和方法，说明为什么这样改。
            - 用中文回答。
            只输出一个 JSON 对象，不要输出其他内容。字段：
            summary：一句话结论，不超过 60 个字；
            rootCause：根本原因分析；
            suggestion：修改建议，Markdown 格式；
            diff：统一 diff 格式（unified diff）的修改，文件路径用仓库内的路径；给不出具体修改时为空字符串；
            confidence：high、medium 或 low。""";

    private final AiMapper mapper;
    private final AiChatService chatService;
    private final CodeRepositories repositories;
    private final LogRecordRepository recordRepository;
    private final LogStoreRepository storeRepository;
    private final NotifyService notifyService;
    private final SecretCipher cipher;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "loggingx-ai-diagnosis");
        thread.setDaemon(true);
        return thread;
    });

    public AiDiagnosisService(AiMapper mapper, AiChatService chatService, CodeRepositories repositories,
                              LogRecordRepository recordRepository, LogStoreRepository storeRepository,
                              NotifyService notifyService, SecretCipher cipher) {
        this.mapper = mapper;
        this.chatService = chatService;
        this.repositories = repositories;
        this.recordRepository = recordRepository;
        this.storeRepository = storeRepository;
        this.notifyService = notifyService;
        this.cipher = cipher;
    }

    @Override
    public void destroy() {
        worker.shutdownNow();
    }

    /**
     * Analyses that were waiting or running when the application stopped will not finish.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void failUnfinished() {
        try {
            mapper.failUnfinished("LoggingX restarted before the analysis finished; analyse it again", Instant.now());
        } catch (RuntimeException e) {
            log.warn("Cannot mark unfinished AI diagnoses: {}", e.getMessage());
        }
    }

    // ---------- settings ----------

    /**
     * @param providers  the configured AI providers
     * @param usedToday  automatic analyses started today
     */
    public record SettingsView(String provider, String model, int dailyLimit, List<String> providers, int usedToday,
                               RuleNotify notification) {
    }

    public SettingsView settings() {
        AiSettings settings = currentSettings();
        return new SettingsView(settings.provider(), settings.model(), settings.dailyLimit(),
                chatService.providers().stream().map(AiProvider::id).toList(),
                mapper.countAutomaticSince(startOfToday()),
                notifyService.ruleNotify(NotifyService.RULE_AI, 0L));
    }

    public SettingsView saveSettings(String provider, String model, Integer dailyLimit, RuleNotify notification,
                                     String operatorUsername) {
        String cleanProvider = provider == null || provider.isBlank() ? null : provider.trim().toLowerCase(Locale.ROOT);
        if (cleanProvider != null && AiProvider.fromId(cleanProvider).isEmpty()) {
            throw new BadRequestException("Unknown AI provider " + cleanProvider);
        }
        int limit = dailyLimit == null ? 50 : dailyLimit;
        if (limit < 0 || limit > 10_000) {
            throw new BadRequestException("dailyLimit must be between 0 and 10000");
        }
        String cleanModel = model == null || model.isBlank() ? null : model.trim();
        notifyService.validate(notification);
        AiSettings settings = new AiSettings(cleanProvider, cleanModel, limit, Instant.now());
        if (mapper.updateSettings(settings) == 0) {
            try {
                mapper.insertSettings(settings);
            } catch (DuplicateKeyException e) {
                mapper.updateSettings(settings);
            }
        }
        if (notification != null) {
            notifyService.saveRuleNotify(NotifyService.RULE_AI, 0L, notification);
        }
        log.info("event=ai.settings.update operator={} provider={} dailyLimit={}", operatorUsername, cleanProvider,
                limit);
        return settings();
    }

    private AiSettings currentSettings() {
        AiSettings settings = mapper.selectSettings();
        return settings == null ? new AiSettings(null, null, 50, null) : settings;
    }

    private static Instant startOfToday() {
        return LocalDate.now(ZONE).atStartOfDay(ZONE).toInstant();
    }

    // ---------- stores ----------

    public record StoreView(Long storeId, String storeCode, String storeName, boolean storeEnabled, String repoUrl,
                            String repoBranch, String repoUsername, boolean tokenSaved, boolean autoNew,
                            boolean onAlert, String repoCommit, Instant repoSyncedAt, String repoError) {
    }

    public List<StoreView> stores() {
        Map<Long, AiStoreConfig> configs = new LinkedHashMap<>();
        mapper.selectStoreConfigs().forEach(config -> configs.put(config.storeId(), config));
        return storeRepository.findAll().stream()
                .sorted(Comparator.comparing(LogStore::id))
                .map(store -> view(store, configs.get(store.id())))
                .toList();
    }

    private static StoreView view(LogStore store, AiStoreConfig config) {
        if (config == null) {
            return new StoreView(store.id(), store.code(), store.name(), store.enabled(), null, null, null, false,
                    false, false, null, null, null);
        }
        return new StoreView(store.id(), store.code(), store.name(), store.enabled(), config.repoUrl(),
                config.repoBranch(), config.repoUsername(), config.repoSecret() != null, config.autoNew(),
                config.onAlert(), config.repoCommit(), config.repoSyncedAt(), config.repoError());
    }

    /**
     * @param repoUrl an {@code https://} or {@code file:} Git URL, or empty for no repository (the clone is removed)
     * @param token   empty keeps the saved one
     */
    public StoreView saveStore(Long storeId, String repoUrl, String repoBranch, String repoUsername, String token,
                               boolean autoNew, boolean onAlert, String operatorUsername) {
        LogStore store = storeRepository.findById(storeId)
                .orElseThrow(() -> new NotFoundException("Log store was not found"));
        String url = repoUrl == null || repoUrl.isBlank() ? null : repoUrl.trim();
        if (url != null && !url.matches("(?i)(https?://|file:)\\S+")) {
            throw new BadRequestException("The repository URL must start with https:// (or file: for a local one)");
        }
        String branch = repoBranch == null || repoBranch.isBlank() ? null : repoBranch.trim();
        if (branch != null && !BRANCH.matcher(branch).matches()) {
            throw new BadRequestException("The branch name is not valid");
        }
        AiStoreConfig existing = mapper.selectStoreConfig(storeId);
        String secret = token != null && !token.isEmpty() ? cipher.encrypt(token)
                : existing == null || url == null ? null : existing.repoSecret();
        boolean repoChanged = existing == null || !Objects.equals(existing.repoUrl(), url)
                || !Objects.equals(existing.repoBranch(), branch);
        // Automatic analysis starts with the errors from now on, not the store's history.
        Instant scannedUntil = autoNew && (existing == null || !existing.autoNew()) ? Instant.now()
                : existing == null ? null : existing.scannedUntil();
        AiStoreConfig config = new AiStoreConfig(storeId, url, branch,
                repoUsername == null || repoUsername.isBlank() ? null : repoUsername.trim(), secret, autoNew, onAlert,
                scannedUntil, repoChanged ? null : existing.repoCommit(),
                repoChanged ? null : existing.repoSyncedAt(), repoChanged ? null : existing.repoError(),
                Instant.now());
        if (mapper.updateStoreConfig(config) == 0) {
            try {
                mapper.insertStoreConfig(config);
            } catch (DuplicateKeyException e) {
                mapper.updateStoreConfig(config);
            }
        }
        if (repoChanged) {
            try {
                repositories.delete(storeId);
            } catch (RuntimeException e) {
                log.warn("Cannot remove the old clone of store {}: {}", storeId, e.getMessage());
            }
        }
        log.info("event=ai.store.update operator={} storeId={} repo={} autoNew={} onAlert={}", operatorUsername,
                storeId, url, autoNew, onAlert);
        return view(store, mapper.selectStoreConfig(storeId));
    }

    /**
     * Clones or updates the store's repository now.
     *
     * @throws BadRequestException with git's message when it fails
     */
    public SyncResult syncRepository(Long storeId) {
        AiStoreConfig config = mapper.selectStoreConfig(storeId);
        if (config == null || config.repoUrl() == null) {
            throw new BadRequestException("The store has no code repository");
        }
        try {
            return sync(config);
        } catch (RepoException e) {
            throw new BadRequestException(e.getMessage());
        }
    }

    private SyncResult sync(AiStoreConfig config) {
        try {
            String token = config.repoSecret() == null ? null : cipher.decrypt(config.repoSecret());
            SyncResult result = repositories.sync(config.storeId(), config.repoUrl(), config.repoBranch(),
                    config.repoUsername(), token);
            mapper.updateRepoState(config.storeId(), result.commit(), Instant.now(), null);
            return result;
        } catch (RepoException | IllegalStateException e) {
            mapper.updateRepoState(config.storeId(), config.repoCommit(), config.repoSyncedAt(),
                    e.getMessage().length() > 1000 ? e.getMessage().substring(0, 1000) : e.getMessage());
            throw e instanceof RepoException repo ? repo : new RepoException(e.getMessage());
        }
    }

    /**
     * Removes a deleted store's clone.
     */
    public void deleteStore(Long storeId) {
        try {
            repositories.delete(storeId);
        } catch (RuntimeException e) {
            log.warn("Cannot remove the clone of store {}: {}", storeId, e.getMessage());
        }
    }

    // ---------- error groups ----------

    public List<ErrorGroup> groups(Long storeId, String status, int limit) {
        String clean = status == null || status.isBlank() ? null : status.trim();
        if (clean != null && !REVIEW_STATES.contains(clean)) {
            throw new BadRequestException("status must be open, resolved or ignored");
        }
        return mapper.selectGroups(storeId, clean, Math.max(1, Math.min(limit, 1000)));
    }

    public ErrorGroup group(Long id) {
        ErrorGroup group = mapper.selectGroup(id);
        if (group == null) {
            throw new NotFoundException("Error group was not found");
        }
        return group;
    }

    public void setGroupStatus(Long id, String status) {
        group(id);
        if (!REVIEW_STATES.contains(status)) {
            throw new BadRequestException("status must be open, resolved or ignored");
        }
        mapper.updateGroupStatus(id, status);
    }

    public AiDiagnosis analyzeGroup(Long id, String username) {
        ErrorGroup group = group(id);
        return submit(group.storeId(), group.id(), MANUAL, null, group.sample(), group.host(), null,
                group.lastSeen(), username);
    }

    // ---------- diagnoses ----------

    public List<AiDiagnosis> diagnoses(Long storeId, String reviewState, String trigger, int days, int limit) {
        String state = reviewState == null || reviewState.isBlank() ? null : reviewState.trim();
        if (state != null && !REVIEW_STATES.contains(state)) {
            throw new BadRequestException("state must be open, resolved or ignored");
        }
        String type = trigger == null || trigger.isBlank() ? null : trigger.trim();
        if (type != null && !TRIGGERS.contains(type)) {
            throw new BadRequestException("trigger must be auto, alert or manual");
        }
        Instant since = Instant.now().minus(Duration.ofDays(Math.max(1, Math.min(days, 90))));
        return mapper.selectDiagnoses(storeId, state, type, since, Math.max(1, Math.min(limit, 500)));
    }

    public AiDiagnosis diagnosis(Long id) {
        AiDiagnosis diagnosis = mapper.selectDiagnosis(id);
        if (diagnosis == null) {
            throw new NotFoundException("AI diagnosis was not found");
        }
        return diagnosis;
    }

    /**
     * Marks a diagnosis, and its error group, as resolved, ignored or open again.
     */
    public void setReviewState(Long id, String state) {
        AiDiagnosis diagnosis = diagnosis(id);
        if (!REVIEW_STATES.contains(state)) {
            throw new BadRequestException("state must be open, resolved or ignored");
        }
        mapper.updateReviewState(id, state);
        if (diagnosis.groupId() != null && mapper.selectGroup(diagnosis.groupId()) != null) {
            mapper.updateGroupStatus(diagnosis.groupId(), state);
        }
    }

    public AiDiagnosis reanalyze(Long id, String username) {
        AiDiagnosis diagnosis = diagnosis(id);
        return submit(diagnosis.storeId(), diagnosis.groupId(), MANUAL, diagnosis.alertEventId(),
                diagnosis.logText(), diagnosis.host(), diagnosis.source(), diagnosis.logTime(), username);
    }

    /**
     * Analyses a log someone picked, e.g. in the log query.
     */
    public AiDiagnosis analyzeLog(Long storeId, String text, String host, String source, Instant time,
                                  String username) {
        storeRepository.findById(storeId).orElseThrow(() -> new NotFoundException("Log store was not found"));
        if (text == null || text.isBlank()) {
            throw new BadRequestException("The log to analyse is empty");
        }
        ParsedError parsed = StackTraces.parse(text);
        Long groupId = Optional.ofNullable(mapper.selectGroupByFingerprint(storeId, parsed.fingerprint()))
                .map(ErrorGroup::id).orElse(null);
        return submit(storeId, groupId, MANUAL, null, text, host, source, time, username);
    }

    /**
     * Queues an analysis.
     *
     * @return the queued analysis, or {@code null} when an automatic one is over the daily limit
     */
    AiDiagnosis submit(Long storeId, Long groupId, String trigger, Long alertEventId, String text, String host,
                       String source, Instant time, String username) {
        if (!MANUAL.equals(trigger) && mapper.countAutomaticSince(startOfToday()) >= currentSettings().dailyLimit()) {
            log.info("Skipping the {} AI diagnosis of store {}: the daily limit is reached", trigger, storeId);
            return null;
        }
        String logText = text == null ? "" : text.length() > LOG_TEXT_MAX ? text.substring(0, LOG_TEXT_MAX) : text;
        ParsedError parsed = StackTraces.parse(logText);
        Instant now = Instant.now();
        AiDiagnosis pending = new AiDiagnosis(null, storeId, groupId, trigger, alertEventId, AiDiagnosis.PENDING,
                "open", truncate(parsed.errorType(), 255), logText, truncate(host, 128), truncate(source, 255),
                time, null, null, null, null, null, null, null, null, null, null, null, null, username, now, null);
        GeneratedKey key = new GeneratedKey();
        mapper.insertDiagnosis(pending, key);
        Long id = key.getId();
        if (groupId != null) {
            mapper.updateGroupDiagnosis(groupId, id);
        }
        worker.submit(() -> run(id));
        log.info("event=ai.diagnosis.submit id={} storeId={} trigger={} by={}", id, storeId, trigger, username);
        return pending.withId(id);
    }

    void run(Long id) {
        AiDiagnosis diagnosis = mapper.selectDiagnosis(id);
        if (diagnosis == null) {
            return;
        }
        update(diagnosis, AiDiagnosis.RUNNING, null, null, null, null, null, null, null, null, null, null, null,
                null);
        try {
            AiSettings settings = currentSettings();
            List<AiProvider> available = chatService.providers();
            if (available.isEmpty()) {
                throw new IllegalStateException("No AI provider is configured: set one of LOGGINGX_AI_DEEPSEEK_API_KEY, "
                        + "LOGGINGX_AI_OPENAI_API_KEY or LOGGINGX_AI_ANTHROPIC_API_KEY");
            }
            AiProvider provider = settings.provider() == null ? available.get(0)
                    : AiProvider.fromId(settings.provider()).filter(available::contains).orElseThrow(() ->
                    new IllegalStateException("The AI provider " + settings.provider() + " is not configured"));

            String commit = null;
            String repoNote = "未配置代码仓库，只能根据日志分析。";
            List<CodeRef> refs = List.of();
            AiStoreConfig config = mapper.selectStoreConfig(diagnosis.storeId());
            ParsedError parsed = StackTraces.parse(diagnosis.logText());
            if (config != null && config.repoUrl() != null) {
                try {
                    boolean stale = config.repoSyncedAt() == null || !repositories.exists(config.storeId())
                            || config.repoSyncedAt().plus(RESYNC_AFTER).isBefore(Instant.now());
                    commit = stale ? sync(config).commit() : config.repoCommit();
                    List<Frame> frames = new ArrayList<>(parsed.appFrames());
                    parsed.frames().stream().filter(frame -> !frames.contains(frame)).forEach(frames::add);
                    refs = repositories.snippets(config.storeId(), frames, CODE_REFS, CODE_CONTEXT, CODE_CHARS);
                    repoNote = refs.isEmpty() ? "代码仓库里没有找到堆栈对应的文件，只能根据日志分析。" : null;
                } catch (RepoException e) {
                    repoNote = "代码仓库同步失败（" + e.getMessage() + "），只能根据日志分析。";
                }
            }
            String store = storeRepository.findById(diagnosis.storeId()).map(LogStore::name).orElse("-");
            String prompt = prompt(diagnosis, store, commit, refs, repoNote);
            AiChatReply reply = chatService.chat(new AiChatCommand(provider.id(), prompt, SYSTEM_PROMPT,
                    settings.model()));
            Answer answer = Answer.parse(reply.content());
            AiDiagnosis done = update(diagnosis, AiDiagnosis.SUCCESS, commit, JSON.writeValueAsString(refs),
                    reply.provider(), reply.model(), truncate(answer.summary(), 500), answer.rootCause(),
                    answer.suggestion(), answer.diff(), answer.confidence(), reply.promptTokens(),
                    reply.completionTokens(), null);
            log.info("event=ai.diagnosis.done id={} storeId={} provider={} codeRefs={}", id, diagnosis.storeId(),
                    reply.provider(), refs.size());
            notifyDone(done, store);
        } catch (RuntimeException e) {
            log.warn("AI diagnosis {} failed: {}", id, e.getMessage());
            update(diagnosis, AiDiagnosis.FAILED, null, null, null, null, null, null, null, null, null, null, null,
                    truncate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), 1000));
        }
    }

    private AiDiagnosis update(AiDiagnosis d, String status, String commit, String codeRefs, String provider,
                               String model, String summary, String rootCause, String suggestion, String diff,
                               String confidence, Integer promptTokens, Integer completionTokens, String error) {
        boolean finished = AiDiagnosis.SUCCESS.equals(status) || AiDiagnosis.FAILED.equals(status);
        AiDiagnosis updated = new AiDiagnosis(d.id(), d.storeId(), d.groupId(), d.triggerType(), d.alertEventId(),
                status, d.reviewState(), d.errorType(), d.logText(), d.host(), d.source(), d.logTime(), commit,
                codeRefs, provider, model, summary, rootCause, suggestion, diff, confidence, promptTokens,
                completionTokens, error, d.createdBy(), d.createdAt(), finished ? Instant.now() : null);
        mapper.updateDiagnosis(updated);
        return updated;
    }

    static String prompt(AiDiagnosis d, String store, String commit, List<CodeRef> refs, String repoNote) {
        StringBuilder text = new StringBuilder("## 错误日志\n");
        text.append("日志库：").append(store).append('\n');
        if (d.host() != null) {
            text.append("主机：").append(d.host()).append('\n');
        }
        if (d.source() != null) {
            text.append("来源：").append(d.source()).append('\n');
        }
        if (d.logTime() != null) {
            text.append("时间：").append(DateTimes.format(d.logTime())).append('\n');
        }
        text.append("```\n").append(d.logText()).append("\n```\n\n");
        if (refs.isEmpty()) {
            text.append("## 相关代码\n").append(repoNote == null ? "没有可用的代码。" : repoNote).append('\n');
            return text.toString();
        }
        text.append("## 相关代码").append(commit == null ? "" : "（提交 " + commit.substring(0, Math.min(10,
                commit.length())) + "）").append('\n');
        text.append("每行前是行号，> 标出堆栈指向的行。\n\n");
        for (CodeRef ref : refs) {
            text.append("### ").append(ref.path()).append("（").append(ref.frame()).append("）\n```\n");
            String[] lines = ref.text().split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                int number = ref.startLine() + i;
                text.append(number == ref.focusLine() ? ">" : " ")
                        .append(String.format("%5d | ", number)).append(lines[i]).append('\n');
            }
            text.append("```\n\n");
        }
        return text.toString();
    }

    /**
     * The model's answer; a reply that is not the asked-for JSON is kept as the suggestion.
     */
    record Answer(String summary, String rootCause, String suggestion, String diff, String confidence) {

        static Answer parse(String content) {
            String text = content == null ? "" : content.trim();
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start >= 0 && end > start) {
                try {
                    JsonNode node = JSON.readTree(text.substring(start, end + 1));
                    String confidence = node.path("confidence").asString("").toLowerCase(Locale.ROOT);
                    String diff = node.path("diff").asString("").strip();
                    return new Answer(node.path("summary").asString("").strip(),
                            node.path("rootCause").asString("").strip(),
                            node.path("suggestion").asString("").strip(),
                            diff.isEmpty() ? null : stripFence(diff),
                            Set.of("high", "medium", "low").contains(confidence) ? confidence : null);
                } catch (JacksonException e) {
                    // Not JSON after all: keep the text.
                }
            }
            String firstLine = text.lines().filter(line -> !line.isBlank()).findFirst().orElse("").strip();
            return new Answer(firstLine.length() > 120 ? firstLine.substring(0, 120) : firstLine, null, text, null,
                    null);
        }

        private static String stripFence(String diff) {
            return diff.replaceAll("^```\\w*\\n", "").replaceAll("\\n```$", "");
        }
    }

    private void notifyDone(AiDiagnosis d, String store) {
        try {
            StringBuilder extra = new StringBuilder();
            if (d.suggestion() != null) {
                extra.append(d.suggestion());
            }
            if (d.diff() != null) {
                extra.append(extra.isEmpty() ? "" : "\n\n").append(d.diff());
            }
            String summary = d.summary() == null || d.summary().isBlank() ? "AI 已给出修改建议" : d.summary();
            notifyService.notify(new AlertNotice(NotifyService.RULE_AI, d.id(), NotifyService.RULE_AI, 0L,
                    d.errorType() == null ? "错误日志" : d.errorType(), "info", NotifyService.DIAGNOSIS,
                    "日志库 " + store, summary, summary, d.logTime() == null ? d.createdAt() : d.logTime(), null,
                    extra.isEmpty() ? null : extra.toString()));
        } catch (RuntimeException e) {
            log.warn("Cannot notify AI diagnosis {}: {}", d.id(), e.getMessage());
        }
    }

    // ---------- triggers ----------

    @Scheduled(initialDelayString = "${loggingx.monitor.alert-initial-delay:30000}",
            fixedDelayString = "${loggingx.monitor.alert-interval:60000}")
    public void scanScheduled() {
        try {
            scan(Instant.now());
        } catch (RuntimeException e) {
            log.warn("Cannot group new errors for AI diagnosis: {}", e.getMessage());
        }
    }

    private record Occurrences(ParsedError parsed, LogRecord newest, long count) {
    }

    /**
     * Groups the ERROR and FATAL logs that arrived since the last scan of each store with automatic analysis, and
     * analyses kinds of error seen for the first time.
     */
    public void scan(Instant now) {
        Instant until = now.minus(SCAN_DELAY);
        for (AiStoreConfig config : mapper.selectStoreConfigs()) {
            if (!config.autoNew()) {
                continue;
            }
            Optional<LogStore> store = storeRepository.findById(config.storeId());
            if (store.isEmpty() || !store.get().enabled()) {
                continue;
            }
            Instant from = config.scannedUntil() == null ? until.minus(FIRST_SCAN) : config.scannedUntil();
            if (!from.isBefore(until)) {
                continue;
            }
            try {
                scanStore(config.storeId(), from, until);
                mapper.updateScannedUntil(config.storeId(), until);
            } catch (RuntimeException e) {
                log.warn("Cannot group the errors of store {}: {}", config.storeId(), e.getMessage());
            }
        }
    }

    private void scanStore(Long storeId, Instant from, Instant until) {
        LogSearch search = new LogSearch(storeId, from, until, List.of("ERROR", "FATAL"), null, null, null);
        Map<String, Occurrences> byFingerprint = new LinkedHashMap<>();
        for (int offset = 0; offset < SCAN_MAX; offset += SCAN_PAGE) {
            List<LogRecord> page = recordRepository.search(search, offset, SCAN_PAGE);
            for (LogRecord record : page) {
                ParsedError parsed = StackTraces.parse(record.message());
                byFingerprint.merge(parsed.fingerprint(), new Occurrences(parsed, record, 1),
                        (a, b) -> new Occurrences(a.parsed(),
                                a.newest().time().isAfter(b.newest().time()) ? a.newest() : b.newest(),
                                a.count() + b.count()));
            }
            if (page.size() < SCAN_PAGE) {
                break;
            }
        }
        for (Map.Entry<String, Occurrences> entry : byFingerprint.entrySet()) {
            Occurrences found = entry.getValue();
            LogRecord newest = found.newest();
            String sample = truncate(newest.message(), LOG_TEXT_MAX);
            ErrorGroup existing = mapper.selectGroupByFingerprint(storeId, entry.getKey());
            if (existing != null) {
                mapper.touchGroup(existing.id(), found.count(), newest.time(), sample, truncate(newest.host(), 128));
                continue;
            }
            StackTraces.Frame location = found.parsed().location();
            ErrorGroup group = new ErrorGroup(null, storeId, entry.getKey(), truncate(found.parsed().errorType(), 255),
                    location == null ? truncate(found.parsed().headline(), 512) : truncate(location.label(), 512),
                    sample, truncate(newest.host(), 128), found.count(), newest.time(), newest.time(), "open", null);
            GeneratedKey key = new GeneratedKey();
            try {
                mapper.insertGroup(group, key);
            } catch (DuplicateKeyException e) {
                continue;
            }
            submit(storeId, key.getId(), AUTO, null, newest.message(), newest.host(), newest.source(), newest.time(),
                    null);
        }
    }

    /**
     * Analyses the newest log matching a log alert that started firing, when its store asks for it.
     */
    @EventListener
    public void onLogAlert(LogAlertFired fired) {
        try {
            AiStoreConfig config = mapper.selectStoreConfig(fired.storeId());
            if (config == null || !config.onAlert()) {
                return;
            }
            List<LogRecord> newest = recordRepository.search(fired.search(), 0, 1);
            if (newest.isEmpty()) {
                return;
            }
            LogRecord record = newest.get(0);
            ParsedError parsed = StackTraces.parse(record.message());
            Long groupId = Optional.ofNullable(mapper.selectGroupByFingerprint(fired.storeId(),
                    parsed.fingerprint())).map(ErrorGroup::id).orElse(null);
            submit(fired.storeId(), groupId, ALERT, fired.eventId(), record.message(), record.host(), record.source(),
                    record.time(), null);
        } catch (RuntimeException e) {
            log.warn("Cannot start the AI diagnosis of log alert {}: {}", fired.eventId(), e.getMessage());
        }
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
