package com.loggingx.web.ai;

import com.loggingx.biz.ai.AiDiagnosisService;
import com.loggingx.biz.ai.AiDiagnosisService.SettingsView;
import com.loggingx.biz.ai.AiDiagnosisService.StoreView;
import com.loggingx.biz.ai.CodeRepositories.SyncResult;
import com.loggingx.biz.notify.NotifyService.RuleNotify;
import com.loggingx.biz.permission.StoreAccessService;
import com.loggingx.common.api.ApiResponse;
import com.loggingx.common.util.DateTimes;
import com.loggingx.dao.ai.AiDiagnosis;
import com.loggingx.dao.ai.ErrorGroup;
import com.loggingx.dao.log.LogStore;
import com.loggingx.dao.log.LogStoreRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI diagnosis: errors analysed with the stores' code, the analyses and their settings. Each AI page's menu opens its
 * part (diagnoses, error groups, settings); only the stores the user may access are shown.
 */
@RestController
@RequestMapping("/ai-diagnosis")
@PreAuthorize(AiDiagnosisController.ANY_AI_PAGE)
public class AiDiagnosisController {

    static final String ANY_AI_PAGE = "@permissionService.hasAnyMenu(authentication, 'AiChat', 'AiDiagnosis', "
            + "'AiErrorGroup', 'AiKnowledge', 'AiSettings')";
    private static final String DIAGNOSES = "@permissionService.hasAnyMenu(authentication, 'AiDiagnosis')";
    /** One diagnosis is also opened from the error groups and from answers of the assistant. */
    private static final String DIAGNOSIS_READERS =
            "@permissionService.hasAnyMenu(authentication, 'AiDiagnosis', 'AiErrorGroup', 'AiChat')";
    private static final String GROUPS = "@permissionService.hasAnyMenu(authentication, 'AiErrorGroup')";
    private static final String SETTINGS = "@permissionService.hasAnyMenu(authentication, 'AiSettings')";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AiDiagnosisService service;
    private final StoreAccessService storeAccessService;
    private final LogStoreRepository logStoreRepository;

    public AiDiagnosisController(AiDiagnosisService service, StoreAccessService storeAccessService,
                                 LogStoreRepository logStoreRepository) {
        this.service = service;
        this.storeAccessService = storeAccessService;
        this.logStoreRepository = logStoreRepository;
    }

    // ---------- settings ----------

    public record SettingsRequest(String provider, String model, Integer dailyLimit, RuleNotify notification) {
    }

    @GetMapping("/settings")
    @PreAuthorize(SETTINGS)
    public ApiResponse<SettingsView> settings() {
        return ApiResponse.success(service.settings());
    }

    @PutMapping("/settings")
    @PreAuthorize(SETTINGS)
    public ApiResponse<SettingsView> saveSettings(@RequestBody SettingsRequest request,
                                                 Authentication authentication) {
        return ApiResponse.success(service.saveSettings(request.provider(), request.model(), request.dailyLimit(),
                request.notification(), authentication.getName()));
    }

    // ---------- stores ----------

    public record StoreResponse(Long storeId, String storeCode, String storeName, boolean storeEnabled,
                                String repoUrl, String repoBranch, String repoUsername, boolean tokenSaved,
                                boolean autoNew, boolean onAlert, String repoCommit, String repoSyncTime,
                                String repoError) {

        static StoreResponse from(StoreView view) {
            return new StoreResponse(view.storeId(), view.storeCode(), view.storeName(), view.storeEnabled(),
                    view.repoUrl(), view.repoBranch(), view.repoUsername(), view.tokenSaved(), view.autoNew(),
                    view.onAlert(), view.repoCommit(), DateTimes.format(view.repoSyncedAt()), view.repoError());
        }
    }

    /**
     * @param token empty keeps the saved one
     */
    public record StoreRequest(String repoUrl, String repoBranch, String repoUsername, String token, Boolean autoNew,
                               Boolean onAlert) {
    }

