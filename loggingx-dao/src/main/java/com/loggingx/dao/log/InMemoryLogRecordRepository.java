package com.loggingx.dao.log;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Keeps each store's newest log lines in memory, for tests and for local runs without ClickHouse
 * ({@code loggingx.log.storage=memory}). Logs are lost on restart, and a store keeps at most
 * {@code loggingx.log.memory.capacity-per-store} lines, dropping the oldest received first.
 */
@Repository
@ConditionalOnProperty(name = "loggingx.log.storage", havingValue = "memory")
public class InMemoryLogRecordRepository implements LogRecordRepository {

    private static final Comparator<LogRecord> NEWEST_FIRST = LogRecord.ORDER.reversed();

    private final int capacityPerStore;
    private final Map<Long, NavigableMap<Long, LogRecord>> byStore = new TreeMap<>();

    public InMemoryLogRecordRepository(@Value("${loggingx.log.memory.capacity-per-store:50000}") int capacityPerStore) {
        this.capacityPerStore = capacityPerStore;
    }

    @Override
    public synchronized void insertAll(List<LogRecord> records, int retentionDays) {
        for (LogRecord record : records) {
            NavigableMap<Long, LogRecord> store = byStore.computeIfAbsent(record.storeId(), id -> new TreeMap<>());
            store.put(record.id(), record);
            while (store.size() > capacityPerStore) {
                store.pollFirstEntry();
            }
        }
    }

    @Override
    public synchronized List<LogRecord> search(LogSearch search, long offset, int limit) {
        return matching(search).sorted(NEWEST_FIRST).skip(offset).limit(limit).toList();
    }

    @Override
    public synchronized long count(LogSearch search) {
        return matching(search).count();
    }

    @Override
    public synchronized List<LevelBucket> levelHistogram(Instant from, Instant to, Duration bucket) {
        long size = bucket.toMillis();
        Map<List<Object>, Long> counts = new java.util.LinkedHashMap<>();
        byStore.forEach((storeId, records) -> records.values().stream()
                .filter(record -> !record.time().isBefore(from) && record.time().isBefore(to))
                .forEach(record -> counts.merge(List.of(record.time().toEpochMilli() / size, storeId, record.level()),
                        1L, Long::sum)));
        return counts.entrySet().stream()
                .map(entry -> new LevelBucket(Instant.ofEpochMilli((Long) entry.getKey().get(0) * size),
                        (Long) entry.getKey().get(1), (String) entry.getKey().get(2), entry.getValue()))
                .toList();
    }

    @Override
    public synchronized List<LevelCount> countByLevel(LogSearch search) {
        return matching(search)
                .collect(Collectors.groupingBy(LogRecord::level, TreeMap::new, Collectors.counting()))
                .entrySet().stream()
                .map(entry -> new LevelCount(entry.getKey(), entry.getValue()))
                .toList();
    }

    @Override
    public synchronized List<LogRecord> findAfter(Long storeId, long afterId, long upToId, int limit) {
        if (afterId >= upToId) {
            return List.of();
        }
        return records(storeId).subMap(afterId, false, upToId, true).values().stream().limit(limit).toList();
    }

    @Override
    public synchronized List<LogRecord> findLatest(Long storeId, int limit) {
        List<LogRecord> newestFirst = new ArrayList<>(
                records(storeId).values().stream().sorted(NEWEST_FIRST).limit(limit).toList());
        return newestFirst.reversed();
    }

    @Override
    public synchronized Optional<Instant> lastLogTime(Long storeId) {
        return records(storeId).values().stream().map(LogRecord::time).max(Comparator.naturalOrder());
    }

    @Override
    public synchronized long countSince(Long storeId, Instant since) {
        return records(storeId).values().stream().filter(record -> !record.time().isBefore(since)).count();
    }

    @Override
    public synchronized void deleteBefore(Long storeId, Instant before) {
        records(storeId).values().removeIf(record -> record.time().isBefore(before));
    }

    @Override
    public synchronized void deleteBefore(Long storeId, Instant before, List<TimeRange> keep) {
        records(storeId).values().removeIf(record -> record.time().isBefore(before)
                && keep.stream().noneMatch(range -> range.contains(record.time())));
    }

    @Override
    public synchronized void deleteBetween(Long storeId, TimeRange range) {
        records(storeId).values().removeIf(record -> range.contains(record.time()));
    }

    @Override
    public synchronized List<LogRecord> scan(Long storeId, Instant start, Instant end, LogRecord after, int limit) {
        return records(storeId).values().stream()
                .filter(record -> !record.time().isBefore(start) && record.time().isBefore(end))
                .filter(record -> after == null || LogRecord.ORDER.compare(record, after) > 0)
                .sorted(LogRecord.ORDER)
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized void deleteByStore(Long storeId) {
        byStore.remove(storeId);
    }

    @Override
    public synchronized Set<Long> storeIds() {
        return new HashSet<>(byStore.keySet());
    }

    private NavigableMap<Long, LogRecord> records(Long storeId) {
        return byStore.getOrDefault(storeId, new TreeMap<>());
    }

    private Stream<LogRecord> matching(LogSearch search) {
        String keyword = search.keyword() == null ? null : search.keyword().toLowerCase(Locale.ROOT);
        Predicate<LogRecord> matches = record ->
                (search.start() == null || !record.time().isBefore(search.start()))
                        && (search.end() == null || record.time().isBefore(search.end()))
                        && (search.levels().isEmpty() || search.levels().contains(record.level()))
                        && (search.host() == null || search.host().equals(record.host()))
                        && (search.traceId() == null || search.traceId().equals(record.traceId()))
                        && (keyword == null || record.message().toLowerCase(Locale.ROOT).contains(keyword));
        return records(search.storeId()).values().stream().filter(matches);
    }
}
