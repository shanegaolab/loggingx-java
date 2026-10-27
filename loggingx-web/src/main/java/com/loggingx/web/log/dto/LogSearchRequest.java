package com.loggingx.web.log.dto;

import com.loggingx.biz.log.LogQuery;
import com.loggingx.common.exception.BadRequestException;
import com.loggingx.common.util.DateTimes;

import java.time.Instant;
import java.util.List;

/**
 * Query parameters of the log search.
 *
 * @param startTime inclusive, e.g. {@code 2026-10-23 16:00:00} in the server's time zone, or ISO-8601
 * @param endTime   exclusive, in the same formats
 * @param levels    comma-separated or repeated, e.g. {@code levels=ERROR,WARN}
 */
public record LogSearchRequest(
        Long storeId,
        String startTime,
        String endTime,
        List<String> levels,
        String keyword,
        String host,
        String traceId,
        Integer current,
        Integer size
) {

    public LogQuery toQuery() {
        return new LogQuery(storeId, time("startTime", startTime), time("endTime", endTime), levels, keyword, host,
                traceId, current, size);
    }

    private static Instant time(String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return DateTimes.parse(value).orElseThrow(() -> new BadRequestException(name + " is not a valid time"));
    }
}
