package com.loggingx.biz.log;

import com.loggingx.dao.log.TimeRange;

import java.time.Instant;
import java.util.List;

/**
 * Keeps logs from the retention cleanup, e.g. logs restored from a backup, which are older than their store's
 * retention but must stay queryable for a while.
 */
public interface RetentionHold {

    /**
     * @return the time ranges of the store's logs to keep at {@code now}
     */
    List<TimeRange> held(Long storeId, Instant now);
}
