package com.loggingx.dao.log;

import java.time.Instant;

/**
 * The number of a store's logs of one level within a time bucket.
 *
 * @param time start of the bucket
 */
public record LevelBucket(Instant time, Long storeId, String level, long count) {
}
