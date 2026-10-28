package com.loggingx.biz.ai;

import com.loggingx.ai.AiChatCommand;
import com.loggingx.ai.AiChatService;
import com.loggingx.ai.AiChatStreamEvent;
import com.loggingx.ai.AiProvider;
import com.loggingx.biz.ai.CodeRepositories.CodeRef;
import com.loggingx.biz.monitor.AlertService;
import com.loggingx.biz.monitor.LogAlertService;
import com.loggingx.biz.permission.StoreAccessService.Scope;
import com.loggingx.common.exception.BadRequestException;
import com.loggingx.common.exception.ForbiddenException;
import com.loggingx.common.exception.NotFoundException;
import com.loggingx.common.util.DateTimes;
import com.loggingx.dao.ai.AiDiagnosis;
import com.loggingx.dao.ai.AiKnowledge;
import com.loggingx.dao.ai.AiMapper;
import com.loggingx.dao.ai.AiSettings;
import com.loggingx.dao.ai.AiStoreConfig;
import com.loggingx.dao.ai.ErrorGroup;
import com.loggingx.dao.log.LogRecord;
import com.loggingx.dao.log.LogRecordRepository;
import com.loggingx.dao.log.LogSearch;
import com.loggingx.dao.log.LogStore;
import com.loggingx.dao.log.LogStoreRepository;
import com.loggingx.dao.monitor.AlertEvent;
import com.loggingx.dao.monitor.LogAlertEvent;
import com.loggingx.dao.support.GeneratedKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The operations assistant: answers questions such as "why does the order service keep failing?" from what LoggingX
 * knows (retrieval-augmented generation). For each question it gathers material the user may see - the knowledge
 * base, past AI diagnoses, error groups, recent alerts, the error logs of the chosen time range and code files the
 * question names - ranks the written material by relevance (BM25 over words, identifiers and Chinese bigrams), and
 * asks the model to answer from that material only, citing it as {@code [n]}.
 */
@Service
public class OpsAssistantService {

