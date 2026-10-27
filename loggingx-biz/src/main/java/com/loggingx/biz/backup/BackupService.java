package com.loggingx.biz.backup;

import com.loggingx.biz.log.LogRetentionService;
import com.loggingx.biz.log.RetentionHold;
import com.loggingx.biz.support.SecretCipher;
import com.loggingx.common.exception.BadRequestException;
import com.loggingx.common.exception.ConflictException;
import com.loggingx.common.exception.NotFoundException;
import com.loggingx.dao.backup.BackupArchive;
import com.loggingx.dao.backup.BackupMapper;
import com.loggingx.dao.backup.BackupPolicy;
import com.loggingx.dao.backup.BackupStorage;
import com.loggingx.dao.log.LogRecordRepository;
import com.loggingx.dao.log.LogSearch;
import com.loggingx.dao.log.LogStore;
import com.loggingx.dao.log.LogStoreRepository;
import com.loggingx.dao.log.TimeRange;
import com.loggingx.dao.support.GeneratedKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Log backups: every day, the days of each store with an enabled policy that are older than its
 * {@code archiveAfterDays} are written to the S3-compatible backup bucket, one gzip-compressed file of JSON lines per
 * store and day ({@code <prefix>/<store>/<yyyy>/<MM>/<dd>.ndjson.gz}, see {@link ArchiveCodec}), and archives older
 * than {@code keepDays} are deleted. An archive can be restored into its store, where its logs stay queryable for
 * {@value #RESTORE_DAYS} days despite the store's retention, and downloaded.
 * <p>
 * Days are those of the server's time zone. A day is archived once; logs arriving for it later are not added. Runs and
 * restores happen one at a time in the background.
 */
@Service
public class BackupService implements RetentionHold, DisposableBean {

    /** Restored logs stay this many days. */
    static final int RESTORE_DAYS = 7;
    static final int MAX_KEEP_DAYS = 3650;
    private static final int PAGE = 5000;
    private static final Duration DOWNLOAD_LINK = Duration.ofMinutes(10);
    private static final Pattern BUCKET = Pattern.compile("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]");
    private static final Pattern PREFIX = Pattern.compile("[\\w./-]{0,200}");
    private static final DateTimeFormatter KEY_DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final Logger log = LoggerFactory.getLogger(BackupService.class);

    private final BackupMapper backupMapper;
    private final LogStoreRepository logStoreRepository;
    private final LogRecordRepository logRecordRepository;
    private final SecretCipher cipher;
    private final ZoneId zone = ZoneId.systemDefault();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "loggingx-backup");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile RunSummary lastRun;

    public BackupService(BackupMapper backupMapper, LogStoreRepository logStoreRepository,
                         LogRecordRepository logRecordRepository, SecretCipher cipher) {
        this.backupMapper = backupMapper;
        this.logStoreRepository = logStoreRepository;
        this.logRecordRepository = logRecordRepository;
        this.cipher = cipher;
    }

    @Override
    public void destroy() {
        worker.shutdownNow();
    }

    // ---------- storage ----------

    /**
     * The storage settings as entered; {@code secretKey} is {@code null} to keep the saved one.
     */
    public record StorageCommand(String endpoint, String region, String bucket, String prefix, String accessKey,
                                 String secretKey, boolean pathStyle) {
    }

    /**
     * @param secretSaved whether a secret key is saved (it is never returned)
     */
    public record StorageView(String endpoint, String region, String bucket, String prefix, String accessKey,
                              boolean secretSaved, boolean pathStyle, Instant updatedAt) {
    }

    /**
     * @return the saved settings, or {@code null} when none
     */
    public StorageView storage() {
        BackupStorage storage = backupMapper.selectStorage();
        return storage == null ? null : new StorageView(storage.endpoint(), storage.region(), storage.bucket(),
                storage.prefix(), storage.accessKey(), true, storage.pathStyle(), storage.updatedAt());
    }

    public StorageView saveStorage(StorageCommand command, String operatorUsername) {
        BackupStorage storage = validate(command);
        if (backupMapper.updateStorage(storage) == 0) {
            try {
                backupMapper.insertStorage(storage);
            } catch (DuplicateKeyException e) {
                backupMapper.updateStorage(storage);
            }
        }
        log.info("event=backup.storage.update operator={} endpoint={} bucket={}", operatorUsername,
                storage.endpoint(), storage.bucket());
        return storage();
    }

    /**
     * Checks the settings: the bucket is reachable, and an object can be written and deleted.
     *
     * @throws BadRequestException with the storage's error when not
     */
    public void testStorage(StorageCommand command) {
        BackupStorage storage = validate(command);
        S3Client client = client(storage);
        String key = key(storage.prefix(), ".loggingx-check-" + UUID.randomUUID());
        try {
            client.checkBucket();
            client.putText(key, "LoggingX backup storage check");
            client.delete(key);
        } catch (S3Client.S3Exception e) {
            throw new BadRequestException(e.getMessage());
        }
    }

    private BackupStorage validate(StorageCommand command) {
        String endpoint = trim(command.endpoint()).replaceAll("/+$", "");
        try {
            URI uri = URI.create(endpoint);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                    || (uri.getPath() != null && !uri.getPath().isEmpty())) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("endpoint must be an http(s) address without a path, e.g. "
                    + "https://oss-cn-hangzhou.aliyuncs.com or http://minio:9000");
        }
        String bucket = trim(command.bucket());
        if (!BUCKET.matcher(bucket).matches()) {
            throw new BadRequestException("bucket must be 3-63 lowercase letters, digits, '.' or '-'");
        }
        String region = trim(command.region()).isEmpty() ? "us-east-1" : trim(command.region());
        String prefix = trim(command.prefix()).replaceAll("^/+|/+$", "");
        if (!PREFIX.matcher(prefix).matches() || prefix.contains("..")) {
            throw new BadRequestException("prefix may only contain letters, digits, '_', '-', '.' and '/'");
        }
        String accessKey = trim(command.accessKey());
        if (accessKey.isEmpty()) {
            throw new BadRequestException("accessKey is required");
        }
        String secret = command.secretKey() == null || command.secretKey().isEmpty() ? null : command.secretKey();
        String encrypted;
        if (secret != null) {
            encrypted = cipher.encrypt(secret);
        } else {
            BackupStorage saved = backupMapper.selectStorage();
            if (saved == null) {
                throw new BadRequestException("secretKey is required");
            }
            encrypted = saved.secretKey();
        }
        return new BackupStorage(endpoint, region, bucket, prefix, accessKey, encrypted, command.pathStyle(),
                Instant.now());
    }

    private S3Client client(BackupStorage storage) {
        String secret;
        try {
            secret = cipher.decrypt(storage.secretKey());
        } catch (IllegalStateException e) {
            throw new BadRequestException(e.getMessage());
        }
        return new S3Client(storage.endpoint(), storage.region(), storage.bucket(), storage.accessKey(), secret,
                storage.pathStyle());
    }

    private BackupStorage requireStorage() {
        BackupStorage storage = backupMapper.selectStorage();
        if (storage == null) {
            throw new BadRequestException("The backup storage is not configured");
        }
        return storage;
    }

    // ---------- policies ----------

    /**
     * A store with its archiving policy ({@code null} when not configured) and archives.
     *
     * @param archives       successful archives
     * @param archivedBytes  their total size
     * @param latestDay      the newest archived day, or {@code null}
     * @param failed         failed archives, retried on the next run
     */
    public record PolicyView(LogStore store, BackupPolicy policy, long archives, long archivedBytes, long rawBytes,
                             LocalDate latestDay, long failed) {
    }

    public List<PolicyView> policies() {
        Map<Long, BackupPolicy> policies = new HashMap<>();
        backupMapper.selectPolicies().forEach(policy -> policies.put(policy.storeId(), policy));
        Map<Long, List<BackupArchive>> archives = new HashMap<>();
        backupMapper.selectArchives(null, null, Integer.MAX_VALUE)
                .forEach(archive -> archives.computeIfAbsent(archive.storeId(), id -> new ArrayList<>()).add(archive));
        List<PolicyView> views = new ArrayList<>();
        for (LogStore store : logStoreRepository.findAll()) {
            List<BackupArchive> list = archives.getOrDefault(store.id(), List.of());
            List<BackupArchive> ok = list.stream().filter(a -> BackupArchive.SUCCESS.equals(a.status())).toList();
            views.add(new PolicyView(store, policies.get(store.id()), ok.size(),
                    ok.stream().mapToLong(BackupArchive::sizeBytes).sum(),
                    ok.stream().mapToLong(BackupArchive::rawBytes).sum(),
                    ok.stream().map(BackupArchive::day).max(LocalDate::compareTo).orElse(null),
                    list.size() - ok.size()));
        }
        return views;
    }

    /**
     * Checks a policy against a store retention: a day is archived before the retention deletes it, and archives
     * outlive the logs.
     *
     * @throws BadRequestException when not
     */
    public static void validatePolicy(int retentionDays, int archiveAfterDays, int keepDays) {
        if (archiveAfterDays < 1 || archiveAfterDays >= retentionDays) {
            throw new BadRequestException("archiveAfterDays must be at least 1 and less than the store's retention ("
                    + retentionDays + " days), so every day is archived before its logs are deleted");
        }
        if (keepDays <= archiveAfterDays || keepDays > MAX_KEEP_DAYS) {
            throw new BadRequestException("keepDays must be more than archiveAfterDays and at most " + MAX_KEEP_DAYS);
        }
    }

    public BackupPolicy savePolicy(Long storeId, boolean enabled, int archiveAfterDays, int keepDays,
                                   String operatorUsername) {
        LogStore store = logStoreRepository.findById(storeId)
                .orElseThrow(() -> new NotFoundException("Log store was not found"));
        // A paused policy may not fit the retention; it is checked again when enabled.
        validatePolicy(enabled ? store.retentionDays() : Integer.MAX_VALUE, archiveAfterDays, keepDays);
        BackupPolicy policy = new BackupPolicy(storeId, enabled, archiveAfterDays, keepDays, Instant.now());
        if (backupMapper.updatePolicy(policy) == 0) {
            try {
                backupMapper.insertPolicy(policy);
            } catch (DuplicateKeyException e) {
                backupMapper.updatePolicy(policy);
            }
        }
        log.info("event=backup.policy.update operator={} storeId={} enabled={} archiveAfterDays={} keepDays={}",
                operatorUsername, storeId, enabled, archiveAfterDays, keepDays);
        return policy;
    }

    // ---------- archives ----------

    public List<BackupArchive> archives(Long storeId, String status, int limit) {
        return backupMapper.selectArchives(storeId, status == null || status.isBlank() ? null : status,
                Math.max(1, Math.min(limit, 2000)));
    }

    /**
     * @param running     whether a run is in progress
     * @param lastRun     the latest finished run, or {@code null}
     */
    public record RunStatus(boolean running, RunSummary lastRun) {
    }

    /**
     * @param archived days archived
     * @param failed   days that failed (retried next run)
     * @param deleted  archives deleted because they expired
     */
    public record RunSummary(Instant startedAt, Instant finishedAt, int archived, int failed, int deleted,
                             String error) {
    }

    public RunStatus status() {
        return new RunStatus(running.get(), lastRun);
    }

    /**
     * Starts a run in the background.
     *
     * @return {@code false} when one is already running
     */
    public boolean runNow(String operatorUsername) {
        if (running.get()) {
            return false;
        }
        log.info("event=backup.run.request operator={}", operatorUsername);
        worker.submit(this::run);
        return true;
    }

    /**
     * Runs daily, by default at 02:30 server time, before the log retention cleanup.
     */
    @Scheduled(cron = "${loggingx.backup.cron:0 30 2 * * *}")
    public void scheduledRun() {
        worker.submit(this::run);
    }

    /**
     * Archives the due days of every enabled store and deletes expired archives. Runs one at a time.
     */
    void run() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Instant started = Instant.now();
        int archived = 0;
        int failed = 0;
        int deleted = 0;
        String error = null;
        try {
            BackupStorage storage = backupMapper.selectStorage();
            List<BackupPolicy> policies = backupMapper.selectPolicies().stream().filter(BackupPolicy::enabled)
                    .toList();
            if (storage == null) {
                if (!policies.isEmpty()) {
                    error = "The backup storage is not configured";
                }
                return;
            }
            S3Client client = client(storage);
            LocalDate today = LocalDate.now(zone);
            for (BackupPolicy policy : policies) {
                LogStore store = logStoreRepository.findById(policy.storeId()).orElse(null);
                if (store == null) {
                    continue;
                }
                // Days past the retention may still be held back for archiving (see held), up to the TTL grace period.
                LocalDate first = today.minusDays(store.retentionDays() + LogRetentionService.TTL_GRACE_DAYS);
                LocalDate last = today.minusDays(policy.archiveAfterDays());
                for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
                    BackupArchive existing = backupMapper.selectArchiveByDay(store.id(), day);
                    if (existing != null && BackupArchive.SUCCESS.equals(existing.status())) {
                        continue;
                    }
                    TimeRange range = range(day);
                    if (existing == null && logRecordRepository.count(new LogSearch(store.id(), range.start(),
                            range.end(), null, null, null, null)) == 0) {
                        continue;
                    }
                    if (archiveDay(client, storage, store, day, existing)) {
                        archived++;
                    } else {
                        failed++;
                    }
                }
                deleted += deleteExpired(client, store, policy, today);
            }
        } catch (RuntimeException e) {
            error = e.getMessage();
            log.warn("Backup run failed: {}", e.getMessage(), e);
        } finally {
            lastRun = new RunSummary(started, Instant.now(), archived, failed, deleted, error);
            running.set(false);
            log.info("event=backup.run.finished archived={} failed={} deleted={} error={}", archived, failed, deleted,
                    error);
        }
    }

    /**
     * Writes one day of a store to the bucket and records the archive.
     *
     * @param existing the day's failed archive, retried, or {@code null}
     * @return whether it succeeded
     */
    private boolean archiveDay(S3Client client, BackupStorage storage, LogStore store, LocalDate day,
                               BackupArchive existing) {
        String key = key(storage.prefix(), store.code() + "/" + day.format(KEY_DATE) + ".ndjson.gz");
        TimeRange range = range(day);
        Path file = null;
        BackupArchive result;
        try {
            file = Files.createTempFile("loggingx-archive-", ".ndjson.gz");
            ArchiveCodec.Written written = ArchiveCodec.write(file, after ->
                    logRecordRepository.scan(store.id(), range.start(), range.end(), after, PAGE));
            client.put(key, file, written.sha256(), "application/gzip");
            result = new BackupArchive(existing == null ? null : existing.id(), store.id(), store.code(), day, key,
                    written.records(), written.rawBytes(), written.sizeBytes(), written.sha256(),
                    BackupArchive.SUCCESS, null, Instant.now(), null, null, null, null);
            log.info("event=backup.archive.success store={} day={} records={} bytes={}", store.code(), day,
                    written.records(), written.sizeBytes());
        } catch (IOException | RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            result = new BackupArchive(existing == null ? null : existing.id(), store.id(), store.code(), day, key,
                    0, 0, 0, null, BackupArchive.FAILED, truncate(message), Instant.now(), null, null, null, null);
            log.warn("event=backup.archive.failed store={} day={} error={}", store.code(), day, message);
        } finally {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    log.debug("Cannot delete {}", file, e);
                }
            }
        }
        if (result.id() == null) {
            backupMapper.insertArchive(result, new GeneratedKey());
        } else {
            backupMapper.updateArchive(result);
        }
        return BackupArchive.SUCCESS.equals(result.status());
    }

    /**
     * Deletes the store's archives of days more than {@code keepDays} ago, except restored ones still in use.
     *
     * @return how many were deleted
     */
    private int deleteExpired(S3Client client, LogStore store, BackupPolicy policy, LocalDate today) {
        int deleted = 0;
        Instant now = Instant.now();
        for (BackupArchive archive : backupMapper.selectArchivesBefore(store.id(),
                today.minusDays(policy.keepDays()))) {
            if (archive.restoredUntil() != null && archive.restoredUntil().isAfter(now)) {
                continue;
            }
            try {
                client.delete(archive.objectKey());
                backupMapper.deleteArchive(archive.id());
                deleted++;
            } catch (S3Client.S3Exception e) {
                log.warn("Cannot delete expired archive {}: {}", archive.objectKey(), e.getMessage());
            }
        }
        return deleted;
    }

    // ---------- restore ----------

    /**
     * Restores an archive into its store in the background. Its logs stay queryable for {@value #RESTORE_DAYS} days.
     *
     * @throws ConflictException when the day's logs are still in the store, or a restore is in progress
     */
    public BackupArchive restore(Long archiveId, String operatorUsername) {
        BackupArchive archive = requireArchive(archiveId);
        if (!BackupArchive.SUCCESS.equals(archive.status())) {
            throw new BadRequestException("Only successful archives can be restored");
        }
        LogStore store = logStoreRepository.findById(archive.storeId())
                .orElseThrow(() -> new BadRequestException("The archive's log store was deleted"));
        if (BackupArchive.RESTORING.equals(archive.restoreStatus())) {
            throw new ConflictException("The archive is being restored");
        }
        TimeRange range = range(archive.day());
        if (logRecordRepository.count(new LogSearch(store.id(), range.start(), range.end(), null, null, null,
                null)) > 0) {
            throw new ConflictException("The day's logs are still in the log store; query them directly");
        }
        requireStorage();
        Instant now = Instant.now();
        Instant until = now.plus(Duration.ofDays(RESTORE_DAYS));
        BackupArchive restoring = archive.withRestore(BackupArchive.RESTORING, null, now, until);
        backupMapper.updateArchive(restoring);
        log.info("event=backup.restore.request operator={} store={} day={}", operatorUsername, archive.storeCode(),
                archive.day());
        worker.submit(() -> restoreNow(restoring, store));
        return restoring;
    }

    private void restoreNow(BackupArchive archive, LogStore store) {
        TimeRange range = range(archive.day());
        // Rows carry their retention; it must outlast the restore window or storage TTL would drop them earlier.
        int retentionDays = (int) Duration.between(range.start(), archive.restoredUntil()).toDays() + 2;
        try (InputStream in = client(requireStorage()).get(archive.objectKey())) {
            long count = ArchiveCodec.read(in, store.id(), PAGE,
                    batch -> logRecordRepository.insertAll(batch, retentionDays));
            backupMapper.updateArchive(archive.withRestore(BackupArchive.RESTORED, null, archive.restoredAt(),
                    archive.restoredUntil()));
            log.info("event=backup.restore.success store={} day={} records={}", store.code(), archive.day(), count);
        } catch (IOException | RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            // Remove what was inserted, so the restore can be retried.
            try {
                logRecordRepository.deleteBetween(store.id(), range);
            } catch (RuntimeException cleanup) {
                log.warn("Cannot remove the partly restored logs of {} {}", store.code(), archive.day(), cleanup);
            }
            backupMapper.updateArchive(archive.withRestore(BackupArchive.FAILED, truncate(message),
                    archive.restoredAt(), null));
            log.warn("event=backup.restore.failed store={} day={} error={}", store.code(), archive.day(), message);
        }
    }

    /**
     * Restored days until their restore expires, and, for a store with an enabled policy, days past the retention
     * that are not archived yet (the archive failed, or the storage was unreachable), so no log is deleted before it
     * is backed up. Held days are retried by every run, for at most {@link LogRetentionService#TTL_GRACE_DAYS} days,
     * after which the storage drops them anyway.
     */
    @Override
    public List<TimeRange> held(Long storeId, Instant now) {
        List<TimeRange> held = new ArrayList<>(backupMapper.selectRestoredAfter(now).stream()
                .filter(archive -> storeId.equals(archive.storeId()))
                .map(archive -> range(archive.day()))
                .toList());
        BackupPolicy policy = backupMapper.selectPolicy(storeId);
        LogStore store = logStoreRepository.findById(storeId).orElse(null);
        if (policy == null || !policy.enabled() || store == null) {
            return held;
        }
        LocalDate cutoff = now.minus(Duration.ofDays(store.retentionDays())).atZone(zone).toLocalDate();
        java.util.Set<LocalDate> archived = new java.util.HashSet<>();
        backupMapper.selectArchives(storeId, BackupArchive.SUCCESS, Integer.MAX_VALUE)
                .forEach(archive -> archived.add(archive.day()));
        for (LocalDate day = cutoff.minusDays(LogRetentionService.TTL_GRACE_DAYS); !day.isAfter(cutoff);
             day = day.plusDays(1)) {
            if (!archived.contains(day)) {
                held.add(range(day));
            }
        }
        return held;
    }

    /**
     * @return how many days of the store are held back from deletion because they are not archived yet
     */
    public int pendingDays(Long storeId) {
        Instant now = Instant.now();
        BackupPolicy policy = backupMapper.selectPolicy(storeId);
        LogStore store = logStoreRepository.findById(storeId).orElse(null);
        if (policy == null || !policy.enabled() || store == null) {
            return 0;
        }
        Instant cutoff = now.minus(Duration.ofDays(store.retentionDays()));
        // Held days that still have logs: from the oldest log on.
        List<com.loggingx.dao.log.LogRecord> oldest = logRecordRepository.scan(storeId, Instant.EPOCH, cutoff, null, 1);
        if (oldest.isEmpty()) {
            return 0;
        }
        java.util.Set<LocalDate> archived = new java.util.HashSet<>();
        backupMapper.selectArchives(storeId, BackupArchive.SUCCESS, Integer.MAX_VALUE)
                .forEach(archive -> archived.add(archive.day()));
        int pending = 0;
        LocalDate last = cutoff.atZone(zone).toLocalDate();
        LocalDate first = oldest.get(0).time().atZone(zone).toLocalDate();
        if (first.isBefore(last.minusDays(LogRetentionService.TTL_GRACE_DAYS))) {
            first = last.minusDays(LogRetentionService.TTL_GRACE_DAYS);
        }
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            TimeRange range = range(day);
            // Days without logs are never archived; only those with logs are waiting.
            if (!archived.contains(day) && logRecordRepository.count(new LogSearch(storeId, range.start(),
                    range.end(), null, null, null, null)) > 0) {
                pending++;
            }
        }
        return pending;
    }

    /**
     * Checks a new retention against the store's archiving: a day must be archived before it is deleted.
     *
     * @throws BadRequestException when the retention is not longer than the policy's archiveAfterDays
     */
    public void checkRetention(Long storeId, int retentionDays) {
        BackupPolicy policy = backupMapper.selectPolicy(storeId);
        if (policy != null && policy.enabled() && retentionDays <= policy.archiveAfterDays()) {
            throw new BadRequestException("The store archives logs after " + policy.archiveAfterDays()
                    + " days, so its retention must be longer than that; change the archiving first");
        }
    }

    /**
     * Deletes the archives of a store from the bucket and the inventory (when the store itself is deleted).
     *
     * @return how many were deleted
     */
    public int deleteStoreArchives(Long storeId, String operatorUsername) {
        List<BackupArchive> archives = backupMapper.selectArchives(storeId, null, Integer.MAX_VALUE);
        if (archives.isEmpty()) {
            return 0;
        }
        S3Client client = client(requireStorage());
        int deleted = 0;
        for (BackupArchive archive : archives) {
            try {
                if (BackupArchive.SUCCESS.equals(archive.status())) {
                    client.delete(archive.objectKey());
                }
                backupMapper.deleteArchive(archive.id());
                deleted++;
            } catch (S3Client.S3Exception e) {
                log.warn("Cannot delete archive {}: {}", archive.objectKey(), e.getMessage());
            }
        }
        log.info("event=backup.store.archives.deleted operator={} storeId={} archives={}", operatorUsername,
                storeId, deleted);
        return deleted;
    }

    // ---------- download ----------

    /**
     * @return a token for downloading the archive without signing in, valid for 10 minutes
     */
    public String downloadToken(Long archiveId) {
        BackupArchive archive = requireArchive(archiveId);
        if (!BackupArchive.SUCCESS.equals(archive.status())) {
            throw new BadRequestException("Only successful archives can be downloaded");
        }
        return cipher.sign(archiveId + ":" + Instant.now().plus(DOWNLOAD_LINK).getEpochSecond());
    }

    /**
     * An archive's content.
     *
     * @param fileName e.g. {@code order-service-2026-10-20.ndjson.gz}
     */
    public record Download(String fileName, long size, InputStream content) {
    }

    /**
     * @throws NotFoundException when the token is invalid or expired
     */
    public Download download(String token) {
        String payload = cipher.verify(token);
        String[] parts = payload == null ? new String[0] : payload.split(":");
        if (parts.length != 2 || Instant.ofEpochSecond(Long.parseLong(parts[1])).isBefore(Instant.now())) {
            throw new NotFoundException("The download link is invalid or has expired");
        }
        BackupArchive archive = requireArchive(Long.parseLong(parts[0]));
        try {
            return new Download(archive.storeCode() + "-" + archive.day() + ".ndjson.gz", archive.sizeBytes(),
                    client(requireStorage()).get(archive.objectKey()));
        } catch (S3Client.S3Exception e) {
            throw new BadRequestException(e.getMessage());
        }
    }

    // ---------- helpers ----------

    private BackupArchive requireArchive(Long id) {
        BackupArchive archive = backupMapper.selectArchive(id);
        if (archive == null) {
            throw new NotFoundException("Archive was not found");
        }
        return archive;
    }

    /**
     * @return the day in the server's time zone, as {@code [start, end)}
     */
    TimeRange range(LocalDate day) {
        return new TimeRange(day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant());
    }

    static String key(String prefix, String name) {
        return prefix == null || prefix.isEmpty() ? name : prefix + "/" + name;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String truncate(String message) {
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }
}
