package com.loggingx.web.backup;

import com.loggingx.biz.backup.BackupService;
import com.loggingx.biz.backup.BackupService.Download;
import com.loggingx.biz.backup.BackupService.PolicyView;
import com.loggingx.biz.backup.BackupService.RunStatus;
import com.loggingx.biz.backup.BackupService.StorageCommand;
import com.loggingx.biz.backup.BackupService.StorageView;
import com.loggingx.biz.permission.StoreAccessService;
import com.loggingx.common.api.ApiResponse;
import com.loggingx.common.util.DateTimes;
import com.loggingx.dao.backup.BackupArchive;
import com.loggingx.dao.backup.BackupPolicy;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * Log backup management: the backup bucket, each store's archiving policy, the archives, restores and downloads.
 * Everything requires the backup menu, except {@code /download/<token>}, which the token authorizes (so the browser
 * can download a large file directly).
 */
@RestController
@RequestMapping("/backup")
public class BackupController {

    /** The archive policies page: storage, schedule and per-store policies. */
    private static final String POLICIES = "@permissionService.hasAnyMenu(authentication, 'LogBackup')";
    /** The archives page: download and restore. */
    private static final String ARCHIVES = "@permissionService.hasAnyMenu(authentication, 'BackupArchive')";
    /** Both pages show the stores' archive totals and the running task. */
    private static final String BACKUP_PAGES =
            "@permissionService.hasAnyMenu(authentication, 'LogBackup', 'BackupArchive')";

    private final BackupService backupService;
    private final StoreAccessService storeAccessService;

    public BackupController(BackupService backupService, StoreAccessService storeAccessService) {
        this.backupService = backupService;
        this.storeAccessService = storeAccessService;
    }

    public record StorageRequest(String endpoint, String region, String bucket, String prefix, String accessKey,
                                 String secretKey, Boolean pathStyle) {

        StorageCommand toCommand() {
            return new StorageCommand(endpoint, region, bucket, prefix, accessKey, secretKey,
                    pathStyle == null || pathStyle);
        }
    }

    public record StorageResponse(String endpoint, String region, String bucket, String prefix, String accessKey,
                                  boolean secretSaved, boolean pathStyle, String updateTime) {

        static StorageResponse from(StorageView view) {
            return view == null ? null : new StorageResponse(view.endpoint(), view.region(), view.bucket(),
                    view.prefix(), view.accessKey(), view.secretSaved(), view.pathStyle(),
                    DateTimes.format(view.updatedAt()));
        }
    }

    public record PolicyRequest(Boolean enabled, Integer archiveAfterDays, Integer keepDays) {
    }

    /**
     * @param enabled whether the store is archived; {@code null} fields when it has no policy
     */
    public record PolicyResponse(Long storeId, String storeCode, String storeName, int retentionDays,
                                 boolean storeEnabled, Boolean enabled, Integer archiveAfterDays, Integer keepDays,
                                 long archives, long archivedBytes, long rawBytes, String latestDay, long failed) {

        static PolicyResponse from(PolicyView view) {
            BackupPolicy policy = view.policy();
            return new PolicyResponse(view.store().id(), view.store().code(), view.store().name(),
                    view.store().retentionDays(), view.store().enabled(), policy == null ? null : policy.enabled(),
                    policy == null ? null : policy.archiveAfterDays(), policy == null ? null : policy.keepDays(),
                    view.archives(), view.archivedBytes(), view.rawBytes(),
                    view.latestDay() == null ? null : view.latestDay().toString(), view.failed());
        }
    }

    /**
     * @param restoreStatus  {@code restoring}, {@code restored}, {@code failed}, {@code expired} (the restored logs
     *                       were removed again) or {@code null}
     */
    public record ArchiveResponse(Long id, Long storeId, String storeCode, String day, String objectKey,
                                  long recordCount, long rawBytes, long sizeBytes, String sha256, String status,
                                  String error, String archiveTime, String restoreStatus, String restoreError,
                                  String restoreTime, String restoredUntil) {

        static ArchiveResponse from(BackupArchive archive) {
            String restore = archive.restoreStatus();
            if (BackupArchive.RESTORED.equals(restore) && archive.restoredUntil() != null
                    && archive.restoredUntil().isBefore(Instant.now())) {
                restore = "expired";
            }
            return new ArchiveResponse(archive.id(), archive.storeId(), archive.storeCode(), archive.day().toString(),
                    archive.objectKey(), archive.recordCount(), archive.rawBytes(), archive.sizeBytes(),
                    archive.sha256(), archive.status(), archive.error(), DateTimes.format(archive.archivedAt()),
                    restore, archive.restoreError(), DateTimes.format(archive.restoredAt()),
                    DateTimes.format(archive.restoredUntil()));
        }
    }

