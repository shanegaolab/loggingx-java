package com.loggingx.dao.monitor;

import java.time.Instant;

/**
 * Usage of one mounted file system in a scrape.
 */
public record FilesystemSample(
        Long storeId,
        String host,
        Instant time,
        String mountpoint,
        String fsType,
        double totalBytes,
        double usedBytes
) {

    /**
     * @return used share in percent, 0 for an empty file system
     */
    public double usedPercent() {
        return totalBytes <= 0 ? 0 : usedBytes * 100 / totalBytes;
    }
}
