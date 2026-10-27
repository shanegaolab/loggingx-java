package com.loggingx.dao.log;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogStorageTest {

    private static final Instant T0 = Instant.parse("2026-10-23T08:00:00Z");

    private final InMemoryLogRecordRepository records = new InMemoryLogRecordRepository(3);

    private static LogRecord record(long id, Long storeId, int secondsAfter, String level, String message) {
        return new LogRecord(id, storeId, T0.plusSeconds(secondsAfter), level, "web-1", null, null, message, null,
                T0);
    }

    private static List<String> messages(List<LogRecord> found) {
        return found.stream().map(LogRecord::message).toList();
    }

    @Test
    void idsIncreaseAndAreBoundedByTheirTime() {
        Set<Long> ids = new HashSet<>();
        long previous = 0;
        Instant before = Instant.now();
        for (int i = 0; i < 10_000; i++) {
            long id = LogIds.next();
            assertTrue(id > previous);
            ids.add(id);
            previous = id;
        }
        assertEquals(10_000, ids.size());
        assertTrue(previous <= LogIds.upperBound(Instant.now().plusMillis(10)));
        assertTrue(LogIds.upperBound(before.minusMillis(1)) < previous);
    }

    @Test
    void memorySearchFiltersNewestFirstAndCountsLevels() {
        records.insertAll(List.of(record(1, 1L, 1, "INFO", "Started"), record(2, 1L, 3, "ERROR", "Payment failed"),
                record(3, 1L, 2, "ERROR", "DB failed"), record(4, 2L, 4, "ERROR", "other store")), 7);

        LogSearch errors = new LogSearch(1L, T0, T0.plusSeconds(10), List.of("ERROR"), "FAILED", null, null);
        assertEquals(List.of("Payment failed", "DB failed"), messages(records.search(errors, 0, 10)));
        assertEquals(List.of("DB failed"), messages(records.search(errors, 1, 10)));
        assertEquals(2, records.count(errors));
        Map<String, Long> levels = records.countByLevel(new LogSearch(1L, null, null, null, null, null, null))
                .stream().collect(Collectors.toMap(LevelCount::level, LevelCount::count));
        assertEquals(Map.of("INFO", 1L, "ERROR", 2L), levels);
    }

    @Test
    void memoryFollowsByIdKeepsTheNewestAndDeletes() {
        records.insertAll(List.of(record(1, 1L, 1, "INFO", "a"), record(2, 1L, 2, "INFO", "b"),
                record(3, 1L, 3, "INFO", "c"), record(4, 1L, 4, "INFO", "d")), 7);

        assertEquals(List.of("b", "c", "d"), messages(records.findLatest(1L, 10)));
        assertEquals(List.of("c"), messages(records.findAfter(1L, 2, 3, 10)));
        assertEquals(List.of("c"), messages(records.findAfter(1L, 2, 4, 1)));
        assertEquals(T0.plusSeconds(4), records.lastLogTime(1L).orElseThrow());
        assertEquals(2, records.countSince(1L, T0.plusSeconds(3)));

        records.deleteBefore(1L, T0.plusSeconds(4));
        assertEquals(List.of("d"), messages(records.findLatest(1L, 10)));
        assertEquals(Set.of(1L), records.storeIds());
        records.deleteByStore(1L);
        assertTrue(records.storeIds().isEmpty());
    }

    @Test
    void linesWithTheSameTimeAreOrderedBySequenceThenId() {
        Instant same = T0.plusSeconds(1);
        // Received out of order (as from several Kafka partitions); seq is their order in the source file.
        records.insertAll(List.of(
                new LogRecord(1L, 1L, same, 30, "INFO", "web-1", null, null, "third", null, T0),
                new LogRecord(2L, 1L, same, 10, "INFO", "web-1", null, null, "first", null, T0),
                new LogRecord(3L, 1L, same, 20, "INFO", "web-1", null, null, "second", null, T0)), 7);

        assertEquals(List.of("third", "second", "first"),
                messages(records.search(new LogSearch(1L, null, null, null, null, null, null), 0, 10)));
        assertEquals(List.of("first", "second", "third"), messages(records.findLatest(1L, 10)));
    }

    @Test
    void withoutSequenceTheInsertionOrderDecides() {
        Instant same = T0.plusSeconds(1);
        records.insertAll(List.of(record(LogIds.next(), 1L, 1, "INFO", "a"), record(LogIds.next(), 1L, 1, "INFO", "b"),
                record(LogIds.next(), 1L, 1, "INFO", "c")), 7);

        assertEquals(List.of("a", "b", "c"), messages(records.findLatest(1L, 10)));
        assertEquals(same, records.findLatest(1L, 1).getFirst().time());
    }

    @Test
    void clickHouseKeywordPatternEscapesWildcards() {
        assertEquals("%100\\% done%", ClickHouseLogRecordRepository.likePattern("100% done"));
        assertEquals("%user\\_id%", ClickHouseLogRecordRepository.likePattern("user_id"));
        assertEquals("%a\\\\b%", ClickHouseLogRecordRepository.likePattern("a\\b"));
    }
}
