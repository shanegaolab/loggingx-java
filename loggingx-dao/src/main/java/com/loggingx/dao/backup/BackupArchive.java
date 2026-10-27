package com.loggingx.dao.backup;

import com.loggingx.dao.support.Timestamps;

import java.time.Instant;
import java.time.LocalDate;

/**
 * The archive of one store and day: a gzip-compressed file of JSON lines in the backup bucket.
 *
 * @param day           the day, in the server's time zone
 * @param objectKey     the file's key in the bucket
 * @param recordCount   logs in the file
 * @param rawBytes      size before compression
 * @param sizeBytes     size of the file
 * @param sha256        SHA-256 of the file, hex
 * @param status        {@code success} or {@code failed}
 * @param restoreStatus {@code restoring}, {@code restored}, {@code failed}, or {@code null} when never restored
 * @param restoredUntil a restored archive's logs are kept in the store until then
 */
public record BackupArchive(
        Long id,
        Long storeId,
        String storeCode,
        LocalDate day,
        String objectKey,
        long recordCount,
        long rawBytes,
        long sizeBytes,
        String sha256,
        String status,
        String error,
        Instant archivedAt,
        String restoreStatus,
        String restoreError,
        Instant restoredAt,
        Instant restoredUntil
) {

    public static final String SUCCESS = "success";
    public static final String FAILED = "failed";
    public static final String RESTORING = "restoring";
    public static final String RESTORED = "restored";

    public BackupArchive {
        archivedAt = Timestamps.storable(archivedAt);
        restoredAt = Timestamps.storable(restoredAt);
        restoredUntil = Timestamps.storable(restoredUntil);
    }

    public BackupArchive withRestore(String status, String error, Instant at, Instant until) {
        return new BackupArchive(id, storeId, storeCode, day, objectKey, recordCount, rawBytes, sizeBytes, sha256,
                this.status, this.error, archivedAt, status, error, at, until);
    }
}
