package com.loggingx.biz.log;

import java.time.Instant;
import java.util.List;

/**
 * Log search filters; blank values are ignored.
 *
 * @param start   inclusive lower bound of the log time
 * @param end     exclusive upper bound of the log time
 * @param levels  level names, e.g. {@code ERROR}
 * @param keyword matched as a substring of the message
 * @param host    matched exactly
 * @param traceId matched exactly
 */
public record LogQuery(
        Long storeId,
        Instant start,
        Instant end,
        List<String> levels,
        String keyword,
        String host,
        String traceId,
        Integer current,
        Integer size
) {
}