    @GetMapping("/stores")
    public ApiResponse<List<StoreResponse>> stores(Authentication authentication) {
        return ApiResponse.success(storeAccessService.filter(authentication.getName(), service.stores(),
                StoreView::storeId).stream().map(StoreResponse::from).toList());
    }

    @PutMapping("/stores/{storeId}")
    @PreAuthorize(SETTINGS)
    public ApiResponse<StoreResponse> saveStore(@PathVariable Long storeId, @RequestBody StoreRequest request,
                                                Authentication authentication) {
        return ApiResponse.success(StoreResponse.from(service.saveStore(storeId, request.repoUrl(),
                request.repoBranch(), request.repoUsername(), request.token(), Boolean.TRUE.equals(request.autoNew()),
                Boolean.TRUE.equals(request.onAlert()), authentication.getName())));
    }

    /**
     * Clones or updates the repository now, which also checks the URL and token.
     */
    @PostMapping("/stores/{storeId}/sync")
    @PreAuthorize(SETTINGS)
    public ApiResponse<SyncResult> sync(@PathVariable Long storeId) {
        return ApiResponse.success(service.syncRepository(storeId));
    }

    // ---------- error groups ----------

    public record GroupResponse(Long id, Long storeId, String storeName, String errorType, String location,
                                String sample, String host, long occurrences, String firstSeen, String lastSeen,
                                String status, Long diagnosisId) {
    }

    public record StatusRequest(String status) {
    }

    @GetMapping("/groups")
    @PreAuthorize(GROUPS)
    public ApiResponse<List<GroupResponse>> groups(@RequestParam(required = false) Long storeId,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(defaultValue = "200") int limit,
                                                   Authentication authentication) {
        Map<Long, LogStore> stores = stores();
        return ApiResponse.success(storeAccessService.filter(authentication.getName(),
                        service.groups(storeId, status, limit), ErrorGroup::storeId).stream()
                .map((ErrorGroup group) -> new GroupResponse(group.id(), group.storeId(),
                        name(stores, group.storeId()), group.errorType(), group.location(), group.sample(),
                        group.host(), group.occurrences(), DateTimes.format(group.firstSeen()),
                        DateTimes.format(group.lastSeen()), group.status(), group.diagnosisId()))
                .toList());
    }

    @PutMapping("/groups/{id}/status")
    @PreAuthorize(GROUPS)
    public ApiResponse<Void> groupStatus(@PathVariable Long id, @RequestBody StatusRequest request,
                                         Authentication authentication) {
        storeAccessService.require(authentication.getName(), service.group(id).storeId());
        service.setGroupStatus(id, request.status());
        return ApiResponse.success(null);
    }

    @PostMapping("/groups/{id}/analyze")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize(GROUPS)
    public ApiResponse<DiagnosisItem> analyzeGroup(@PathVariable Long id, Authentication authentication) {
        storeAccessService.require(authentication.getName(), service.group(id).storeId());
        return ApiResponse.success(item(service.analyzeGroup(id, authentication.getName()), stores()));
    }

    // ---------- diagnoses ----------

    /**
     * A diagnosis in a list, without the code and the full answer.
     */
    public record DiagnosisItem(Long id, Long storeId, String storeName, Long groupId, String triggerType,
                                String status, String reviewState, String errorType, String summary,
                                String confidence, String host, String logTime, String createdBy, String createTime,
                                String finishTime, String error) {
    }

    public record CodeRefResponse(String path, int startLine, int focusLine, String frame, String text) {
    }

    public record DiagnosisResponse(DiagnosisItem item, Long alertEventId, String logText, String source,
                                    String repoCommit, List<CodeRefResponse> codeRefs, String provider, String model,
                                    String rootCause, String suggestion, String diff, Integer promptTokens,
                                    Integer completionTokens) {
    }

    public record AnalyzeRequest(Long storeId, String message, String host, String source, String time) {
    }

