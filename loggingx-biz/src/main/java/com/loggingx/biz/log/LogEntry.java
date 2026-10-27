package com.loggingx.biz.log;

import java.util.Map;

/**
 * One log line as a producer sends it, before validation.
 *
 * @param time   ISO-8601 date-time, local {@code yyyy-MM-dd HH:mm:ss[.SSS]} in the server's time zone, or epoch
 *               seconds / milliseconds; {@code null} means the time it is received
 * @param level  any common level name, see {@link com.loggingx.dao.log.LogLevel#parse}
 * @param fields extra attributes, stored as a JSON object
 * @param seq    the line's position within its source, increasing along the source (e.g. the byte offset in the log
 *               file); orders lines with the same time. {@code null} or negative means none
 */
public record LogEntry(
        String time,
        String level,
        String host,
        String source,
        String traceId,
        String message,
        Map<String, Object> fields,
        Long seq
) {

    /**
     * Creates an entry without a sequence number.
     */
    public LogEntry(String time, String level, String host, String source, String traceId, String message,
                    Map<String, Object> fields) {
        this(time, level, host, source, traceId, message, fields, null);
    }
}
