package com.loggingx.dao.backup;

import com.loggingx.dao.support.Timestamps;

import java.time.Instant;

/**
 * Archiving of one log store.
 *
 * @param archiveAfterDays a day is archived once it is this many days old; less than the store's retention, so
 *                         every day is archived before its logs are deleted
 * @param keepDays         archives are deleted this many days after their day
 */
public record BackupPolicy(
        Long storeId,
        boolean enabled,
        int archiveAfterDays,
        int keepDays,
        Instant updatedAt
) {

    public BackupPolicy {
        updatedAt = Timestamps.storable(updatedAt);
    }
}
