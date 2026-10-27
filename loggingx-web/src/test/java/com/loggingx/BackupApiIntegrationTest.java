package com.loggingx;

import com.loggingx.biz.log.LogRetentionService;
import com.loggingx.dao.log.LogRecordRepository;
import com.loggingx.dao.log.LogSearch;
import com.loggingx.dao.log.TimeRange;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Log backups end to end, against an in-process S3 stand-in that checks each upload's payload hash.
 */
class BackupApiIntegrationTest extends ApiIntegrationTest {

    private static final String BUCKET = "it-bucket";

    @Autowired
    private LogRecordRepository logRecordRepository;

    @Autowired
    private LogRetentionService logRetentionService;

    private HttpServer s3;
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    /** When set, uploads fail as if the storage were down. */
    private volatile boolean uploadsFail;

    @BeforeEach
    void startS3() throws IOException {
        s3 = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s3.createContext("/", this::handle);
        s3.start();
    }

    @AfterEach
    void stopS3() {
        s3.stop(0);
    }

    /**
     * Path-style S3: {@code /<bucket>[/<key>]}.
     */
    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath().substring(1);
        String bucket = path.contains("/") ? path.substring(0, path.indexOf('/')) : path;
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        int status;
        byte[] body = new byte[0];
        if (authorization == null || !authorization.startsWith("AWS4-HMAC-SHA256 Credential=it-access/")) {
            status = 403;
        } else if (!BUCKET.equals(bucket)) {
            status = 404;
            body = "<Error><Code>NoSuchBucket</Code><Message>missing</Message></Error>".getBytes();
        } else {
            switch (exchange.getRequestMethod()) {
                case "HEAD" -> status = 200;
                case "PUT" -> {
                    byte[] content = exchange.getRequestBody().readAllBytes();
                    if (uploadsFail) {
                        status = 503;
                        body = "<Error><Code>ServiceUnavailable</Code><Message>down</Message></Error>".getBytes();
                        break;
                    }
                    String hash = exchange.getRequestHeaders().getFirst("x-amz-content-sha256");
                    boolean intact = HexFormat.of().formatHex(sha256(content)).equals(hash);
                    status = intact ? 200 : 400;
                    if (intact) {
                        objects.put(path, content);
                    }
                }
                case "GET" -> {
                    byte[] content = objects.get(path);
                    status = content == null ? 404 : 200;
                    body = content == null ? new byte[0] : content;
                }
                case "DELETE" -> {
                    objects.remove(path);
                    status = 204;
                }
                default -> status = 405;
            }
        }
        exchange.sendResponseHeaders(status, "HEAD".equals(exchange.getRequestMethod()) || body.length == 0 ? -1
                : body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String storage(String bucket) {
        return "{\"endpoint\":\"http://127.0.0.1:" + s3.getAddress().getPort() + "\",\"region\":\"us-east-1\","
                + "\"bucket\":\"" + bucket + "\",\"prefix\":\"it\",\"accessKey\":\"it-access\","
                + "\"secretKey\":\"it-secret\",\"pathStyle\":true}";
    }

    private void await(String token, String path, Predicate<String> done) throws Exception {
        String body = "";
        for (int i = 0; i < 100; i++) {
            body = send("GET", path, token, null).body();
            if (done.test(body)) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Timed out waiting on " + path + ": " + body);
    }

    @Test
    void logsAreNotDeletedBeforeTheyAreArchived() throws Exception {
        String token = login("super", DEMO_PASSWORD);
        assertEquals(200, send("PUT", "/api/backup/storage", token, storage(BUCKET)).statusCode());
        // Archiving is set up together with the store.
        String created = send("POST", "/api/logstore", token, "{\"code\":\"it-backup-hold\",\"name\":\"Hold\","
                + "\"retentionDays\":7,\"enabled\":true,\"backup\":{\"enabled\":true,\"archiveAfterDays\":1,"
                + "\"keepDays\":90}}").body();
        String storeId = extract(created, "\"storeId\":(\\d+)");
        String ingestToken = extract(created, "\"ingestToken\":\"([^\"]+)\"");
        ZoneId zone = ZoneId.systemDefault();
        LocalDate day = LocalDate.now(zone).minusDays(3);
        HttpResponse<String> ingested = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        uri("/api/logs/ingest")).header("X-Log-Token", ingestToken)
                .POST(HttpRequest.BodyPublishers.ofString("[{\"time\":\"" + day.atTime(12, 0).atZone(zone).toInstant()
                        + "\",\"message\":\"kept until archived\"}]")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, ingested.statusCode(), ingested.body());
        TimeRange range = new TimeRange(day.atStartOfDay(zone).toInstant(),
                day.plusDays(1).atStartOfDay(zone).toInstant());
        LogSearch dayLogs = new LogSearch(Long.valueOf(storeId), range.start(), range.end(), null, null, null, null);

        // The storage is down: the archive fails, and the day stays although it is past a 2-day retention.
        uploadsFail = true;
        assertEquals(200, send("POST", "/api/backup/run", token, null).statusCode());
        await(token, "/api/backup/status", body -> body.contains("\"running\":false")
                && body.contains("\"failed\":1"));
        assertEquals(200, send("PUT", "/api/logstore/" + storeId, token,
                "{\"name\":\"Hold\",\"retentionDays\":2,\"enabled\":true}").statusCode());
        logRetentionService.purge();
        assertEquals(1, logRecordRepository.count(dayLogs), "not deleted before it is archived");
        String stores = send("GET", "/api/logstore/list", token, null).body();
        assertTrue(stores.matches("(?s).*\"code\":\"it-backup-hold\".*?\"backup\":\\{\"enabled\":true,"
                + "\"archiveAfterDays\":1,\"keepDays\":90,\"archives\":0,[^}]*\"failed\":1,\"pendingDays\":1\\}.*"),
                stores);

        // Once archived, the next cleanup deletes it.
        uploadsFail = false;
        assertEquals(200, send("POST", "/api/backup/run", token, null).statusCode());
        await(token, "/api/backup/status", body -> body.contains("\"running\":false")
                && body.contains("\"archived\":1"));
        logRetentionService.purge();
        assertEquals(0, logRecordRepository.count(dayLogs));
        assertTrue(send("GET", "/api/logstore/list", token, null).body()
                .matches("(?s).*\"code\":\"it-backup-hold\".*?\"archives\":1,[^}]*\"pendingDays\":0\\}.*"));

        // Deleting the store can delete its archives too.
        assertTrue(objects.keySet().stream().anyMatch(key -> key.contains("/it-backup-hold/")));
        assertEquals(200, send("DELETE", "/api/logstore/" + storeId + "?deleteArchives=true", token, null)
                .statusCode());
        assertFalse(objects.keySet().stream().anyMatch(key -> key.contains("/it-backup-hold/")), "objects deleted");
        assertFalse(send("GET", "/api/backup/archives", token, null).body().contains("it-backup-hold"));
    }

    @Test
    void daysAreArchivedDownloadedAndRestored() throws Exception {
        String token = login("super", DEMO_PASSWORD);
        String created = send("POST", "/api/logstore", token,
                "{\"code\":\"it-backup\",\"name\":\"Backup\",\"retentionDays\":7,\"enabled\":true}").body();
        String storeId = extract(created, "\"storeId\":(\\d+)");
        String ingestToken = extract(created, "\"ingestToken\":\"([^\"]+)\"");

        // Storage: checked before saving; the secret is never returned.
        assertEquals(400, send("POST", "/api/backup/storage/test", token, storage("other-bucket")).statusCode());
        assertEquals(200, send("POST", "/api/backup/storage/test", token, storage(BUCKET)).statusCode());
        assertEquals(200, send("PUT", "/api/backup/storage", token, storage(BUCKET)).statusCode());
        String saved = send("GET", "/api/backup/storage", token, null).body();
        assertTrue(saved.contains("\"secretSaved\":true"), saved);
        assertFalse(saved.contains("it-secret"), "the secret key is not returned");

        // 120 logs three days ago, and one today (not due yet).
        ZoneId zone = ZoneId.systemDefault();
        LocalDate day = LocalDate.now(zone).minusDays(3);
        StringBuilder logs = new StringBuilder("[");
        for (int i = 0; i < 120; i++) {
            logs.append(i == 0 ? "" : ",").append("{\"time\":\"").append(day.atTime(10, 0, i % 60).atZone(zone)
                    .toInstant()).append("\",\"level\":\"INFO\",\"host\":\"web-1\",\"message\":\"order ").append(i)
                    .append(" 完成\",\"orderId\":").append(i).append('}');
        }
        logs.append(",{\"level\":\"INFO\",\"message\":\"today\"}]");
        HttpResponse<String> ingested = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        uri("/api/logs/ingest")).header("X-Log-Token", ingestToken)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(logs.toString(), StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, ingested.statusCode(), ingested.body());

        assertEquals(400, send("PUT", "/api/backup/policies/" + storeId, token,
                "{\"enabled\":true,\"archiveAfterDays\":7,\"keepDays\":30}").statusCode(),
                "a day must be archived before the store's retention deletes it");
        assertEquals(200, send("PUT", "/api/backup/policies/" + storeId, token,
                "{\"enabled\":true,\"archiveAfterDays\":1,\"keepDays\":30}").statusCode());

        assertEquals(200, send("POST", "/api/backup/run", token, null).statusCode());
        await(token, "/api/backup/status", body -> body.contains("\"running\":false")
                && body.contains("\"lastFinishTime\":\"2"));
        String archives = send("GET", "/api/backup/archives?storeId=" + storeId, token, null).body();
        assertTrue(archives.contains("\"day\":\"" + day + "\""), archives);
        assertTrue(archives.contains("\"recordCount\":120"), archives);
        assertTrue(archives.contains("\"status\":\"success\""), archives);
        String key = BUCKET + "/it/it-backup/" + day.format(DateTimeFormatter.ofPattern("yyyy/MM/dd")) + ".ndjson.gz";
        assertTrue(objects.containsKey(key), objects.keySet().toString());
        String policies = send("GET", "/api/backup/policies", token, null).body();
        assertTrue(policies.matches("(?s).*\"storeCode\":\"it-backup\"[^}]*\"archives\":1,.*"), policies);

        // Download: a signed link the browser opens without signing in.
        String archiveId = extract(archives, "\"id\":(\\d+)");
        String link = extract(send("POST", "/api/backup/archives/" + archiveId + "/download-link", token, null).body(),
                "\"url\":\"([^\"]+)\"");
        HttpResponse<byte[]> file = HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri(link)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, file.statusCode());
        assertTrue(file.headers().firstValue("Content-Disposition").orElse("").contains(day + ".ndjson.gz"));
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new GZIPInputStream(
                new ByteArrayInputStream(file.body())), StandardCharsets.UTF_8))) {
            assertEquals(120, reader.lines().count());
        }
        assertEquals(404, HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri(link + "x")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());

        // Restore: refused while the day's logs are still there; once they are gone, they come back.
        assertEquals(409, send("POST", "/api/backup/archives/" + archiveId + "/restore", token, null).statusCode());
        TimeRange range = new TimeRange(day.atStartOfDay(zone).toInstant(),
                day.plusDays(1).atStartOfDay(zone).toInstant());
        Long store = Long.valueOf(storeId);
        logRecordRepository.deleteBetween(store, range);
        assertEquals(200, send("POST", "/api/backup/archives/" + archiveId + "/restore", token, null).statusCode());
        await(token, "/api/backup/archives?storeId=" + storeId, body -> body.contains("\"restoreStatus\":\"restored\""));
        LogSearch dayLogs = new LogSearch(store, range.start(), range.end(), null, null, null, null);
        assertEquals(120, logRecordRepository.count(dayLogs));

        // A retention not longer than the archiving delay is refused while archiving is on.
        assertEquals(400, send("PUT", "/api/logstore/" + storeId, token,
                "{\"name\":\"Backup\",\"retentionDays\":1,\"enabled\":true}").statusCode());

        // Restored logs outlive the store's retention until the restore expires.
        assertEquals(200, send("PUT", "/api/logstore/" + storeId, token, "{\"name\":\"Backup\",\"retentionDays\":1,"
                + "\"enabled\":true,\"backup\":{\"enabled\":false,\"archiveAfterDays\":1,\"keepDays\":30}}")
                .statusCode());
        logRetentionService.purge();
        assertEquals(120, logRecordRepository.count(dayLogs), "restored logs are kept");

        // Archived days are not archived again.
        assertEquals(200, send("PUT", "/api/logstore/" + storeId, token, "{\"name\":\"Backup\",\"retentionDays\":7,"
                + "\"enabled\":true,\"backup\":{\"enabled\":true,\"archiveAfterDays\":1,\"keepDays\":30}}")
                .statusCode());
        assertEquals(200, send("POST", "/api/backup/run", token, null).statusCode());
        await(token, "/api/backup/status", body -> body.contains("\"running\":false"));
        String again = send("GET", "/api/backup/archives?storeId=" + storeId, token, null).body();
        assertEquals(1, again.split("\"objectKey\"").length - 1, again);

        String userToken = login("user", DEMO_PASSWORD);
        assertEquals(403, send("GET", "/api/backup/archives", userToken, null).statusCode());
        assertEquals(200, send("DELETE", "/api/logstore/" + storeId, token, null).statusCode());
    }
}
