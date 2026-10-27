package com.loggingx.dao.backup;

import com.loggingx.dao.support.Timestamps;

import java.time.Instant;

/**
 * The S3-compatible bucket archives are written to.
 *
 * @param endpoint  e.g. {@code https://oss-cn-hangzhou.aliyuncs.com} or {@code http://minio:9000}
 * @param prefix    key prefix of all archives, e.g. {@code loggingx}; may be empty
 * @param secretKey the secret access key, encrypted by the application
 * @param pathStyle whether the bucket is addressed in the path ({@code endpoint/bucket/key}, as MinIO needs) rather
 *                  than the host name ({@code bucket.endpoint/key})
 */
public record BackupStorage(
        String endpoint,
        String region,
        String bucket,
        String prefix,
        String accessKey,
        String secretKey,
        boolean pathStyle,
        Instant updatedAt
) {

    public BackupStorage {
        updatedAt = Timestamps.storable(updatedAt);
    }
}