    public record StatusResponse(boolean running, String lastStartTime, String lastFinishTime, Integer archived,
                                 Integer failed, Integer deleted, String error) {

        static StatusResponse from(RunStatus status) {
            var last = status.lastRun();
            return new StatusResponse(status.running(), last == null ? null : DateTimes.format(last.startedAt()),
                    last == null ? null : DateTimes.format(last.finishedAt()),
                    last == null ? null : last.archived(), last == null ? null : last.failed(),
                    last == null ? null : last.deleted(), last == null ? null : last.error());
        }
    }

    public record DownloadLink(String url) {
    }

    // ---------- storage ----------

    /**
     * @return the settings, or {@code null} data when not configured; the secret key is never returned
     */
    @GetMapping("/storage")
    @PreAuthorize(POLICIES)
    public ApiResponse<StorageResponse> storage() {
        return ApiResponse.success(StorageResponse.from(backupService.storage()));
    }

    /**
     * @param request {@code secretKey} empty keeps the saved one
     */
    @PutMapping("/storage")
    @PreAuthorize(POLICIES)
    public ApiResponse<StorageResponse> saveStorage(@RequestBody StorageRequest request,
                                                    Authentication authentication) {
        return ApiResponse.success(StorageResponse.from(
                backupService.saveStorage(request.toCommand(), authentication.getName())));
    }

    /**
     * Checks the settings without saving them: reaches the bucket, writes and deletes a small object.
     */
    @PostMapping("/storage/test")
    @PreAuthorize(POLICIES)
    public ApiResponse<Void> testStorage(@RequestBody StorageRequest request) {
        backupService.testStorage(request.toCommand());
        return ApiResponse.success(null);
    }

    // ---------- policies ----------

    @GetMapping("/policies")
    @PreAuthorize(BACKUP_PAGES)
    public ApiResponse<List<PolicyResponse>> policies(Authentication authentication) {
        return ApiResponse.success(storeAccessService.filter(authentication.getName(), backupService.policies(),
                (PolicyView policy) -> policy.store().id()).stream().map(PolicyResponse::from).toList());
    }

    @PutMapping("/policies/{storeId}")
    @PreAuthorize(POLICIES)
    public ApiResponse<Void> savePolicy(@PathVariable Long storeId, @RequestBody PolicyRequest request,
                                        Authentication authentication) {
        backupService.savePolicy(storeId, request.enabled() == null || request.enabled(),
                request.archiveAfterDays() == null ? 0 : request.archiveAfterDays(),
                request.keepDays() == null ? 0 : request.keepDays(), authentication.getName());
        return ApiResponse.success(null);
    }

    // ---------- runs and archives ----------

    @GetMapping("/status")
    @PreAuthorize(BACKUP_PAGES)
    public ApiResponse<StatusResponse> status() {
        return ApiResponse.success(StatusResponse.from(backupService.status()));
    }

    /**
     * Starts an archive run now (it also runs daily).
     */
    @PostMapping("/run")
    @PreAuthorize(POLICIES)
    public ApiResponse<StatusResponse> run(Authentication authentication) {
        backupService.runNow(authentication.getName());
        return ApiResponse.success(StatusResponse.from(backupService.status()));
    }

    @GetMapping("/archives")
    @PreAuthorize(ARCHIVES)
    public ApiResponse<List<ArchiveResponse>> archives(@RequestParam(required = false) Long storeId,
                                                       @RequestParam(required = false) String status,
                                                       @RequestParam(defaultValue = "500") int limit,
                                                       Authentication authentication) {
        return ApiResponse.success(storeAccessService.filter(authentication.getName(),
                        backupService.archives(storeId, status, limit), BackupArchive::storeId).stream()
                .map(ArchiveResponse::from).toList());
    }

    @PostMapping("/archives/{id}/restore")
    @PreAuthorize(ARCHIVES)
    public ApiResponse<ArchiveResponse> restore(@PathVariable Long id, Authentication authentication) {
        storeAccessService.require(authentication.getName(), backupService.archiveStoreId(id));
        return ApiResponse.success(ArchiveResponse.from(backupService.restore(id, authentication.getName())));
    }

    /**
     * @return a link the browser downloads the archive from, valid for 10 minutes
     */
    @PostMapping("/archives/{id}/download-link")
    @PreAuthorize(ARCHIVES)
    public ApiResponse<DownloadLink> downloadLink(@PathVariable Long id, Authentication authentication) {
        storeAccessService.require(authentication.getName(), backupService.archiveStoreId(id));
        return ApiResponse.success(new DownloadLink("/api/backup/download/" + backupService.downloadToken(id)));
    }

    @GetMapping("/download/{token}")
    public ResponseEntity<InputStreamResource> download(@PathVariable String token) {
        Download download = backupService.download(token);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/gzip"))
                .contentLength(download.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(download.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(new InputStreamResource(download.content()));
    }
}
