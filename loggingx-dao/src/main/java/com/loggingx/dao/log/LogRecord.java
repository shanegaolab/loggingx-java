package com.loggingx.dao.log;

import com.loggingx.dao.support.Timestamps;

import java.time.Instant;
import java.util.Comparator;

/**
 * One log line.
 * <p>
 * Lines are ordered by {@link #ORDER}: by time, then by the producer's sequence number, then by id. Producers log
 * many lines within the same timestamp (all of a stack trace, or every line of a second when timestamps have second
 * precision), and their transport can reorder them, e.g. across Kafka partitions; the sequence number keeps such lines
 * in their original order.
 *
 * @param id         assigned on insert and increasing in insertion order, so the live view can follow a store by id
 * @param time       when the producer logged the line
 * @param seq        the producer's position of the line within its source, e.g. the byte offset in the log file;
 *                   0 when not given
 * @param level      one of {@link LogLevel}
 * @param host       machine or container that produced the line, if known
 * @param source     file, logger or component within the host, if known
 * @param fields     extra structured attributes as a JSON object, or {@code null}
 * @param receivedAt when LoggingX stored the line
 */
public record LogRecord(
        Long id,
        Long storeId,
        Instant time,
        long seq,
        String level,
        String host,
        String source,
        String traceId,
        String message,
        String fields,
        Instant receivedAt
) {

    /**
     * Oldest first: by time, then sequence number, then id (insertion order).
     */
    public static final Comparator<LogRecord> ORDER = Comparator.comparing(LogRecord::time)
            .thenComparingLong(LogRecord::seq)
            .thenComparing(LogRecord::id);

    public LogRecord {
        time = Timestamps.storable(time);
        receivedAt = Timestamps.storable(receivedAt);
    }

    /**
     * Creates a line without a sequence number.
     */
    public LogRecord(Long id, Long storeId, Instant time, String level, String host, String source, String traceId,
                     String message, String fields, Instant receivedAt) {
        this(id, storeId, time, 0, level, host, source, traceId, message, fields, receivedAt);
    }
}
