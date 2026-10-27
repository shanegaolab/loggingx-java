package com.loggingx.dao.monitor;

import java.time.Instant;

/**
 * The fullest file system of a host over a time bucket.
 *
 * @param time          start of the bucket
 * @param maxUsedPercent largest used share of any file system, in percent
 */
public record FilesystemUsage(Instant time, double maxUsedPercent) {
}
