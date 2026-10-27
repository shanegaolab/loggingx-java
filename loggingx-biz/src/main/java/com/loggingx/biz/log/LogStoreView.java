package com.loggingx.biz.log;

import com.loggingx.dao.log.LogStore;

import java.time.Instant;

/**
 * @param lastLogTime time of the store's newest log, or {@code null} when it has none or the log storage is
 *                    unavailable
 * @param recentCount number of logs from the last 24 hours, or {@code null} when the log storage is unavailable
 */
public record LogStoreView(LogStore store, Instant lastLogTime, Long recentCount) {
}
