package com.loggingx.dao.log;

import java.time.Instant;
import java.util.List;

/**
 * Log filters; {@code null} or empty values match everything.
 *
 * @param start   inclusive lower bound of {@link LogRecord#time()}
 * @param end     exclusive upper bound of {@link LogRecord#time()}
 * @param levels  level names, see {@link LogLevel}
 * @param keyword matched as a substring of the message, ignoring case
 * @param host    matched exactly
 * @param traceId matched exactly
 */
public record LogSearch(
        Long storeId,
        Instant start,
        Instant end,
        List<String> levels,
        String keyword,
        String host,
        String traceId
) {

    public LogSearch {
        levels = levels == null ? List.of() : List.copyOf(levels);
    }
}
