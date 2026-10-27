package com.loggingx.biz.backup;

import com.loggingx.biz.support.SecretCipher;

import com.loggingx.dao.log.LogRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupCodecTest {

    /**
     * The example of the S3 documentation, "Signature Calculations for the Authorization Header: Transferring Payload
     * in a Single Chunk", GET Object.
     */
    @Test
    void signsLikeTheS3Documentation() {
        String authorization = S3Client.authorization("GET",
                URI.create("https://examplebucket.s3.amazonaws.com/test.txt"),
                Map.of("host", "examplebucket.s3.amazonaws.com", "range", "bytes=0-9",
                        "x-amz-content-sha256", S3Client.EMPTY_SHA256, "x-amz-date", "20130524T000000Z"),
                S3Client.EMPTY_SHA256, Instant.parse("2013-05-24T00:00:00Z"), "AKIAIOSFODNN7EXAMPLE",
                "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY", "us-east-1");
        assertEquals("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, "
                + "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, "
                + "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41", authorization);
    }

    @Test
    void addressesBucketsByPathOrHost() {
        S3Client minio = new S3Client("http://minio:9000/", "us-east-1", "logs", "k", "s", true);
        assertEquals("http://minio:9000/logs/loggingx/order-service/2026/10/20.ndjson.gz",
                minio.objectUri("loggingx/order-service/2026/10/20.ndjson.gz").toString());
        S3Client oss = new S3Client("https://oss-cn-hangzhou.aliyuncs.com", "cn-hangzhou", "logs", "k", "s", false);
        assertEquals("https://logs.oss-cn-hangzhou.aliyuncs.com/a%20b.gz", oss.objectUri("a b.gz").toString());
        assertEquals("minio:9000", S3Client.hostHeader(URI.create("http://minio:9000/logs")));
        assertEquals("s3.amazonaws.com", S3Client.hostHeader(URI.create("https://s3.amazonaws.com:443/x")));
    }

    @Test
    void archivesRoundTripWithTheirIdsAndFields(@TempDir Path dir) throws IOException {
        List<LogRecord> logs = new ArrayList<>();
        for (int i = 0; i < 12_345; i++) {
            logs.add(new LogRecord(1000L + i, 7L, Instant.parse("2026-10-20T08:00:00.123456Z").plusMillis(i), i,
                    i % 10 == 0 ? "ERROR" : "INFO", "web-1", "/var/log/app.log", i % 2 == 0 ? "trace-" + i : null,
                    "order " + i + " 处理完成", i % 3 == 0 ? "{\"orderId\":" + i + ",\"user\":\"张三\"}" : null,
                    Instant.parse("2026-10-20T08:00:01Z")));
        }
        Path file = dir.resolve("day.ndjson.gz");
        ArchiveCodec.Written written = ArchiveCodec.write(file, after -> {
            int from = after == null ? 0 : logs.indexOf(after) + 1;
            return logs.subList(from, Math.min(logs.size(), from + 5000));
        });
        assertEquals(12_345, written.records());
        assertTrue(written.sizeBytes() * 5 < written.rawBytes(), "compressed: " + written);
        assertEquals(64, written.sha256().length());

        List<LogRecord> read = new ArrayList<>();
        try (InputStream in = Files.newInputStream(file)) {
            assertEquals(12_345, ArchiveCodec.read(in, 9L, 1000, read::addAll));
        }
        assertEquals(logs.size(), read.size());
        LogRecord original = logs.get(3);
        LogRecord restored = read.get(3);
        assertEquals(original.id(), restored.id());
        assertEquals(9L, restored.storeId(), "filed under the target store");
        assertEquals(original.time(), restored.time());
        assertEquals(original.seq(), restored.seq());
        assertEquals(original.message(), restored.message());
        assertEquals("{\"orderId\":3,\"user\":\"张三\"}", restored.fields());
        assertNull(read.get(1).traceId());
        assertNull(read.get(1).fields());
    }

    @Test
    void secretsAreEncryptedAndTokensSigned() {
        SecretCipher cipher = new SecretCipher("app-secret");
        String encrypted = cipher.encrypt("minio-secret-key");
        assertTrue(encrypted.startsWith("v1:"));
        assertNotEquals(encrypted, cipher.encrypt("minio-secret-key"), "a random IV each time");
        assertEquals("minio-secret-key", cipher.decrypt(encrypted));
        assertThrows(IllegalStateException.class, () -> new SecretCipher("other").decrypt(encrypted));

        String token = cipher.sign("42:1893456000");
        assertEquals("42:1893456000", cipher.verify(token));
        assertNull(cipher.verify(token.replace("42:", "43:")));
        assertNull(new SecretCipher("other").verify(token));
    }
}
