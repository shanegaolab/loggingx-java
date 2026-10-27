package com.loggingx.web.log.dto;

import com.loggingx.common.util.DateTimes;
import com.loggingx.dao.log.LogRecord;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * @param id     as text: ids exceed the integers JavaScript represents exactly
 * @param time   in the server's time zone with milliseconds, e.g. {@code 2026-10-23 16:00:00.123}
 * @param seq    the producer's sequence number of the line within its source, or {@code null} when not given
 * @param fields the extra attributes as a JSON object, or {@code null}
 */
public record LogRecordResponse(
        String id,
        Long storeId,
        String time,
        Long seq,
        String level,
        String host,
        String source,
        String traceId,
        String message,
        JsonNode fields,
        String receivedAt
) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public static LogRecordResponse from(LogRecord record) {
        return new LogRecordResponse(String.valueOf(record.id()), record.storeId(),
                DateTimes.formatPrecise(record.time()), record.seq() == 0 ? null : record.seq(), record.level(),
                record.host(), record.source(), record.traceId(), record.message(), fields(record.fields()),
                DateTimes.formatPrecise(record.receivedAt()));
    }

    private static JsonNode fields(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(json);
        } catch (JacksonException e) {
            return JSON.getNodeFactory().objectNode().put("_raw", json);
        }
    }
}
