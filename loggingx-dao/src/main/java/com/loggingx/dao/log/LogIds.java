package com.loggingx.dao.log;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * Generates log ids that increase with the time they are generated: milliseconds since 2025 in the high 41 bits, a
 * random per-process node number in the next 10, and a per-millisecond sequence in the low 12. Ids from different
 * processes are unique (barring a node number collision) and ordered to the millisecond.
 */
public final class LogIds {

    private static final long EPOCH_MILLIS = Instant.parse("2025-01-01T00:00:00Z").toEpochMilli();
    private static final int NODE_BITS = 10;
    private static final int SEQUENCE_BITS = 12;
    private static final int TIME_SHIFT = NODE_BITS + SEQUENCE_BITS;
    private static final long SEQUENCE_MASK = (1L << SEQUENCE_BITS) - 1;
    private static final long NODE = new SecureRandom().nextInt(1 << NODE_BITS);

    private static long lastMillis = -1;
    private static long sequence;

    private LogIds() {
    }

    public static synchronized long next() {
        // A clock moving backwards keeps using the last millisecond, so ids never decrease.
        long millis = Math.max(System.currentTimeMillis() - EPOCH_MILLIS, lastMillis);
        if (millis == lastMillis) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                millis++;
            }
        } else {
            sequence = 0;
        }
        lastMillis = millis;
        return (millis << TIME_SHIFT) | (NODE << SEQUENCE_BITS) | sequence;
    }

    /**
     * @return the largest id that can be generated at or before {@code time}
     */
    public static long upperBound(Instant time) {
        long millis = Math.max(0, time.toEpochMilli() - EPOCH_MILLIS);
        return (millis << TIME_SHIFT) | ((1L << TIME_SHIFT) - 1);
    }
}
