package com.loggingx.biz.log;

import com.loggingx.common.exception.TooManyRequestsException;
import com.loggingx.dao.log.LogIds;
import com.loggingx.dao.log.LogRecord;
import com.loggingx.dao.log.LogRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Follows stores for the live view. Each subscriber first receives the store's latest lines, then every new line
 * that matches its filter.
 * <p>
 * New lines are found by polling the log storage for ids above each subscriber's cursor, once per store and poll, so
 * it works however many application instances or Kafka consumers write the logs. Ids are generated from the time of
 * receipt (see {@link LogIds}), and a poll only reads ids older than the settle delay, giving inserts in flight time
 * to land; a line whose insert takes longer than that can be missed by the live view, but searches still find it.
 */
@Service
public class LogTailService {

    /**
     * Latest lines a new subscriber receives.
     */
    public static final int BACKLOG = 100;
    static final int MAX_SUBSCRIPTIONS = 200;
    /**
     * Lines read per store and poll; a busier store catches up over the following polls.
     */
    static final int BATCH = 500;
    static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    private static final Logger log = LoggerFactory.getLogger(LogTailService.class);

    private final LogStoreService logStoreService;
    private final LogRecordRepository logRecordRepository;
    private final Duration settleDelay;
    private final Map<Long, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong();

    public LogTailService(LogStoreService logStoreService, LogRecordRepository logRecordRepository,
                          @Value("${loggingx.log.tail.settle-delay:2s}") Duration settleDelay) {
        this.logStoreService = logStoreService;
        this.logRecordRepository = logRecordRepository;
        this.settleDelay = settleDelay;
    }

    /**
     * Receives lines for one subscriber. An empty list is a heartbeat, sent when nothing matched for a while so the
     * connection stays open through proxies. Throwing ends the subscription.
     */
    @FunctionalInterface
    public interface Sink {
        void send(List<LogRecord> records) throws Exception;
    }

    /**
     * @param levels  level names to keep; empty keeps every level
     * @param keyword substring the message must contain, ignoring case; blank keeps every message
     */
    public record TailFilter(Set<String> levels, String keyword) {

        public TailFilter {
            levels = levels == null ? Set.of() : levels.stream()
                    .filter(level -> level != null && !level.isBlank())
                    .map(level -> level.trim().toUpperCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
            keyword = keyword == null || keyword.isBlank() ? null : keyword.trim().toLowerCase(Locale.ROOT);
        }

        boolean matches(LogRecord record) {
            return (levels.isEmpty() || levels.contains(record.level()))
                    && (keyword == null || record.message().toLowerCase(Locale.ROOT).contains(keyword));
        }
    }

    /**
     * Sends the store's latest matching lines to {@code sink}, then registers it for new ones.
     *
     * @return the subscription id, for {@link #unsubscribe}
     * @throws TooManyRequestsException when too many live views are open
     */
    public long subscribe(Long storeId, TailFilter filter, Sink sink) throws Exception {
        logStoreService.require(storeId);
        if (subscriptions.size() >= MAX_SUBSCRIPTIONS) {
            throw new TooManyRequestsException("Too many live log views are open; try again later");
        }
        // Lines received after the cursor come from polls, so the backlog leaves them out to avoid sending them twice.
        long cursor = settledUpTo();
        List<LogRecord> latest = logRecordRepository.findLatest(storeId, BACKLOG);
        sink.send(latest.stream().filter(record -> record.id() <= cursor && filter.matches(record)).toList());
        long id = nextId.incrementAndGet();
        subscriptions.put(id, new Subscription(storeId, filter, sink, cursor));
        return id;
    }

    public void unsubscribe(long subscriptionId) {
        subscriptions.remove(subscriptionId);
    }

    int subscriptionCount() {
        return subscriptions.size();
    }

    @Scheduled(fixedDelayString = "${loggingx.log.tail.poll-interval:1000}")
    public void poll() {
        Map<Long, List<Map.Entry<Long, Subscription>>> byStore = subscriptions.entrySet().stream()
                .collect(Collectors.groupingBy(entry -> entry.getValue().storeId));
        Instant now = Instant.now();
        long upTo = settledUpTo();
        byStore.forEach((storeId, subscribers) -> {
            long from = subscribers.stream().mapToLong(entry -> entry.getValue().cursor).min().orElse(upTo);
            List<LogRecord> records;
            try {
                records = logRecordRepository.findAfter(storeId, from, upTo, BATCH);
            } catch (RuntimeException e) {
                log.warn("Cannot read new logs of store {} for the live view", storeId, e);
                return;
            }
            // A full batch may leave lines up to upTo unread; otherwise everything up to upTo has been seen.
            long reached = records.size() < BATCH ? upTo : records.getLast().id();
            subscribers.forEach(entry -> deliver(entry.getKey(), entry.getValue(), records, reached, now));
        });
    }

    /**
     * Up to the last millisecond that has fully passed before the settle delay: ids can still be generated within the
     * current millisecond, and those must stay above the cursor so a later poll delivers them.
     */
    private long settledUpTo() {
        return LogIds.upperBound(Instant.now().minus(settleDelay).minusMillis(1));
    }

    private void deliver(Long subscriptionId, Subscription subscription, List<LogRecord> records, long reached,
                         Instant now) {
        // Lines arrive in the order they were received; lines received together, e.g. from several Kafka partitions,
        // are shown in log order. A line received later than newer ones still comes after them.
        List<LogRecord> matching = records.stream()
                .filter(record -> record.id() > subscription.cursor && subscription.filter.matches(record))
                .sorted(LogRecord.ORDER)
                .toList();
        subscription.cursor = Math.max(subscription.cursor, reached);
        boolean heartbeatDue = Duration.between(subscription.lastSent, now).compareTo(HEARTBEAT_INTERVAL) >= 0;
        if (matching.isEmpty() && !heartbeatDue) {
            return;
        }
        try {
            subscription.sink.send(matching);
            subscription.lastSent = now;
        } catch (Exception e) {
            // Usually the client went away; the transport reports it as a failed write.
            subscriptions.remove(subscriptionId);
            log.debug("Live log view {} ended: {}", subscriptionId, e.toString());
        }
    }

    private static final class Subscription {

        final Long storeId;
        final TailFilter filter;
        final Sink sink;
        // Only the polling thread touches these after registration.
        long cursor;
        Instant lastSent = Instant.now();

        Subscription(Long storeId, TailFilter filter, Sink sink, long cursor) {
            this.storeId = storeId;
            this.filter = filter;
            this.sink = sink;
            this.cursor = cursor;
        }
    }
}
