package com.loggingx.dao.log;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the ClickHouse repository against a real server, which the build does not provide. Set
 * {@code LOGGINGX_TEST_CLICKHOUSE_URL} (e.g. {@code jdbc:clickhouse://localhost:8123/loggingx}), and optionally
 * {@code LOGGINGX_TEST_CLICKHOUSE_USERNAME} / {@code LOGGINGX_TEST_CLICKHOUSE_PASSWORD}, to run it. It writes to a
 * random store id and deletes it afterwards.
 */
@EnabledIfEnvironmentVariable(named = "LOGGINGX_TEST_CLICKHOUSE_URL", matches = ".+")
class ClickHouseLogRecordRepositoryLiveTest {

    private final ClickHouseLogRecordRepository records = new ClickHouseLogRecordRepository(
            System.getenv("LOGGINGX_TEST_CLICKHOUSE_URL"),
            System.getenv().getOrDefault("LOGGINGX_TEST_CLICKHOUSE_USERNAME", "default"),
            System.getenv().getOrDefault("LOGGINGX_TEST_CLICKHOUSE_PASSWORD", ""),
            2);
    private final Long store = ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE / 2);
    private final Instant t0 = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(60);

    @AfterEach
    void cleanUp() {
        records.deleteByStore(store);
        records.destroy();
    }

    private LogRecord record(int secondsAfter, String level, String message, String traceId) {
        return new LogRecord(LogIds.next(), store, t0.plusSeconds(secondsAfter).plusNanos(123_456_000), level,
                "web-1", "app.log", traceId, message, traceId == null ? null : "{\"k\":1}", t0);
    }

    @Test
    void roundTripsSearchesFollowsAndDeletes() {
        List<LogRecord> written = List.of(record(1, "INFO", "user signed in", null),
                record(2, "ERROR", "Payment FAILED: 100% of retries", "trace-1"),
                record(3, "WARN", "slow_query took 2s", null));
        records.insertAll(written, 7);

        LogSearch all = new LogSearch(store, null, null, null, null, null, null);
        List<LogRecord> found = records.search(all, 0, 10);
        assertEquals(written.reversed(), found);
        assertEquals(3, records.count(all));

        LogSearch filtered = new LogSearch(store, t0.plusSeconds(2), t0.plusSeconds(3), List.of("ERROR", "WARN"),
                "failed: 100%", "web-1", "trace-1");
        assertEquals(List.of(written.get(1)), records.search(filtered, 0, 10));
        assertEquals(List.of(written.get(2)),
                records.search(new LogSearch(store, null, null, null, "slow_q", null, null), 0, 10));
        Map<String, Long> levels = records.countByLevel(all).stream()
                .collect(Collectors.toMap(LevelCount::level, LevelCount::count));
        assertEquals(Map.of("INFO", 1L, "ERROR", 1L, "WARN", 1L), levels);

        assertEquals(written, records.findLatest(store, 10));
        assertEquals(written.subList(1, 3), records.findAfter(store, written.get(0).id(), Long.MAX_VALUE, 10));
        assertEquals(written.get(2).time(), records.lastLogTime(store).orElseThrow());
        assertEquals(2, records.countSince(store, t0.plusSeconds(2)));
        assertTrue(records.storeIds().contains(store));

        records.deleteBefore(store, t0.plusSeconds(3));
        assertEquals(List.of(written.get(2)), records.search(all, 0, 10));
    }

    @Test
    void linesWithTheSameTimeAreOrderedBySequenceThenId() {
        Instant same = t0.plusSeconds(5);
        LogRecord third = new LogRecord(LogIds.next(), store, same, 30, "INFO", "web-1", null, null, "third", null, t0);
        LogRecord first = new LogRecord(LogIds.next(), store, same, 10, "INFO", "web-1", null, null, "first", null, t0);
        LogRecord second = new LogRecord(LogIds.next(), store, same, 20, "INFO", "web-1", null, null, "second", null,
                t0);
        records.insertAll(List.of(third, first, second), 7);

        assertEquals(List.of(third, second, first),
                records.search(new LogSearch(store, null, null, null, null, null, null), 0, 10));
        assertEquals(List.of(first, second, third), records.findLatest(store, 10));
    }
}
