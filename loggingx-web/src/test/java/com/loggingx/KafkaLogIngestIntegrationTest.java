package com.loggingx;

import com.loggingx.web.log.KafkaLogListener;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Publishes logs to an embedded Kafka broker and finds them through the search API. It runs in its own application
 * context, with the Kafka consumer enabled.
 */
@EmbeddedKafka(partitions = 3, topics = "loggingx-it-logs")
@TestPropertySource(properties = {
        "loggingx.log.kafka.enabled=true",
        "loggingx.log.kafka.topics=loggingx-it-logs",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.consumer.auto-offset-reset=earliest"
})
class KafkaLogIngestIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private KafkaTemplate<String, String> kafka;

    private String searchUntil(String token, String storeId, String expected) throws Exception {
        String body = "";
        for (int attempt = 0; attempt < 100 && !body.contains(expected); attempt++) {
            Thread.sleep(200);
            body = send("GET", "/api/logs/search?storeId=" + storeId, token, null).body();
        }
        assertTrue(body.contains(expected), body);
        return body;
    }

    @Test
    void linesWithTheSameTimeKeepTheirSourceOrderAcrossPartitions() throws Exception {
        String token = login("super", DEMO_PASSWORD);
        String storeId = extract(send("POST", "/api/logstore", token,
                "{\"code\":\"it-order\",\"name\":\"Order\",\"retentionDays\":7,\"enabled\":true}").body(),
                "\"storeId\":(\\d+)");

        // One second of one file, published to different partitions in reverse order; the store is named by header,
        // leaving the key free (here: the file). Only the sequence number (the file offset) tells the order.
        for (int line = 4; line >= 1; line--) {
            ProducerRecord<String, String> message = new ProducerRecord<>("loggingx-it-logs", line % 3,
                    "web-1:/var/log/app.log", "{\"time\":\"2026-10-23 08:00:00\",\"host\":\"web-1\","
                    + "\"message\":\"line " + line + "\",\"log\":{\"offset\":" + line * 100 + "}}");
            message.headers().add(KafkaLogListener.STORE_HEADER, "it-order".getBytes(StandardCharsets.UTF_8));
            kafka.send(message).get();
        }

        String body = searchUntil(token, storeId, "\"total\":4");
        int[] positions = {body.indexOf("line 4"), body.indexOf("line 3"), body.indexOf("line 2"),
                body.indexOf("line 1")};
        assertTrue(positions[0] < positions[1] && positions[1] < positions[2] && positions[2] < positions[3],
                "newest first, in file order: " + body);
        assertEquals(200, send("DELETE", "/api/logstore/" + storeId, token, null).statusCode());
    }

    @Test
    void logsPublishedToKafkaAreStored() throws Exception {
        String token = login("super", DEMO_PASSWORD);
        String storeId = extract(send("POST", "/api/logstore", token,
                "{\"code\":\"it-kafka\",\"name\":\"Kafka\",\"retentionDays\":7,\"enabled\":true}").body(),
                "\"storeId\":(\\d+)");

        // The store is named by the payload, or by the message key; malformed and unknown-store messages are skipped.
        kafka.send("loggingx-it-logs", null, "{\"logstore\":\"it-kafka\",\"logs\":[{\"level\":\"ERROR\","
                + "\"message\":\"from kafka payload\"}]}");
        kafka.send("loggingx-it-logs", "it-kafka", "{\"level\":\"WARN\",\"msg\":\"from kafka key\"}");
        kafka.send("loggingx-it-logs", "it-kafka", "not json");
        kafka.send("loggingx-it-logs", "no-such-store", "{\"message\":\"lost\"}");
        kafka.send("loggingx-it-logs", "it-kafka", "{\"message\":\"after the bad ones\"}").get();

        String body = "";
        for (int attempt = 0; attempt < 100 && !body.contains("\"total\":3"); attempt++) {
            Thread.sleep(200);
            HttpResponse<String> search = send("GET", "/api/logs/search?storeId=" + storeId, token, null);
            body = search.body();
        }
        assertTrue(body.contains("\"total\":3"), body);
        assertTrue(body.contains("from kafka payload") && body.contains("from kafka key")
                && body.contains("after the bad ones"), body);
        assertEquals(200, send("DELETE", "/api/logstore/" + storeId, token, null).statusCode());
    }
}
