package com.loggingx.web.ai;

import com.loggingx.biz.ai.AiDiagnosisService;
import com.loggingx.biz.monitor.AlertService;
import com.loggingx.biz.monitor.LogAlertService;
import com.loggingx.biz.permission.PermissionService;
import com.loggingx.biz.permission.StoreAccessService;
import com.loggingx.biz.permission.StoreAccessService.Scope;
import com.loggingx.common.api.ApiResponse;
import com.loggingx.common.util.DateTimes;
import com.loggingx.dao.ai.AiDiagnosis;
import com.loggingx.dao.ai.AiMapper;
import com.loggingx.dao.log.LogStore;
import com.loggingx.dao.log.LogStoreRepository;
import com.loggingx.dao.monitor.AlertEvent;
import com.loggingx.dao.monitor.LogAlertEvent;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The header bell: finished AI diagnoses of the last week and alerts that are firing, as far as the user's menus and
 * stores allow, and how many arrived since the user last read them.
 */
@RestController
@RequestMapping("/notices")
public class NoticeController {

    private static final int DAYS = 7;
    private static final int MAX_ITEMS = 50;

    private final AiDiagnosisService aiDiagnosisService;
    private final AlertService alertService;
    private final LogAlertService logAlertService;
    private final PermissionService permissionService;
    private final StoreAccessService storeAccessService;
    private final LogStoreRepository logStoreRepository;
    private final AiMapper aiMapper;

    public NoticeController(AiDiagnosisService aiDiagnosisService, AlertService alertService,
                            LogAlertService logAlertService, PermissionService permissionService,
                            StoreAccessService storeAccessService, LogStoreRepository logStoreRepository,
                            AiMapper aiMapper) {
        this.aiDiagnosisService = aiDiagnosisService;
        this.alertService = alertService;
        this.logAlertService = logAlertService;
        this.permissionService = permissionService;
        this.storeAccessService = storeAccessService;
        this.logStoreRepository = logStoreRepository;
        this.aiMapper = aiMapper;
    }

    /**
     * @param type  {@code ai}, {@code host} (a host alert) or {@code log} (a log alert)
     * @param level {@code critical}, {@code warning} or {@code info}
     * @param unread arrived after the user last read their notices
     */
    public record Notice(String type, Long id, String title, String detail, String level, long time,
                         String timeText, boolean unread) {
    }

    public record Notices(int unread, List<Notice> items) {
    }

    @GetMapping
    public ApiResponse<Notices> notices(Authentication authentication) {
        String username = authentication.getName();
        Instant seen = aiMapper.selectNoticeSeen(username);
        Scope scope = storeAccessService.scopeOf(username);
        Map<Long, String> storeNames = new HashMap<>();
        logStoreRepository.findAll().forEach((LogStore store) -> storeNames.put(store.id(), store.name()));
        List<Notice> items = new ArrayList<>();

        if (permissionService.hasAnyMenu(authentication, "AiDiagnosis")) {
            for (AiDiagnosis d : aiDiagnosisService.diagnoses(null, null, null, DAYS, MAX_ITEMS)) {
                if (!AiDiagnosis.SUCCESS.equals(d.status()) || !"open".equals(d.reviewState())
                        || !scope.includes(d.storeId())) {
                    continue;
                }
                Instant time = d.finishedAt() == null ? d.createdAt() : d.finishedAt();
                items.add(notice("ai", d.id(), "AI 诊断：" + (d.errorType() == null ? "错误日志" : d.errorType()),
                        storeNames.getOrDefault(d.storeId(), "") + " · " + (d.summary() == null ? "" : d.summary()),
                        "info", time, seen));
            }
        }
        if (permissionService.hasAnyMenu(authentication, "AlertEvent", "HostAlert", "HostMonitor")) {
            for (AlertEvent event : alertService.events(AlertEvent.FIRING, null, null, 30, MAX_ITEMS)) {
                if (scope.includes(event.storeId())) {
                    items.add(notice("host", event.id(), event.ruleName(), event.hostname() + " · "
                            + storeNames.getOrDefault(event.storeId(), ""), event.severity(), event.startedAt(), seen));
                }
            }
        }
        if (permissionService.hasAnyMenu(authentication, "AlertEvent", "HostAlert")) {
            for (LogAlertEvent event : logAlertService.events(LogAlertService.FIRING, null, 30, MAX_ITEMS)) {
                if (scope.includes(event.storeId())) {
                    items.add(notice("log", event.id(), event.ruleName(), storeNames.getOrDefault(event.storeId(), "")
                            + " · " + event.windowMinutes() + " 分钟 " + event.value() + " 条", event.severity(),
                            event.startedAt(), seen));
                }
            }
        }
        items.sort(Comparator.comparingLong(Notice::time).reversed());
        List<Notice> shown = items.size() > MAX_ITEMS ? items.subList(0, MAX_ITEMS) : items;
        return ApiResponse.success(new Notices((int) shown.stream().filter(Notice::unread).count(), shown));
    }

    /**
     * Marks every notice so far as read.
     */
    @PostMapping("/read")
    public ApiResponse<Void> read(Authentication authentication) {
        Instant now = Instant.now();
        if (aiMapper.updateNoticeSeen(authentication.getName(), now) == 0) {
            try {
                aiMapper.insertNoticeSeen(authentication.getName(), now);
            } catch (DuplicateKeyException e) {
                aiMapper.updateNoticeSeen(authentication.getName(), now);
            }
        }
        return ApiResponse.success(null);
    }

    private static Notice notice(String type, Long id, String title, String detail, String level, Instant time,
                                 Instant seen) {
        return new Notice(type, id, title, detail, level, time.toEpochMilli(), DateTimes.format(time),
                seen == null || time.isAfter(seen));
    }
}
