package com.loggingx.web.log;

import com.loggingx.biz.log.LogEntry;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MappingIterator;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the log payload shared by the HTTP ingest API and the Kafka consumer. A payload is one of
 * <ul>
 *     <li>an array of entries,</li>
 *     <li>an object with a {@code logs} array of entries, and optionally the target store's {@code logstore} code,</li>
 *     <li>a single entry, optionally with a {@code logstore} code.</li>
 * </ul>
 * An entry is a flat JSON object. Common names from collectors are accepted: {@code time}/{@code timestamp}/
 * {@code @timestamp}, {@code level}/{@code severity}, {@code host}/{@code hostname}, {@code source}/{@code logger}/
 * {@code file}, {@code traceId}/{@code trace_id}, {@code message}/{@code msg}/{@code log}, and the sequence number
 * {@code seq}/{@code sequence}/{@code offset}, which orders lines with the same time (see
 * {@link com.loggingx.dao.log.LogRecord#ORDER}). Filebeat's nested {@code log.offset}, {@code log.file.path} and
 * {@code host.name} are read too. A {@code fields} object and any other properties are kept as the entry's fields.
 */
public final class LogPayloads {

    static final String STORE_PROPERTY = "logstore";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> TIME = List.of("time", "timestamp", "@timestamp");
    private static final List<String> LEVEL = List.of("level", "severity", "lvl");
    private static final List<String> HOST = List.of("host", "hostname");
    private static final List<String> SOURCE = List.of("source", "logger", "file");
    private static final List<String> TRACE_ID = List.of("traceId", "trace_id", "traceid");
    private static final List<String> MESSAGE = List.of("message", "msg", "log");
    private static final List<String> SEQ = List.of("seq", "sequence", "offset");
    private static final Set<String> KNOWN = Set.of("time", "timestamp", "@timestamp", "level", "severity", "lvl",
            "host", "hostname", "source", "logger", "file", "traceId", "trace_id", "traceid", "message", "msg", "log",
            "seq", "sequence", "offset", "fields", STORE_PROPERTY);

    private LogPayloads() {
    }

    /**
     * @param storeCode the {@code logstore} code the payload names, or {@code null}
     */
    public record Batch(String storeCode, List<LogEntry> entries) {
    }

    /**
     * @throws IllegalArgumentException when the payload is not shaped as described above
     */
    public static Batch parse(JsonNode root) {
        if (root == null || root.isNull()) {
            throw new IllegalArgumentException("The payload is empty");
        }
        if (root.isArray()) {
            return new Batch(null, entries(root));
        }
        if (!root.isObject()) {
            throw new IllegalArgumentException("The payload must be a JSON object or array");
        }
        String storeCode = text(root, List.of(STORE_PROPERTY));
        JsonNode logs = root.get("logs");
        if (logs != null && !logs.isNull()) {
            if (!logs.isArray()) {
                throw new IllegalArgumentException("logs must be an array");
            }
            return new Batch(storeCode, entries(logs));
        }
        return new Batch(storeCode, List.of(entry(root)));
    }

    /**
     * @throws IllegalArgumentException when the text is not JSON or not shaped as described above
     */
    public static Batch parse(String json) {
        List<JsonNode> values;
        try (MappingIterator<JsonNode> iterator = JSON.readerFor(JsonNode.class).readValues(json)) {
            values = iterator.readAll();
        } catch (JacksonException e) {
            throw new IllegalArgumentException("The payload is not valid JSON", e);
        }
        if (values.size() <= 1) {
            return parse(values.isEmpty() ? null : values.getFirst());
        }
        // Newline-delimited JSON: one log per value; a logstore code is taken from the first.
        String storeCode = values.getFirst().isObject() ? text(values.getFirst(), List.of(STORE_PROPERTY)) : null;
        return new Batch(storeCode, values.stream().map(LogPayloads::entry).toList());
    }

    private static List<LogEntry> entries(JsonNode array) {
        List<LogEntry> entries = new ArrayList<>(array.size());
        for (JsonNode node : array) {
            entries.add(entry(node));
        }
        return entries;
    }

    private static LogEntry entry(JsonNode node) {
        if (!node.isObject()) {
            throw new IllegalArgumentException("Each log must be a JSON object");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        JsonNode explicit = node.get("fields");
        if (explicit != null && explicit.isObject()) {
            explicit.properties().forEach(property -> fields.put(property.getKey(), value(property.getValue())));
        }
        node.properties().forEach(property -> {
            // Objects under known names (Filebeat's "log" and "host") are kept too: only scalars are read from them.
            boolean read = KNOWN.contains(property.getKey()) && !property.getValue().isContainer();
            if (!read && !"fields".equals(property.getKey())) {
                fields.put(property.getKey(), value(property.getValue()));
            }
        });
        String host = text(node, HOST);
        String source = text(node, SOURCE);
        Long seq = number(node, SEQ);
        return new LogEntry(text(node, TIME), text(node, LEVEL),
                host != null ? host : nested(node, "/host/name"),
                source != null ? source : nested(node, "/log/file/path"),
                text(node, TRACE_ID), text(node, MESSAGE), fields.isEmpty() ? null : fields,
                seq != null ? seq : parseLong(nested(node, "/log/offset")));
    }

    /**
     * @return the first of the named properties that holds a whole number, also when given as text, or {@code null}
     */
    private static Long number(JsonNode node, List<String> names) {
        for (String name : names) {
            Long value = parseLong(text(node, List.of(name)));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Long parseLong(String text) {
        if (text == null) {
            return null;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * @return the scalar at the JSON pointer, as text, or {@code null}
     */
    private static String nested(JsonNode node, String pointer) {
        JsonNode value = node.at(pointer);
        return value.isValueNode() && !value.isNull() ? value.asString() : null;
    }

    /**
     * @return the first of the named properties that holds a scalar, as text, or {@code null}
     */
    private static String text(JsonNode node, List<String> names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.isValueNode() && !value.isNull()) {
                return value.asString();
            }
        }
        return null;
    }

    private static Object value(JsonNode node) {
        return JSON.treeToValue(node, Object.class);
    }
}
