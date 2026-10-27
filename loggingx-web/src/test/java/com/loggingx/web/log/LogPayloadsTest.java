package com.loggingx.web.log;

import com.loggingx.biz.log.LogEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LogPayloadsTest {

    @Test
    void readsSequenceNumbersUnderAnyAlias() {
        List<LogEntry> entries = LogPayloads.parse("[{\"message\":\"a\",\"seq\":7},{\"message\":\"b\",\"offset\":\"8\"},"
                + "{\"message\":\"c\",\"sequence\":9},{\"message\":\"d\",\"seq\":\"x\"},{\"message\":\"e\"}]").entries();

        assertEquals(List.of(7L, 8L, 9L), entries.subList(0, 3).stream().map(LogEntry::seq).toList());
        assertNull(entries.get(3).seq());
        assertNull(entries.get(4).seq());
    }

    @Test
    void readsFilebeatEvents() {
        LogPayloads.Batch batch = LogPayloads.parse("{\"@timestamp\":\"2026-10-23T08:00:00.123Z\","
                + "\"message\":\"order created\",\"log\":{\"offset\":4096,\"file\":{\"path\":\"/var/log/app.log\"}},"
                + "\"host\":{\"name\":\"web-1\",\"os\":{\"family\":\"debian\"}},\"logstore\":\"orders\"}");

        LogEntry entry = batch.entries().getFirst();
        assertEquals("orders", batch.storeCode());
        assertEquals("order created", entry.message());
        assertEquals(4096L, entry.seq());
        assertEquals("/var/log/app.log", entry.source());
        assertEquals("web-1", entry.host());
        // The objects are kept as fields, so nothing the agent sent is lost.
        assertEquals(Map.of("name", "web-1", "os", Map.of("family", "debian")), entry.fields().get("host"));
    }

    @Test
    void flatPropertiesWinOverNestedOnes() {
        LogEntry entry = LogPayloads.parse("{\"message\":\"m\",\"seq\":1,\"source\":\"svc\","
                + "\"log\":{\"offset\":2,\"file\":{\"path\":\"/f\"}}}").entries().getFirst();

        assertEquals(1L, entry.seq());
        assertEquals("svc", entry.source());
    }

    @Test
    void readsNewlineDelimitedJson() {
        LogPayloads.Batch batch = LogPayloads.parse("{\"message\":\"a\",\"logstore\":\"orders\"}\n"
                + "{\"message\":\"b\",\"seq\":2}\n\n{\"msg\":\"c\"}\n");

        assertEquals("orders", batch.storeCode());
        assertEquals(List.of("a", "b", "c"), batch.entries().stream().map(LogEntry::message).toList());
        assertEquals(2L, batch.entries().get(1).seq());
    }

    @Test
    void rejectsPayloadsOfTheWrongShape() {
        assertThrows(IllegalArgumentException.class, () -> LogPayloads.parse("\"text\""));
        assertThrows(IllegalArgumentException.class, () -> LogPayloads.parse("[1]"));
        assertThrows(IllegalArgumentException.class, () -> LogPayloads.parse("{\"logs\":{}}"));
        assertThrows(IllegalArgumentException.class, () -> LogPayloads.parse("not json"));
    }
}
