package com.loggingx.biz.log;

import com.loggingx.common.exception.BadRequestException;
import com.loggingx.common.exception.ConflictException;
import com.loggingx.common.exception.ForbiddenException;
import com.loggingx.common.exception.NotFoundException;
import com.loggingx.common.exception.UnauthorizedException;
import com.loggingx.dao.log.LogIds;
import com.loggingx.dao.log.LogRecord;
import com.loggingx.dao.log.LogStore;
import com.loggingx.dao.support.TestDatabase;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogServicesTest {

    private final TestDatabase database = new TestDatabase();
    private final LogStoreService stores = new LogStoreService(database.logStoreRepository,
            database.logRecordRepository);
    private final LogIngestService ingest = new LogIngestService(database.logStoreRepository,
            database.logRecordRepository);
    private final LogQueryService query = new LogQueryService(stores, database.logRecordRepository);
    private final LogTailService tail = new LogTailService(stores, database.logRecordRepository, Duration.ZERO);
    private final LogRetentionService retention = new LogRetentionService(database.logStoreRepository,
            database.logRecordRepository);

    private LogStore createStore(String code) {
        return stores.create(new LogStoreCommand(code, "App " + code, null, 7, true), "super");
    }

    private static LogEntry entry(String time, String level, String message) {
        return new LogEntry(time, level, "web-1", null, null, message, null);
    }

    private List<LogRecord> all(LogStore store) {
        return query.search(new LogQuery(store.id(), null, null, null, null, null, null, 1, 100)).page().records();
    }

    @Test
    void storeCodesAreValidatedUniqueAndFixed() {
        LogStore store = createStore("order-service");

        assertTrue(store.ingestToken().startsWith("lx_"));
        assertThrows(ConflictException.class, () -> createStore("order-service"));
        assertThrows(BadRequestException.class, () -> createStore("Order Service"));
        assertThrows(BadRequestException.class,
                () -> stores.create(new LogStoreCommand("ok-code", "x", null, 0, true), "super"));

        LogStore updated = stores.update(store.id(), new LogStoreCommand("renamed", "Orders", " ", 30, false), "super");
        assertEquals("order-service", updated.code());
        assertEquals(30, updated.retentionDays());
        assertNull(updated.description());

        LogStore regenerated = stores.regenerateToken(store.id(), "super");
        assertNotEquals(store.ingestToken(), regenerated.ingestToken());
        assertTrue(database.logStoreRepository.findByIngestToken(store.ingestToken()).isEmpty());
    }

    @Test
    void ingestNormalizesEntries() {
        LogStore store = createStore("app");
        String longMessage = "x".repeat(LogIngestService.MAX_MESSAGE_LENGTH + 10);

        IngestResult result = ingest.ingestWithToken(store.ingestToken(), List.of(
                new LogEntry("2026-10-23T08:00:00Z", "warning", " web-1 ", "app.log", "t-1", "disk almost full\n",
                        Map.of("disk", "/data", "used", 93)),
                entry("1761206401", "err", "epoch seconds"),
                entry("1761206402500", null, "epoch millis"),
                entry("not a time", "bogus", longMessage),
                entry(null, "INFO", "  ")));

        assertEquals(new IngestResult(4, 1), result);
        List<LogRecord> records = all(store);
        LogRecord full = records.stream().filter(r -> "t-1".equals(r.traceId())).findFirst().orElseThrow();
        assertEquals(Instant.parse("2026-10-23T08:00:00Z"), full.time());
        assertEquals("WARN", full.level());
        assertEquals("web-1", full.host());
        assertEquals("disk almost full", full.message());
        assertTrue(full.fields().contains("\"used\":93"), full.fields());

        LogRecord seconds = records.stream().filter(r -> r.message().equals("epoch seconds")).findFirst().orElseThrow();
        assertEquals(Instant.ofEpochSecond(1761206401), seconds.time());
        assertEquals("ERROR", seconds.level());
        LogRecord millis = records.stream().filter(r -> r.message().equals("epoch millis")).findFirst().orElseThrow();
        assertEquals(Instant.ofEpochMilli(1761206402500L), millis.time());
        assertEquals("INFO", millis.level());
        LogRecord truncated = records.stream().filter(r -> r.message().startsWith("xxx")).findFirst().orElseThrow();
        assertEquals(LogIngestService.MAX_MESSAGE_LENGTH, truncated.message().length());
        assertEquals("INFO", truncated.level());
        assertTrue(Duration.between(truncated.time(), Instant.now()).abs().toMinutes() < 1);
    }

    @Test
    void ingestChecksTokenStoreAndBatchSize() {
        LogStore store = createStore("app");

        assertThrows(UnauthorizedException.class, () -> ingest.ingestWithToken(null, List.of()));
        assertThrows(UnauthorizedException.class, () -> ingest.ingestWithToken("lx_wrong", List.of()));
        assertThrows(BadRequestException.class, () -> ingest.ingestWithToken(store.ingestToken(),
                Collections.nCopies(LogIngestService.MAX_BATCH + 1, entry(null, null, "m"))));
        assertThrows(NotFoundException.class, () -> ingest.ingestToStore("missing", List.of(entry(null, null, "m"))));
        assertEquals(1, ingest.ingestToStore("app", List.of(entry(null, null, "m"))).accepted());

        stores.update(store.id(), new LogStoreCommand(null, "App", null, 7, false), "super");
        assertThrows(ForbiddenException.class, () -> ingest.ingestToStore("app", List.of(entry(null, null, "m"))));
    }

    @Test
    void searchPagesFiltersAndCountsLevels() {
        LogStore store = createStore("app");
        ingest.ingestToStore("app", List.of(
                entry("2026-10-23T08:00:01Z", "INFO", "started"),
                entry("2026-10-23T08:00:02Z", "ERROR", "payment failed"),
                entry("2026-10-23T08:00:03Z", "ERROR", "db failed"),
                entry("2026-10-23T08:00:04Z", "WARN", "slow")));

        LogSearchResult errors = query.search(new LogQuery(store.id(), Instant.parse("2026-10-23T08:00:00Z"),
                Instant.parse("2026-10-23T09:00:00Z"), List.of("error"), "failed", null, null, 1, 1));
        assertEquals(List.of("db failed"), errors.page().records().stream().map(LogRecord::message).toList());
        assertEquals(2, errors.page().total());
        // Level counts ignore the level filter, so they show what the other levels would add.
        assertEquals(2L, errors.levelCounts().get("ERROR"));
        assertEquals(0L, errors.levelCounts().get("WARN"));

        assertThrows(BadRequestException.class, () -> query.search(new LogQuery(null, null, null, null, null, null,
                null, 1, 10)));
        assertThrows(NotFoundException.class, () -> query.search(new LogQuery(999L, null, null, null, null, null,
                null, 1, 10)));
        assertThrows(BadRequestException.class, () -> query.search(new LogQuery(store.id(),
                Instant.parse("2026-10-23T09:00:00Z"), Instant.parse("2026-10-23T08:00:00Z"), null, null, null, null,
                1, 10)));
        assertThrows(BadRequestException.class, () -> query.search(new LogQuery(store.id(), null, null, null, null,
                null, null, 101, 100)));
    }

    @Test
    void tailSendsBacklogThenNewMatchingLines() throws Exception {
        LogStore store = createStore("app");
        ingest.ingestToStore("app", List.of(entry(null, "INFO", "old info"), entry(null, "ERROR", "old error")));
        Thread.sleep(5);
        List<List<String>> received = new ArrayList<>();

        long id = tail.subscribe(store.id(), new LogTailService.TailFilter(Set.of("error"), null),
                records -> received.add(records.stream().map(LogRecord::message).toList()));
        assertEquals(List.of(List.of("old error")), received);

        ingest.ingestToStore("app", List.of(entry(null, "INFO", "new info"), entry(null, "ERROR", "new error")));
        // Lines received in the current millisecond are delivered once it has passed.
        Thread.sleep(5);
        tail.poll();
        assertEquals(List.of(List.of("old error"), List.of("new error")), received);

        tail.unsubscribe(id);
        assertEquals(0, tail.subscriptionCount());
    }

    @Test
    void tailDeliversLinesReceivedTogetherInLogOrder() throws Exception {
        LogStore store = createStore("app");
        List<String> received = new ArrayList<>();
        tail.subscribe(store.id(), new LogTailService.TailFilter(null, null),
                records -> records.forEach(record -> received.add(record.message())));

        // Lines of one second from one file, arriving out of order as from several Kafka partitions.
        String second = "2026-10-23T08:00:00Z";
        ingest.ingestToStore("app", List.of(new LogEntry(second, null, "web-1", "app.log", null, "line 3", null, 300L),
                new LogEntry(second, null, "web-1", "app.log", null, "line 1", null, 100L),
                new LogEntry("2026-10-23T07:59:59Z", null, "web-1", "app.log", null, "line 0", null, 50L),
                new LogEntry(second, null, "web-1", "app.log", null, "line 2", null, 200L)));
        Thread.sleep(5);
        tail.poll();

        assertEquals(List.of("line 0", "line 1", "line 2", "line 3"), received);
        List<LogRecord> stored = all(store);
        assertEquals(List.of("line 3", "line 2", "line 1", "line 0"),
                stored.stream().map(LogRecord::message).toList());
        assertEquals(300L, stored.getFirst().seq());
    }

    @Test
    void negativeSequenceNumbersAreIgnored() {
        LogStore store = createStore("app");
        ingest.ingestToStore("app", List.of(new LogEntry(null, null, null, null, null, "m", null, -5L)));

        assertEquals(0, all(store).getFirst().seq());
    }

    @Test
    void tailDropsSubscribersWhoseSinkFails() throws Exception {
        LogStore store = createStore("app");
        tail.subscribe(store.id(), new LogTailService.TailFilter(null, "BOOM"), records -> {
            if (!records.isEmpty()) {
                throw new IllegalStateException("client went away");
            }
        });

        ingest.ingestToStore("app", List.of(entry(null, null, "boom happened")));
        Thread.sleep(5);
        tail.poll();

        assertEquals(0, tail.subscriptionCount());
    }

    @Test
    void deletingAStoreDeletesItsLogs() {
        LogStore store = createStore("gone");
        ingest.ingestToStore("gone", List.of(entry(null, null, "m")));

        stores.delete(store.id(), "super");

        assertFalse(database.logRecordRepository.storeIds().contains(store.id()));
    }

    @Test
    void retentionPurgesExpiredLogsAndLogsOfDeletedStores() {
        LogStore kept = createStore("kept");
        String old = Instant.now().minus(Duration.ofDays(8)).toString();
        ingest.ingestToStore("kept", List.of(entry(old, null, "expired"), entry(null, null, "fresh")));
        // Left behind by a store deletion whose log deletion failed.
        Instant now = Instant.now();
        database.logRecordRepository.insertAll(List.of(new LogRecord(LogIds.next(), 999L, now, "INFO", null, null,
                null, "orphan", null, now)), 7);

        retention.purge();

        assertEquals(List.of("fresh"), all(kept).stream().map(LogRecord::message).toList());
        assertEquals(Set.of(kept.id()), database.logRecordRepository.storeIds());
    }

    @Test
    void storeListReportsRecentActivity() {
        LogStore store = createStore("app");
        createStore("idle");
        ingest.ingestToStore("app", List.of(entry(null, null, "a"), entry(null, null, "b")));

        List<LogStoreView> views = stores.list();
        assertEquals(2L, views.get(0).recentCount());
        assertTrue(views.get(0).lastLogTime() != null);
        assertEquals(store.id(), views.get(0).store().id());
        assertEquals(0L, views.get(1).recentCount());
        assertNull(views.get(1).lastLogTime());
    }
}
