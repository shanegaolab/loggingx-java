package com.loggingx.dao.log;

import java.time.Instant;

/**
 * A half-open time range {@code [start, end)}.
 */
public record TimeRange(Instant start, Instant end) {

    public boolean contains(Instant time) {
        return !time.isBefore(start) && time.isBefore(end);
    }
}
