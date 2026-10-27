package com.loggingx.biz.log;

import com.loggingx.dao.log.LogRecordRepository;
import com.loggingx.dao.log.LogStore;
import com.loggingx.dao.log.LogStoreRepository;
import com.loggingx.dao.log.TimeRange;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Deletes logs older than their store's current retention period, and the logs of deleted stores. The log storage
 * also expires rows by the retention they were written with (ClickHouse TTL); this job applies later retention
 * changes and cleans up after store deletions that could not delete their logs at once.
 */
@Service
public class LogRetentionService {

    /**
     * Rows are written with a storage TTL this many days longer than the retention, so logs held back by a
     * {@link RetentionHold} (a day not archived yet) survive; this job deletes the others on time.
     */
    public static final int TTL_GRACE_DAYS = 30;

    private static final Logger log = LoggerFactory.getLogger(LogRetentionService.class);

    private final LogStoreRepository logStoreRepository;
    private final LogRecordRepository logRecordRepository;
    private final List<RetentionHold> holds;

    public LogRetentionService(LogStoreRepository logStoreRepository, LogRecordRepository logRecordRepository) {
        this(logStoreRepository, logRecordRepository, List.of());
    }

    @Autowired
    public LogRetentionService(LogStoreRepository logStoreRepository, LogRecordRepository logRecordRepository,
                               List<RetentionHold> holds) {
        this.logStoreRepository = logStoreRepository;
        this.logRecordRepository = logRecordRepository;
        this.holds = holds;
    }

    /**
     * Runs daily, by default at 03:30 server time.
     */
    @Scheduled(cron = "${loggingx.log.retention-cron:0 30 3 * * *}")
    public void purge() {
        Instant now = Instant.now();
        List<LogStore> stores = logStoreRepository.findAll();
        Set<Long> orphans = new HashSet<>(logRecordRepository.storeIds());
        for (LogStore store : stores) {
            orphans.remove(store.id());
            Instant cutoff = now.minus(Duration.ofDays(store.retentionDays()));
            List<TimeRange> keep = holds.stream().flatMap(hold -> hold.held(store.id(), now).stream()).toList();
            if (keep.isEmpty()) {
                logRecordRepository.deleteBefore(store.id(), cutoff);
            } else {
                logRecordRepository.deleteBefore(store.id(), cutoff, keep);
            }
        }
        orphans.forEach(logRecordRepository::deleteByStore);
        log.info("event=log.retention.purged stores={} deletedStores={}", stores.size(), orphans.size());
    }
}
