package com.loggingx.web.log;

import com.loggingx.biz.agent.AgentService;
import com.loggingx.biz.backup.BackupService;
import com.loggingx.biz.backup.BackupService.PolicyView;
import com.loggingx.biz.log.LogStoreService;
import com.loggingx.biz.monitor.MonitorService;
import com.loggingx.dao.agent.AgentCount;
import com.loggingx.dao.log.LogStore;
import com.loggingx.common.api.ApiResponse;
import com.loggingx.web.log.dto.LogStoreOption;
import com.loggingx.web.log.dto.LogStoreRequest;
import com.loggingx.web.log.dto.LogStoreResponse;
import jakarta.validation.Valid;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Log store management. Every endpoint except {@link #options()} requires access to the log store menu, since the
 * responses include the ingest tokens.
 */
@RestController
@RequestMapping("/logstore")
@PreAuthorize("@permissionService.hasAnyMenu(authentication, 'LogStore')")
public class LogStoreController {

    private final LogStoreService logStoreService;
    private final AgentService agentService;
    private final MonitorService monitorService;
    private final BackupService backupService;

    public LogStoreController(LogStoreService logStoreService, AgentService agentService,
                              MonitorService monitorService, BackupService backupService) {
        this.backupService = backupService;
        this.logStoreService = logStoreService;
        this.agentService = agentService;
        this.monitorService = monitorService;
    }

    /**
     * Also open to the agent deployment page, whose install commands carry the stores' ingest tokens.
     */
    @GetMapping("/list")
    @PreAuthorize("@permissionService.hasAnyMenu(authentication, 'LogStore', 'LogAgent')")
    public ApiResponse<List<LogStoreResponse>> list() {
        Map<Long, AgentCount> agents = agentService.counts();
        Map<Long, LogStoreResponse.Backup> backups = new HashMap<>();
        for (PolicyView policy : backupService.policies()) {
            Long storeId = policy.store().id();
            boolean enabled = policy.policy() != null && policy.policy().enabled();
            backups.put(storeId, LogStoreResponse.Backup.from(policy,
                    enabled ? backupService.pendingDays(storeId) : 0));
        }
        return ApiResponse.success(logStoreService.list().stream()
                .map(view -> LogStoreResponse.from(view, agents.get(view.store().id()),
                        backups.get(view.store().id())))
                .toList());
    }

    /**
     * Stores for the store pickers of the log pages, without ingest tokens.
     */
    @GetMapping("/options")
    @PreAuthorize("@permissionService.hasAnyMenu(authentication, 'LogStore', 'LogQuery', 'LogTail')")
    public ApiResponse<List<LogStoreOption>> options() {
        return ApiResponse.success(logStoreService.all().stream().map(LogStoreOption::from).toList());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<LogStoreResponse> create(@Valid @RequestBody LogStoreRequest request,
                                                Authentication authentication) {
        LogStoreRequest.Backup backup = request.backup();
        boolean archive = backup != null && Boolean.TRUE.equals(backup.enabled());
        if (archive) {
            BackupService.validatePolicy(request.retentionDays(), value(backup.archiveAfterDays()),
                    value(backup.keepDays()));
        }
        LogStore created = logStoreService.create(request.toCommand(), authentication.getName());
        if (archive) {
            backupService.savePolicy(created.id(), true, backup.archiveAfterDays(), backup.keepDays(),
                    authentication.getName());
        }
        return ApiResponse.success(LogStoreResponse.from(created));
    }

    @PutMapping("/{id}")
    public ApiResponse<LogStoreResponse> update(@PathVariable Long id, @Valid @RequestBody LogStoreRequest request,
                                                Authentication authentication) {
        LogStoreRequest.Backup backup = request.backup();
        boolean saveBackup = backup != null && backup.archiveAfterDays() != null;
        if (saveBackup) {
            // Checked against the new retention before anything is saved; a paused policy need not fit it.
            BackupService.validatePolicy(Boolean.TRUE.equals(backup.enabled()) ? request.retentionDays()
                    : Integer.MAX_VALUE, value(backup.archiveAfterDays()), value(backup.keepDays()));
        } else {
            backupService.checkRetention(id, request.retentionDays());
        }
        LogStore updated = logStoreService.update(id, request.toCommand(), authentication.getName());
        if (saveBackup) {
            backupService.savePolicy(id, Boolean.TRUE.equals(backup.enabled()), backup.archiveAfterDays(),
                    backup.keepDays(), authentication.getName());
        }
        return ApiResponse.success(LogStoreResponse.from(updated));
    }

    /**
     * Replaces the ingest token; producers still using the old one are rejected.
     */
    @PostMapping("/{id}/token")
    public ApiResponse<LogStoreResponse> regenerateToken(@PathVariable Long id, Authentication authentication) {
        return ApiResponse.success(LogStoreResponse.from(
                logStoreService.regenerateToken(id, authentication.getName())));
    }

    /**
     * @param deleteArchives also delete the store's archives from the backup storage; by default they are kept and
     *                       can still be downloaded
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean deleteArchives,
                                    Authentication authentication) {
        logStoreService.delete(id, authentication.getName());
        if (deleteArchives) {
            backupService.deleteStoreArchives(id, authentication.getName());
        }
        monitorService.deleteStore(id);
        return ApiResponse.success(null);
    }

    private static int value(Integer number) {
        return number == null ? 0 : number;
    }
}
