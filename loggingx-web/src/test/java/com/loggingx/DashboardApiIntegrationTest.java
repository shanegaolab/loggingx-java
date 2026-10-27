package com.loggingx;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The console overview: log volume and errors per level and store, and the sections each role may see.
 */
class DashboardApiIntegrationTest extends ApiIntegrationTest {

    private static long number(String json, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(json);
        assertTrue(matcher.find(), regex + " in " + json);
        return Long.parseLong(matcher.group(1));
    }

    @Test
    void summarizesLogsHostsAndBackupsForWhoMaySeeThem() throws Exception {
        String token = login("super", DEMO_PASSWORD);
        String created = send("POST", "/api/logstore", token,
                "{\"code\":\"it-dashboard\",\"name\":\"Dashboard\",\"retentionDays\":7,\"enabled\":true}").body();
        String storeId = extract(created, "\"storeId\":(\\d+)");
        String ingestToken = extract(created, "\"ingestToken\":\"([^\"]+)\"");
        String before = send("GET", "/api/dashboard/overview?hours=1", token, null).body();
        long totalBefore = number(before, "\"total\":(\\d+)");
        long errorsBefore = number(before, "\"errors\":(\\d+)");

        // 3 INFO and 2 ERROR within the hour, 1 ERROR the hour before.
        String now = Instant.now().minusSeconds(60).toString();
        String earlier = Instant.now().minusSeconds(5400).toString();
        String logs = "[{\"time\":\"" + now + "\",\"level\":\"INFO\",\"message\":\"a\"},"
                + "{\"time\":\"" + now + "\",\"level\":\"INFO\",\"message\":\"b\"},"
                + "{\"time\":\"" + now + "\",\"level\":\"INFO\",\"message\":\"c\"},"
                + "{\"time\":\"" + now + "\",\"level\":\"ERROR\",\"host\":\"web-1\",\"message\":\"boom 1\"},"
                + "{\"time\":\"" + now + "\",\"level\":\"ERROR\",\"message\":\"boom 2\"},"
                + "{\"time\":\"" + earlier + "\",\"level\":\"ERROR\",\"message\":\"older\"}]";
        HttpResponse<String> ingested = HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri("/api/logs/ingest"))
                .header("X-Log-Token", ingestToken).POST(HttpRequest.BodyPublishers.ofString(logs)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, ingested.statusCode(), ingested.body());

        HttpResponse<String> overview = send("GET", "/api/dashboard/overview?hours=1", token, null);
        assertEquals(200, overview.statusCode(), overview.body());
        String body = overview.body();
        assertEquals(totalBefore + 5, number(body, "\"total\":(\\d+)"), body);
        assertEquals(errorsBefore + 2, number(body, "\"errors\":(\\d+)"));
        assertTrue(number(body, "\"previousErrors\":(\\d+)") >= 1, "the hour before counts as previous");
        assertTrue(body.contains("{\"storeId\":" + storeId + ",\"code\":\"it-dashboard\",\"name\":\"Dashboard\","
                + "\"enabled\":true,\"total\":5,\"errors\":2}"), body);
        assertTrue(body.contains("\"message\":\"boom 1\""), "recent errors: " + body);
        int points = body.split("\"time\":\\d+,\"levels\"").length - 1;
        // One point per 2 minutes; the range rarely starts on a bucket boundary, so both ends may be partial.
        assertTrue(points == 30 || points == 31, "points: " + points);
        assertTrue(body.contains("\"agents\":{"));
        assertTrue(body.contains("\"backup\":{\"configured\":"));
        assertEquals(400, send("GET", "/api/dashboard/overview?hours=5", token, null).statusCode());

        // Users see logs and hosts, but not the agents' or backups' summaries.
        String user = send("GET", "/api/dashboard/overview?hours=24", login("user", DEMO_PASSWORD), null).body();
        assertTrue(user.contains("\"logs\":{\"available\":true"), user);
        assertTrue(user.contains("\"hosts\":{"), user);
        assertTrue(user.contains("\"agents\":null") && user.contains("\"backup\":null"), user);
        assertEquals(200, send("DELETE", "/api/logstore/" + storeId, token, null).statusCode());
    }
}
