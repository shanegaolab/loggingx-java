package com.loggingx;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogApiIntegrationTest extends ApiIntegrationTest {

    private HttpResponse<String> ingest(String token, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/logs/ingest"))
                .header("Content-Type", "application/json")
                .header("X-Log-Token", token)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> ingestFromAgent(String token, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/logs/ingest"))
                .header("X-Log-Token", token)
                .header("X-Log-Dedup", "batch")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private long total(String token, String storeId) throws Exception {
        String body = send("GET", "/api/logs/search?storeId=" + storeId
                + "&startTime=2026-10-24T00:00:00Z&endTime=2026-10-25T00:00:00Z&current=1&size=10", token, null).body();
        return Long.parseLong(extract(body, "\"total\":(\\d+)"));
    }

    /**
     * An agent that retries a batch whose answer it lost sends the same body again; it is stored once. Batches that
     * failed are not remembered, and clients that do not ask for deduplication may repeat lines.
     */
    @Test
    void retriedAgentBatchesAreStoredOnce() throws Exception {
        String token = login("super", DEMO_PASSWORD);
        String created = createStore(token, "it-dedup");
        String storeId = extract(created, "\"storeId\":(\\d+)");
        String ingestToken = extract(created, "\"ingestToken\":\"([^\"]+)\"");
        String batch = "[{\"time\":\"2026-10-24T08:00:00Z\",\"host\":\"web-1\",\"source\":\"/var/log/a.log\","
                + "\"seq\":10,\"message\":\"a\"},{\"time\":\"2026-10-24T08:00:00Z\",\"host\":\"web-1\","
                + "\"source\":\"/var/log/a.log\",\"seq\":12,\"message\":\"b\"}]";

        assertEquals(401, ingestFromAgent("lx_wrong", batch).statusCode());
        HttpResponse<String> first = ingestFromAgent(ingestToken, batch);
        assertTrue(first.body().contains("\"accepted\":2,\"rejected\":0,\"duplicate\":0"), first.body());
        HttpResponse<String> retried = ingestFromAgent(ingestToken, batch);
        assertEquals(200, retried.statusCode());
        assertTrue(retried.body().contains("\"accepted\":0,\"rejected\":0,\"duplicate\":2"), retried.body());
        assertEquals(2, total(token, storeId));

        // Without the header every request is stored.
        assertEquals(200, ingest(ingestToken, batch).statusCode());
        assertEquals(4, total(token, storeId));
        assertEquals(200, send("DELETE", "/api/logstore/" + storeId, token, null).statusCode());
    }

    private String createStore(String token, String code) throws Exception {
        HttpResponse<String> created = send("POST", "/api/logstore", token,
                "{\"code\":\"" + code + "\",\"name\":\"" + code + "\",\"retentionDays\":7,\"enabled\":true}");
        assertEquals(201, created.statusCode(), created.body());
        return created.body();
    }

    @Test
    void storesCanBeManagedAndIngestedIntoAndSearched() throws Exception {
        String token = login("super", DEMO_PASSWORD);
        String created = createStore(token, "it-orders");
        String storeId = extract(created, "\"storeId\":(\\d+)");
        String ingestToken = extract(created, "\"ingestToken\":\"([^\"]+)\"");

        assertEquals(409, send("POST", "/api/logstore", token,
                "{\"code\":\"it-orders\",\"name\":\"x\",\"retentionDays\":7,\"enabled\":true}").statusCode());

        // The three payload shapes: an array, an object with logs, and a single log with collector field names.
        HttpResponse<String> array = ingest(ingestToken, "[{\"time\":\"2026-10-23T08:00:00Z\",\"level\":\"info\","
                + "\"message\":\"order created\",\"host\":\"web-1\",\"orderId\":42},"
                + "{\"time\":\"2026-10-23T08:00:01Z\",\"level\":\"error\",\"message\":\"order 100% failed\","
                + "\"traceId\":\"t-9\"},{\"message\":\"\"}]");
        assertEquals(200, array.statusCode(), array.body());
        assertTrue(array.body().contains("\"accepted\":2") && array.body().contains("\"rejected\":1"), array.body());
        assertEquals(200, ingest(ingestToken, "{\"logstore\":\"ignored\",\"logs\":[{\"@timestamp\":"
                + "\"2026-10-23T08:00:02Z\",\"severity\":\"WARNING\",\"msg\":\"slow\"}]}").statusCode());
        assertEquals(200, ingest(ingestToken, "{\"timestamp\":1761206403000,\"log\":\"single\"}").statusCode());
        assertEquals(401, ingest("lx_wrong", "[{\"message\":\"m\"}]").statusCode());
        assertEquals(400, ingest(ingestToken, "\"just text\"").statusCode());

        HttpResponse<String> search = send("GET", "/api/logs/search?storeId=" + storeId
                + "&startTime=2026-10-23T08:00:00Z&endTime=2026-10-23T09:00:00Z&levels=ERROR,WARN&current=1&size=10",
                token, null);
        assertEquals(200, search.statusCode(), search.body());
        assertTrue(search.body().contains("\"total\":2"), search.body());
        assertTrue(search.body().contains("\"message\":\"order 100% failed\""), search.body());
        // Level counts ignore the level filter but not the time range, which the epoch-time log (2025) is outside.
        assertTrue(search.body().contains("\"INFO\":1"), search.body());
        HttpResponse<String> keyword = send("GET", "/api/logs/search?storeId=" + storeId + "&keyword=100%25",
                token, null);
        assertTrue(keyword.body().contains("\"total\":1"), keyword.body());
        HttpResponse<String> fields = send("GET", "/api/logs/search?storeId=" + storeId + "&keyword=created",
                token, null);
        assertTrue(fields.body().contains("\"fields\":{\"orderId\":42}"), fields.body());
        assertTrue(fields.body().contains("\"id\":\""), "ids are sent as text: " + fields.body());
        assertEquals(400, send("GET", "/api/logs/search?storeId=" + storeId + "&startTime=yesterday", token, null)
                .statusCode());

        String regenerated = send("POST", "/api/logstore/" + storeId + "/token", token, null).body();
        String newToken = extract(regenerated, "\"ingestToken\":\"([^\"]+)\"");
        assertEquals(401, ingest(ingestToken, "[{\"message\":\"m\"}]").statusCode());
        assertEquals(200, ingest(newToken, "[{\"message\":\"m\"}]").statusCode());

        HttpResponse<String> list = send("GET", "/api/logstore/list", token, null);
        assertTrue(list.body().contains("\"code\":\"it-orders\""), list.body());
        assertEquals(200, send("DELETE", "/api/logstore/" + storeId, token, null).statusCode());
        assertEquals(401, ingest(newToken, "[{\"message\":\"m\"}]").statusCode());
    }

    @Test
    void tailStreamsTheBacklogAndThenNewMatchingLogs() throws Exception {
        String token = login("super", DEMO_PASSWORD);
        String created = createStore(token, "it-tail");
        String storeId = extract(created, "\"storeId\":(\\d+)");
        String ingestToken = extract(created, "\"ingestToken\":\"([^\"]+)\"");
        ingest(ingestToken, "[{\"level\":\"ERROR\",\"message\":\"before subscribing\"}]");

        HttpRequest request = HttpRequest.newBuilder(uri("/api/logs/tail?storeId=" + storeId + "&levels=ERROR"))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "text/event-stream")
                .build();
        HttpResponse<Stream<String>> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofLines());
        assertEquals(200, response.statusCode());
        Iterator<String> lines = response.body().iterator();
        List<String> received = readUntil(lines, "event:ready");
        assertTrue(received.stream().anyMatch(line -> line.contains("before subscribing")), received.toString());

        ingest(ingestToken, "[{\"level\":\"INFO\",\"message\":\"filtered out\"},"
                + "{\"level\":\"ERROR\",\"message\":\"after subscribing\"}]");
        received = readUntil(lines, "after subscribing");
        assertFalse(received.stream().anyMatch(line -> line.contains("filtered out")), received.toString());
        response.body().close();

        assertEquals(200, send("DELETE", "/api/logstore/" + storeId, token, null).statusCode());
    }

    @Test
    void logPagesRequireTheirMenus() throws Exception {
        String token = login("user", DEMO_PASSWORD);

        // Regular users may query and follow logs, but not see ingest tokens or manage stores.
        assertEquals(403, send("GET", "/api/logstore/list", token, null).statusCode());
        assertEquals(403, send("POST", "/api/logstore", token,
                "{\"code\":\"nope\",\"name\":\"x\",\"retentionDays\":7,\"enabled\":true}").statusCode());
        HttpResponse<String> options = send("GET", "/api/logstore/options", token, null);
        assertEquals(200, options.statusCode(), options.body());
        assertFalse(options.body().contains("ingestToken"), options.body());
        assertEquals(401, send("GET", "/api/logs/search?storeId=1", null, null).statusCode());
    }

    private static List<String> readUntil(Iterator<String> lines, String marker) throws Exception {
        return CompletableFuture.supplyAsync(() -> {
            List<String> read = new ArrayList<>();
            while (lines.hasNext()) {
                String line = lines.next();
                read.add(line);
                if (line.contains(marker)) {
                    return read;
                }
            }
            throw new IllegalStateException("Stream ended before " + marker + ": " + read);
        }).get(10, TimeUnit.SECONDS);
    }
}
