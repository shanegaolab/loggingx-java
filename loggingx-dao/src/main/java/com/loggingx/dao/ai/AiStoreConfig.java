package com.loggingx.dao.ai;

import com.loggingx.dao.support.Timestamps;

import java.time.Instant;

/**
 * AI diagnosis of one log store.
 *
 * @param repoUrl      the code repository (https or file URL), or {@code null} for none
 * @param repoBranch   the branch, or {@code null} for the repository's default
 * @param repoSecret   the access token, encrypted
 * @param autoNew      analyse each new kind of error automatically
 * @param onAlert      analyse when a log alert of the store fires
 * @param scannedUntil errors up to this time were grouped
 */
public record AiStoreConfig(Long storeId, String repoUrl, String repoBranch, String repoUsername, String repoSecret,
                            boolean autoNew, boolean onAlert, Instant scannedUntil, String repoCommit,
                            Instant repoSyncedAt, String repoError, Instant updatedAt) {

    public AiStoreConfig {
        scannedUntil = Timestamps.storable(scannedUntil);
        repoSyncedAt = Timestamps.storable(repoSyncedAt);
        updatedAt = Timestamps.storable(updatedAt);
    }
}
