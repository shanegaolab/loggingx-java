package com.loggingx.dao.log;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Log lines, written in batches and read by search or by following a store's newest lines. Implemented by
 * {@link ClickHouseLogRecordRepository}, or {@link InMemoryLogRecordRepository} for tests and local runs, chosen by
 * {@code loggingx.log.storage}.
 */
public interface LogRecordRepository {

    /**
     * Stores the records with the ids they carry, see {@link LogIds}.
     *
     * @param retentionDays the store's retention, after which the storage may drop the records by itself
     */
    void insertAll(List<LogRecord> records, int retentionDays);

    /**
     * @return the matching records, newest first
     */
    List<LogRecord> search(LogSearch search, long offset, int limit);

    long count(LogSearch search);

    List<LevelCount> countByLevel(LogSearch search);

    /**
     * Counts the logs of all stores logged in {@code [from, to)} per time bucket, store and level, for overviews.
     * Buckets start at multiples of {@code bucket} since the epoch.
     */
    List<LevelBucket> levelHistogram(Instant from, Instant to, Duration bucket);

    /**
     * @return up to {@code limit} records of the store with an id above {@code afterId} and at most {@code upToId},
     * in id order
     */
    List<LogRecord> findAfter(Long storeId, long afterId, long upToId, int limit);

    /**
     * @return the store's {@code limit} newest records by log time, oldest first
     */
    List<LogRecord> findLatest(Long storeId, int limit);

    Optional<Instant> lastLogTime(Long storeId);

    long countSince(Long storeId, Instant since);

    /**
     * Deletes the store's records logged before {@code before}. The storage may finish the deletion in the background.
     */
    void deleteBefore(Long storeId, Instant before);

    /**
     * Like {@link #deleteBefore(Long, Instant)}, but keeps the records logged within {@code keep} (restored backups).
     */
    void deleteBefore(Long storeId, Instant before, List<TimeRange> keep);

    /**
     * Deletes the store's records logged within {@code range}.
     */
    void deleteBetween(Long storeId, TimeRange range);

    /**
     * Reads the store's records logged in {@code [start, end)} page by page, oldest first ({@link LogRecord#ORDER}).
     *
     * @param after the last record of the previous page, or {@code null} for the first page
     */
    List<LogRecord> scan(Long storeId, Instant start, Instant end, LogRecord after, int limit);

    /**
     * Deletes all of the store's records. The storage may finish the deletion in the background.
     */
    void deleteByStore(Long storeId);

    /**
     * @return the ids of the stores that have records
     */
    Set<Long> storeIds();
}