    @GetMapping("/diagnoses")
    @PreAuthorize(DIAGNOSES)
    public ApiResponse<List<DiagnosisItem>> diagnoses(@RequestParam(required = false) Long storeId,
                                                      @RequestParam(required = false) String state,
                                                      @RequestParam(required = false) String trigger,
                                                      @RequestParam(defaultValue = "30") int days,
                                                      @RequestParam(defaultValue = "200") int limit,
                                                      Authentication authentication) {
        Map<Long, LogStore> stores = stores();
        return ApiResponse.success(storeAccessService.filter(authentication.getName(),
                        service.diagnoses(storeId, state, trigger, days, limit), AiDiagnosis::storeId).stream()
                .map(diagnosis -> item(diagnosis, stores)).toList());
    }

    @GetMapping("/diagnoses/{id}")
    @PreAuthorize(DIAGNOSIS_READERS)
    public ApiResponse<DiagnosisResponse> diagnosis(@PathVariable Long id, Authentication authentication) {
        AiDiagnosis d = service.diagnosis(id);
        storeAccessService.require(authentication.getName(), d.storeId());
        return ApiResponse.success(new DiagnosisResponse(item(d, stores()), d.alertEventId(), d.logText(),
                d.source(), d.repoCommit(), codeRefs(d.codeRefs()), d.provider(), d.model(), d.rootCause(),
                d.suggestion(), d.diff(), d.promptTokens(), d.completionTokens()));
    }

    public record StateRequest(String state) {
    }

    @PutMapping("/diagnoses/{id}/state")
    @PreAuthorize(DIAGNOSES)
    public ApiResponse<Void> state(@PathVariable Long id, @RequestBody StateRequest request,
                                   Authentication authentication) {
        storeAccessService.require(authentication.getName(), service.diagnosis(id).storeId());
        service.setReviewState(id, request.state());
        return ApiResponse.success(null);
    }

    @PostMapping("/diagnoses/{id}/reanalyze")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize(DIAGNOSES)
    public ApiResponse<DiagnosisItem> reanalyze(@PathVariable Long id, Authentication authentication) {
        storeAccessService.require(authentication.getName(), service.diagnosis(id).storeId());
        return ApiResponse.success(item(service.reanalyze(id, authentication.getName()), stores()));
    }

    /**
     * Analyses a log picked in the log query.
     */
    @PostMapping("/analyze")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize(DIAGNOSES)
    public ApiResponse<DiagnosisItem> analyze(@RequestBody AnalyzeRequest request, Authentication authentication) {
        storeAccessService.require(authentication.getName(), request.storeId());
        Instant time = request.time() == null ? null : DateTimes.parse(request.time()).orElse(null);
        return ApiResponse.success(item(service.analyzeLog(request.storeId(), request.message(), request.host(),
                request.source(), time, authentication.getName()), stores()));
    }

    // ---------- helpers ----------

    private static DiagnosisItem item(AiDiagnosis d, Map<Long, LogStore> stores) {
        return new DiagnosisItem(d.id(), d.storeId(), name(stores, d.storeId()), d.groupId(), d.triggerType(),
                d.status(), d.reviewState(), d.errorType(), d.summary(), d.confidence(), d.host(),
                DateTimes.format(d.logTime()), d.createdBy(), DateTimes.format(d.createdAt()),
                DateTimes.format(d.finishedAt()), d.error());
    }

    private static List<CodeRefResponse> codeRefs(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode refs = JSON.readTree(json);
            return refs.valueStream().map(ref -> new CodeRefResponse(ref.path("path").asString(""),
                    ref.path("startLine").asInt(1), ref.path("focusLine").asInt(0), ref.path("frame").asString(""),
                    ref.path("text").asString(""))).toList();
        } catch (JacksonException e) {
            return List.of();
        }
    }

    private Map<Long, LogStore> stores() {
        Map<Long, LogStore> stores = new HashMap<>();
        logStoreRepository.findAll().forEach(store -> stores.put(store.id(), store));
        return stores;
    }

    private static String name(Map<Long, LogStore> stores, Long storeId) {
        LogStore store = stores.get(storeId);
        return store == null ? null : store.name();
    }
}