    private static final Logger log = LoggerFactory.getLogger(OpsAssistantService.class);
    private static final int MAX_DOCS = 6;
    private static final int MAX_LOGS = 8;
    private static final int MAX_CODE = 2;
    private static final int MAX_STORES = 20;
    private static final int MATERIAL_CHARS = 24_000;
    private static final int CHUNK_CHARS = 900;
    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_$.]*[A-Za-z0-9_]|[A-Za-z]{2}");
    private static final Pattern NUMBER = Pattern.compile("\\b\\d{3,}\\b");
    private static final Pattern CJK = Pattern.compile("[\\u4e00-\\u9fff]+");
    private static final Pattern IDENTIFIER = Pattern.compile(
            "\\b(?:[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]*)+|[a-z][\\w-]*\\.(?:java|kt|py|js|ts|go|vue|cs|php|rb))\\b");
    private static final Set<String> STOP_WORDS = Set.of("the", "and", "for", "with", "why", "what", "how", "is",
            "are", "was", "to", "of", "in", "on", "at", "it", "this", "that", "my", "an", "or", "be", "do", "does");
    /** Words too common in logs to find anything by. */
    private static final Set<String> GENERIC = Set.of("error", "errors", "exception", "failed", "fail", "warn",
            "fatal", "info", "log", "logs", "java", "lang", "null", "timeout", "service");
    private static final Set<String> STOP_BIGRAMS = Set.of("为什", "什么", "怎么", "如何", "一下", "这个", "那个",
            "是否", "哪些", "有没", "没有", "最近", "现在", "我们", "可以", "出现", "问题", "原因", "一直", "为何",
            "请问", "帮我", "看看", "一个", "的是", "了吗");

    static final String SYSTEM_PROMPT = """
            你是 LoggingX 的运维助手，帮助工程师排查线上问题、查找错误原因。
            只根据「参考资料」回答：资料里有的直接引用，并在句末用 [编号] 标出来源，例如 [2]；资料不足以回答时，明确说出缺少什么，
            并建议接下来去查哪些日志、主机或配置，不要编造资料里没有的事实。
            回答用中文，使用 Markdown：先给结论，再列依据和建议的处理步骤；涉及代码时指出文件和方法。""";

    private final AiMapper mapper;
    private final AiChatService chatService;
    private final CodeRepositories repositories;
    private final LogRecordRepository recordRepository;
    private final LogStoreRepository storeRepository;
    private final AlertService alertService;
    private final LogAlertService logAlertService;

    public OpsAssistantService(AiMapper mapper, AiChatService chatService, CodeRepositories repositories,
                               LogRecordRepository recordRepository, LogStoreRepository storeRepository,
                               AlertService alertService, LogAlertService logAlertService) {
        this.mapper = mapper;
        this.chatService = chatService;
        this.repositories = repositories;
        this.recordRepository = recordRepository;
        this.storeRepository = storeRepository;
        this.alertService = alertService;
        this.logAlertService = logAlertService;
    }

    // ---------- knowledge base ----------

    public List<AiKnowledge> knowledge(Scope scope) {
        return mapper.selectKnowledge().stream()
                .filter(item -> item.storeId() == null || scope.includes(item.storeId()))
                .toList();
    }

    public AiKnowledge knowledge(Long id, Scope scope) {
        AiKnowledge item = mapper.selectKnowledgeById(id);
        if (item == null || item.storeId() != null && !scope.includes(item.storeId())) {
            throw new NotFoundException("Knowledge document was not found");
        }
        return item;
    }

    /**
     * @param id {@code null} to add a document
     */
    public AiKnowledge saveKnowledge(Long id, String title, String content, String tags, Long storeId, Scope scope,
                                     String operatorUsername) {
        String cleanTitle = title == null ? "" : title.trim();
        if (cleanTitle.isEmpty() || cleanTitle.length() > 200) {
            throw new BadRequestException("title must be 1-200 characters");
        }
        if (content == null || content.isBlank()) {
            throw new BadRequestException("content is required");
        }
        if (content.length() > 2_000_000) {
            throw new BadRequestException("content must be at most 2,000,000 characters");
        }
        if (storeId != null) {
            storeRepository.findById(storeId).orElseThrow(() -> new NotFoundException("Log store was not found"));
            if (!scope.includes(storeId)) {
                throw new ForbiddenException("You have no access to this log store");
            }
        } else if (!scope.allStores()) {
            throw new ForbiddenException("Documents for every store need access to every log store");
        }
        String cleanTags = tags == null || tags.isBlank() ? null
                : String.join(",", new LinkedHashSet<>(List.of(tags.trim().split("\\s*[,，]\\s*"))));
        Instant now = Instant.now();
        if (id == null) {
            AiKnowledge item = new AiKnowledge(null, cleanTitle, content, cleanTags, storeId, operatorUsername, now,
                    now);
            GeneratedKey key = new GeneratedKey();
            mapper.insertKnowledge(item, key);
            id = key.getId();
        } else {
            AiKnowledge existing = knowledge(id, scope);
            mapper.updateKnowledge(new AiKnowledge(id, cleanTitle, content, cleanTags, storeId, existing.createdBy(),
                    existing.createdAt(), now));
        }
        log.info("event=ai.knowledge.save operator={} id={} storeId={}", operatorUsername, id, storeId);
        return mapper.selectKnowledgeById(id);
    }

    public void deleteKnowledge(Long id, Scope scope, String operatorUsername) {
        knowledge(id, scope);
        mapper.deleteKnowledge(id);
        log.info("event=ai.knowledge.delete operator={} id={}", operatorUsername, id);
    }

    // ---------- retrieval ----------

    /**
     * A piece of material the answer may cite.
     *
     * @param ref     the number the answer cites it by
     * @param type    {@code knowledge}, {@code diagnosis}, {@code group}, {@code alert}, {@code log}, {@code stats}
     *                or {@code code}
     * @param id      the knowledge document, diagnosis, error group, alert event or log id (as text: log ids exceed
     *                JavaScript's safe integers); {@code null} otherwise
     * @param excerpt a short excerpt to show
     */
    public record Source(int ref, String type, String id, Long storeId, String storeName, String title, String excerpt,
                         String time) {
    }

    /**
     * @param materials the numbered material as given to the model
     */
    public record Retrieval(List<Source> sources, String materials) {
    }

    private record Doc(String type, Long id, Long storeId, String title, String text, Instant time) {
    }

    /**
     * Gathers the material for a question.
     *
     * @param storeId the store asked about, or {@code null} for every store the user may access
     * @param hours   how far back logs and alerts are searched
     */
    public Retrieval retrieve(String question, Long storeId, int hours, Scope scope) {
        if (storeId != null && !scope.includes(storeId)) {
            throw new ForbiddenException("You have no access to this log store");
        }
        Map<Long, String> storeNames = new HashMap<>();
        List<LogStore> stores = new ArrayList<>();
        for (LogStore store : storeRepository.findAll()) {
            storeNames.put(store.id(), store.name());
            if (scope.includes(store.id()) && (storeId == null || store.id().equals(storeId))) {
                stores.add(store);
            }
        }
        Set<Long> storeIds = new LinkedHashSet<>(stores.stream().map(LogStore::id).toList());
        Instant now = Instant.now();
        Instant since = now.minus(Duration.ofHours(Math.max(1, Math.min(hours, 24 * 30))));
        List<String> terms = terms(question);

        // Written material, ranked by relevance.
        List<Doc> docs = new ArrayList<>();
        for (AiKnowledge item : mapper.selectKnowledge()) {
            if (item.storeId() == null || storeIds.contains(item.storeId())) {
                for (String chunk : chunks(item.content())) {
                    docs.add(new Doc("knowledge", item.id(), item.storeId(), item.title(),
                            item.title() + "\n" + (item.tags() == null ? "" : item.tags() + "\n") + chunk,
                            item.updatedAt()));
                }
            }
        }
        for (AiDiagnosis d : mapper.selectDiagnoses(null, null, null, now.minus(Duration.ofDays(180)), 500)) {
            if (AiDiagnosis.SUCCESS.equals(d.status()) && storeIds.contains(d.storeId())) {
                docs.add(new Doc("diagnosis", d.id(), d.storeId(), "AI 诊断：" + nonNull(d.errorType(), "错误日志"),
                        "错误：" + nonNull(d.errorType(), "-") + "\n结论：" + nonNull(d.summary(), "")
                                + "\n原因：" + nonNull(d.rootCause(), "") + "\n建议：" + nonNull(d.suggestion(), "")
                                + "\n处理状态：" + reviewText(d.reviewState()) + "\n日志：" + head(d.logText(), 600),
                        d.finishedAt() == null ? d.createdAt() : d.finishedAt()));
            }
        }
        for (ErrorGroup group : mapper.selectGroups(null, null, 500)) {
            if (storeIds.contains(group.storeId())) {
                docs.add(new Doc("group", group.id(), group.storeId(), "错误归类：" + nonNull(group.errorType(), "错误"),
                        "错误：" + nonNull(group.errorType(), "-") + "\n位置：" + nonNull(group.location(), "-")
                                + "\n次数：" + group.occurrences() + "，首次 " + DateTimes.format(group.firstSeen())
                                + "，最近 " + DateTimes.format(group.lastSeen()) + "\n处理状态："
                                + reviewText(group.status()) + "\n示例：" + head(group.sample(), 600),
                        group.lastSeen()));
            }
        }
        int days = (int) Math.max(1, Math.min(30, Duration.between(since, now).toDays() + 1));
        for (AlertEvent event : alertService.events(null, null, null, days, 300)) {
            if (storeIds.contains(event.storeId()) && !event.startedAt().isBefore(since)) {
                docs.add(new Doc("alert", event.id(), event.storeId(), "监控告警：" + event.ruleName(),
                        "监控告警「" + event.ruleName() + "」 主机 " + event.hostname() + "，指标 " + event.metric()
                                + "，当前 " + event.value() + "，峰值 " + event.peakValue() + "，阈值 "
                                + event.threshold() + "，" + ("firing".equals(event.status()) ? "告警中" : "已恢复")
                                + "，开始 " + DateTimes.format(event.startedAt())
                                + (event.resolvedAt() == null ? "" : "，恢复 " + DateTimes.format(event.resolvedAt())),
                        event.startedAt()));
            }
        }
        for (LogAlertEvent event : logAlertService.events(null, null, days, 300)) {
            if (storeIds.contains(event.storeId()) && !event.startedAt().isBefore(since)) {
                docs.add(new Doc("alert", event.id(), event.storeId(), "日志告警：" + event.ruleName(),
                        "日志告警「" + event.ruleName() + "」 " + event.windowMinutes() + " 分钟内 " + event.value()
                                + " 条（阈值 " + event.threshold() + "，峰值 " + event.peakValue() + "），"
                                + ("firing".equals(event.status()) ? "告警中" : "已恢复") + "，开始 "
                                + DateTimes.format(event.startedAt()) + "\n示例：" + head(event.sample(), 400),
                        event.startedAt()));
            }
        }
        List<Doc> ranked = rank(docs, terms, MAX_DOCS);

        // Recent error logs: those mentioning the question's words, else the newest.
        Map<Long, LogRecord> logs = new LinkedHashMap<>();
        List<String> keywords = terms.stream().filter(term -> term.matches("[a-z0-9_.$-]{3,}"))
                .filter(term -> !GENERIC.contains(term)).limit(3).toList();
        List<LogStore> searched = stores.size() > MAX_STORES ? stores.subList(0, MAX_STORES) : stores;
        StringBuilder stats = new StringBuilder();
        for (LogStore store : searched) {
            LogSearch errors = new LogSearch(store.id(), since, now, List.of("ERROR", "FATAL"), null, null, null);
            try {
                long count = recordRepository.count(errors);
                if (count > 0) {
                    stats.append(store.name()).append("：").append(count).append(" 条 ERROR/FATAL\n");
                }
                for (String keyword : keywords) {
                    for (LogRecord record : recordRepository.search(new LogSearch(store.id(), since, now,
                            List.of("ERROR", "FATAL", "WARN"), keyword, null, null), 0, 4)) {
                        logs.putIfAbsent(record.id(), record);
                    }
                }
                if (keywords.isEmpty() || logs.isEmpty()) {
                    for (LogRecord record : recordRepository.search(errors, 0, storeId == null ? 2 : 5)) {
                        logs.putIfAbsent(record.id(), record);
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Cannot search the logs of store {} for the assistant: {}", store.id(), e.getMessage());
            }
        }
        List<LogRecord> logList = logs.values().stream()
                .sorted(Comparator.comparing(LogRecord::time).reversed()).limit(MAX_LOGS).toList();

        // Code the question names, from the stores' repositories.
        List<CodeRef> code = new ArrayList<>();
        Map<CodeRef, Long> codeStores = new HashMap<>();
        Matcher identifiers = IDENTIFIER.matcher(question);
        Set<String> named = new LinkedHashSet<>();
        while (identifiers.find()) {
            named.add(identifiers.group());
        }
        if (!named.isEmpty()) {
            for (AiStoreConfig config : mapper.selectStoreConfigs()) {
                if (config.repoUrl() == null || !storeIds.contains(config.storeId())) {
                    continue;
                }
                for (String identifier : named) {
                    for (CodeRef ref : repositories.mentions(config.storeId(), identifier, 1, 60)) {
                        if (code.size() < MAX_CODE) {
                            code.add(ref);
                            codeStores.put(ref, config.storeId());
                        }
                    }
                }
            }
        }

        // Number the material, most relevant first, within the size limit.
        List<Source> sources = new ArrayList<>();
        StringBuilder materials = new StringBuilder();
        String range = DateTimes.format(since) + " 至 " + DateTimes.format(now);
        if (!stats.isEmpty()) {
            int ref = sources.size() + 1;
            String text = "时间范围 " + range + " 内各日志库的错误数量：\n" + stats;
            sources.add(new Source(ref, "stats", null, storeId, storeId == null ? null : storeNames.get(storeId),
                    "错误数量统计", head(stats.toString().replace('\n', '；'), 200), DateTimes.format(now)));
            append(materials, ref, "统计", text);
        }
        for (Doc doc : ranked) {
            int ref = sources.size() + 1;
            String body = head(doc.text(), 1500);
            if (materials.length() + body.length() > MATERIAL_CHARS) {
                break;
            }
            sources.add(new Source(ref, doc.type(), String.valueOf(doc.id()), doc.storeId(), storeNames.get(doc.storeId()),
                    doc.title(), head(doc.text().replace('\n', ' '), 160), DateTimes.format(doc.time())));
            append(materials, ref, typeText(doc.type()) + "《" + doc.title() + "》"
                    + (doc.storeId() == null ? "" : "（日志库 " + storeNames.get(doc.storeId()) + "）"), body);
        }
        for (LogRecord record : logList) {
            int ref = sources.size() + 1;
            String body = DateTimes.format(record.time()) + " " + record.level() + " "
                    + nonNull(record.host(), "-") + " " + nonNull(record.source(), "") + "\n"
                    + head(record.message(), 800);
            if (materials.length() + body.length() > MATERIAL_CHARS) {
                break;
            }
            sources.add(new Source(ref, "log", String.valueOf(record.id()), record.storeId(), storeNames.get(record.storeId()),
                    record.level() + " 日志 · " + nonNull(record.host(), "-"),
                    head(record.message().replace('\n', ' '), 160), DateTimes.format(record.time())));
            append(materials, ref, "日志（日志库 " + storeNames.get(record.storeId()) + "）", body);
        }
        for (CodeRef ref : code) {
            int number = sources.size() + 1;
            if (materials.length() + ref.text().length() > MATERIAL_CHARS + 6000) {
                break;
            }
            Long codeStore = codeStores.get(ref);
            sources.add(new Source(number, "code", null, codeStore, storeNames.get(codeStore), ref.path(),
                    head(ref.text().replace('\n', ' '), 160), null));
            StringBuilder numbered = new StringBuilder();
            String[] lines = ref.text().split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                numbered.append(String.format("%5d | ", ref.startLine() + i)).append(lines[i]).append('\n');
            }
            append(materials, number, "代码 " + ref.path(), numbered.toString());
        }
        return new Retrieval(sources, materials.toString());
    }

    // ---------- answering ----------

    /**
     * A previous turn of the conversation, for follow-up questions.
     *
     * @param role {@code user} or {@code assistant}
     */
    public record Turn(String role, String content) {
    }

    /**
     * The answer as it is generated: the sources first, then the model's events.
     */
    public sealed interface AskEvent {

        record Sources(List<Source> sources) implements AskEvent {
        }

        record Model(AiChatStreamEvent event) implements AskEvent {
        }
    }

    /**
     * @throws BadRequestException when the question is empty or no AI provider is configured
     */
    public Flux<AskEvent> ask(String question, Long storeId, int hours, List<Turn> history, Scope scope) {
        String clean = question == null ? "" : question.trim();
        if (clean.isEmpty()) {
            throw new BadRequestException("Ask a question");
        }
        if (clean.length() > 2000) {
            throw new BadRequestException("The question must be at most 2000 characters");
        }
        List<AiProvider> available = chatService.providers();
        if (available.isEmpty()) {
            throw new BadRequestException("No AI provider is configured: set one of LOGGINGX_AI_DEEPSEEK_API_KEY, "
                    + "LOGGINGX_AI_OPENAI_API_KEY or LOGGINGX_AI_ANTHROPIC_API_KEY");
        }
        AiSettings settings = mapper.selectSettings();
        AiProvider provider = settings == null || settings.provider() == null ? available.get(0)
                : AiProvider.fromId(settings.provider()).filter(available::contains).orElse(available.get(0));
        Retrieval retrieval = retrieve(clean, storeId, hours, scope);
        String prompt = prompt(clean, retrieval, history);
        return Flux.concat(Flux.just(new AskEvent.Sources(retrieval.sources())),
                chatService.stream(new AiChatCommand(provider.id(), prompt, SYSTEM_PROMPT,
                        settings == null ? null : settings.model())).map(AskEvent.Model::new));
    }

    static String prompt(String question, Retrieval retrieval, List<Turn> history) {
        StringBuilder text = new StringBuilder();
        if (history != null && !history.isEmpty()) {
            text.append("## 之前的对话\n");
            List<Turn> recent = history.size() > 6 ? history.subList(history.size() - 6, history.size()) : history;
            for (Turn turn : recent) {
                text.append("assistant".equals(turn.role()) ? "助手：" : "用户：")
                        .append(head(turn.content(), 1500)).append('\n');
            }
            text.append('\n');
        }
        text.append("## 参考资料\n");
        text.append(retrieval.materials().isBlank() ? "（没有找到相关资料）\n" : retrieval.materials());
        text.append("\n## 问题\n").append(question).append('\n');
        return text.toString();
    }

    // ---------- ranking ----------

    /**
     * @return the question's search terms: words and identifiers (also split at dots and camel case), numbers of 3+
     * digits, and Chinese bigrams; without stop words
     */
    static List<String> terms(String text) {
        Set<String> terms = new LinkedHashSet<>();
        String value = text == null ? "" : text;
        Matcher word = WORD.matcher(value);
        while (word.find()) {
            String token = word.group();
            String lower = token.toLowerCase(Locale.ROOT);
            if (lower.length() >= 2 && !STOP_WORDS.contains(lower)) {
                terms.add(lower);
            }
            for (String part : token.split("[.$_]|(?<=[a-z0-9])(?=[A-Z])")) {
                String p = part.toLowerCase(Locale.ROOT);
                if (p.length() >= 3 && !STOP_WORDS.contains(p)) {
                    terms.add(p);
                }
            }
        }
        Matcher number = NUMBER.matcher(value);
        while (number.find()) {
            terms.add(number.group());
        }
        Matcher cjk = CJK.matcher(value);
        while (cjk.find()) {
            String run = cjk.group();
            for (int i = 0; i + 2 <= run.length(); i++) {
                String bigram = run.substring(i, i + 2);
                if (!STOP_BIGRAMS.contains(bigram)) {
                    terms.add(bigram);
                }
            }
        }
        return List.copyOf(terms);
    }

    /**
     * Okapi BM25 (k1 1.2, b 0.75) of each document for the terms.
     */
    private static List<Doc> rank(List<Doc> docs, List<String> terms, int limit) {
        if (docs.isEmpty() || terms.isEmpty()) {
            return List.of();
        }
        List<Map<String, Integer>> frequencies = new ArrayList<>();
        Map<String, Integer> documentFrequency = new HashMap<>();
        double totalLength = 0;
        for (Doc doc : docs) {
            Map<String, Integer> counts = new HashMap<>();
            List<String> docTerms = termsWithRepeats(doc.title() + "\n" + doc.text());
            for (String term : docTerms) {
                counts.merge(term, 1, Integer::sum);
            }
            counts.keySet().forEach(term -> documentFrequency.merge(term, 1, Integer::sum));
            frequencies.add(counts);
            totalLength += docTerms.size();
        }
        double averageLength = Math.max(1, totalLength / docs.size());
        record Scored(Doc doc, double score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            Map<String, Integer> counts = frequencies.get(i);
            int length = counts.values().stream().mapToInt(Integer::intValue).sum();
            double score = 0;
            for (String term : terms) {
                Integer tf = counts.get(term);
                if (tf == null) {
                    continue;
                }
                int df = documentFrequency.getOrDefault(term, 0);
                double idf = Math.log(1 + (docs.size() - df + 0.5) / (df + 0.5));
                score += idf * tf * 2.2 / (tf + 1.2 * (0.25 + 0.75 * length / averageLength));
            }
            if (score > 0) {
                scored.add(new Scored(docs.get(i), score));
            }
        }
        // Keep one chunk per document so the material covers more sources.
        Set<String> seen = new LinkedHashSet<>();
        return scored.stream()
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .filter(item -> seen.add(item.doc().type() + ":" + item.doc().id()))
                .limit(limit)
                .map(Scored::doc)
                .toList();
    }

    private static List<String> termsWithRepeats(String text) {
        List<String> terms = new ArrayList<>();
        Matcher word = WORD.matcher(text);
        while (word.find()) {
            String token = word.group();
            terms.add(token.toLowerCase(Locale.ROOT));
            for (String part : token.split("[.$_]|(?<=[a-z0-9])(?=[A-Z])")) {
                if (part.length() >= 3 && !part.equalsIgnoreCase(token)) {
                    terms.add(part.toLowerCase(Locale.ROOT));
                }
            }
        }
        Matcher number = NUMBER.matcher(text);
        while (number.find()) {
            terms.add(number.group());
        }
        Matcher cjk = CJK.matcher(text);
        while (cjk.find()) {
            String run = cjk.group();
            for (int i = 0; i + 2 <= run.length(); i++) {
                terms.add(run.substring(i, i + 2));
            }
        }
        return terms;
    }

    /**
     * Splits a document at blank lines and headings into pieces of about {@value #CHUNK_CHARS} characters.
     */
    static List<String> chunks(String content) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String paragraph : content.split("\\n\\s*\\n|\\n(?=#{1,6} )")) {
            if (current.length() > 0 && current.length() + paragraph.length() > CHUNK_CHARS) {
                chunks.add(current.toString().trim());
                current.setLength(0);
            }
            if (paragraph.length() > CHUNK_CHARS * 2) {
                for (int i = 0; i < paragraph.length(); i += CHUNK_CHARS) {
                    chunks.add(paragraph.substring(i, Math.min(paragraph.length(), i + CHUNK_CHARS)));
                }
                continue;
            }
            current.append(paragraph).append("\n\n");
        }
        if (!current.toString().isBlank()) {
            chunks.add(current.toString().trim());
        }
        return chunks;
    }

    private static void append(StringBuilder materials, int ref, String label, String body) {
        materials.append('[').append(ref).append("] ").append(label).append('\n').append(body.strip())
                .append("\n\n");
    }

    private static String typeText(String type) {
        return switch (type) {
            case "knowledge" -> "知识库";
            case "diagnosis" -> "AI 诊断";
            case "group" -> "错误归类";
            case "alert" -> "告警";
            default -> type;
        };
    }

    private static String reviewText(String state) {
        return switch (state == null ? "" : state) {
            case "resolved" -> "已处理";
            case "ignored" -> "已忽略";
            default -> "待处理";
        };
    }

    private static String head(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static String nonNull(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
