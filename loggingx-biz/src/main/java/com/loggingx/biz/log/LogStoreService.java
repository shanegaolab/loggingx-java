package com.loggingx.biz.log;

import com.loggingx.biz.support.Updates;
import com.loggingx.common.exception.BadRequestException;
import com.loggingx.common.exception.ConflictException;
import com.loggingx.common.exception.NotFoundException;
import com.loggingx.dao.log.LogRecordRepository;
import com.loggingx.dao.log.LogStore;
import com.loggingx.dao.log.LogStoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Log store administration. A store's code is fixed once created, since producers address the store by it. Each
 * store has a random ingest token for HTTP producers, which can be regenerated to revoke the old one. Deleting a
 * store also deletes its logs; should that fail, {@link LogRetentionService} removes them on its next run.
 */
@Service
public class LogStoreService {

    public static final int MIN_RETENTION_DAYS = 1;
    public static final int MAX_RETENTION_DAYS = 365;

    private static final Logger log = LoggerFactory.getLogger(LogStoreService.class);
    private static final String STORE_NOT_FOUND = "Log store was not found";
    private static final Pattern CODE = Pattern.compile("[a-z0-9][a-z0-9_-]{1,63}");
    private static final String TOKEN_PREFIX = "lx_";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final LogStoreRepository logStoreRepository;
    private final LogRecordRepository logRecordRepository;

    public LogStoreService(LogStoreRepository logStoreRepository, LogRecordRepository logRecordRepository) {
        this.logStoreRepository = logStoreRepository;
        this.logRecordRepository = logRecordRepository;
    }

    /**
     * @return every store with its recent activity, in creation order
     */
    public List<LogStoreView> list() {
        List<LogStore> stores = logStoreRepository.findAll();
        Instant dayAgo = Instant.now().minus(Duration.ofDays(1));
        try {
            return stores.stream()
                    .map(store -> new LogStoreView(store, logRecordRepository.lastLogTime(store.id()).orElse(null),
                            logRecordRepository.countSince(store.id(), dayAgo)))
                    .toList();
        } catch (RuntimeException e) {
            // The stores live in MySQL, so they stay manageable while the log storage is down.
            log.warn("Cannot read log store activity, the log storage is unavailable: {}", e.getMessage());
            return stores.stream().map(store -> new LogStoreView(store, null, null)).toList();
        }
    }

    /**
     * @return every store in creation order, for selection lists
     */
    public List<LogStore> all() {
        return logStoreRepository.findAll();
    }

    public LogStore require(Long id) {
        return logStoreRepository.findById(id).orElseThrow(() -> new NotFoundException(STORE_NOT_FOUND));
    }

    public LogStore create(LogStoreCommand command, String operatorUsername) {
        String code = command.code() == null ? "" : command.code().trim();
        if (!CODE.matcher(code).matches()) {
            throw new BadRequestException(
                    "code must be 2-64 lowercase letters, digits, '-' or '_', starting with a letter or digit");
        }
        requireRetention(command.retentionDays());
        Instant now = Instant.now();
        LogStore store = new LogStore(null, code, command.name().trim(), blankToNull(command.description()),
                command.retentionDays(), newToken(), command.enabled(), now, now);
        LogStore created = logStoreRepository.insert(store)
                .orElseThrow(() -> new ConflictException("Log store code '" + code + "' already exists"));
        log.info("event=logstore.create.success operator={} storeId={} code={}",
                operatorUsername, created.id(), created.code());
        return created;
    }

    public LogStore update(Long id, LogStoreCommand command, String operatorUsername) {
        LogStore existing = require(id);
        requireRetention(command.retentionDays());
        LogStore updated = new LogStore(id, existing.code(), command.name().trim(),
                blankToNull(command.description()), command.retentionDays(), existing.ingestToken(),
                command.enabled(), existing.createdAt(), Instant.now());
        Updates.require(logStoreRepository.update(existing, updated), STORE_NOT_FOUND,
                () -> new ConflictException("Ingest token collided; try again"));
        log.info("event=logstore.update.success operator={} storeId={} code={} enabled={} retentionDays={}",
                operatorUsername, id, updated.code(), updated.enabled(), updated.retentionDays());
        return updated;
    }

    /**
     * Replaces the ingest token; producers using the old one are rejected from now on.
     */
    public LogStore regenerateToken(Long id, String operatorUsername) {
        LogStore existing = require(id);
        LogStore updated = new LogStore(id, existing.code(), existing.name(), existing.description(),
                existing.retentionDays(), newToken(), existing.enabled(), existing.createdAt(), Instant.now());
        Updates.require(logStoreRepository.update(existing, updated), STORE_NOT_FOUND,
                () -> new ConflictException("Ingest token collided; try again"));
        log.info("event=logstore.token.regenerated operator={} storeId={} code={}",
                operatorUsername, id, existing.code());
        return updated;
    }

    public void delete(Long id, String operatorUsername) {
        LogStore store = require(id);
        logStoreRepository.deleteById(id);
        log.info("event=logstore.delete.success operator={} storeId={} code={}", operatorUsername, id, store.code());
        try {
            logRecordRepository.deleteByStore(id);
        } catch (RuntimeException e) {
            log.warn("Cannot delete the logs of deleted store {} yet; the retention job will retry", id, e);
        }
    }

    private static void requireRetention(int retentionDays) {
        if (retentionDays < MIN_RETENTION_DAYS || retentionDays > MAX_RETENTION_DAYS) {
            throw new BadRequestException("retentionDays must be between " + MIN_RETENTION_DAYS + " and "
                    + MAX_RETENTION_DAYS);
        }
    }

    /**
     * @return {@code lx_} followed by 32 random bytes in URL-safe Base64
     */
    private static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
