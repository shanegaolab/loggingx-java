package com.loggingx.common.util;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.Optional;

public final class DateTimes {

    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter PRECISE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    /**
     * {@code 2026-10-12 09:30:00}, optionally with a fraction and with {@code T} in place of the space.
     */
    private static final DateTimeFormatter LOCAL_INPUT_FORMAT = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd")
            .optionalStart().appendLiteral(' ').optionalEnd()
            .optionalStart().appendLiteral('T').optionalEnd()
            .appendPattern("HH:mm:ss")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
            .toFormatter();

    private DateTimes() {
    }

    /**
     * Formats an instant in the server's time zone, e.g. {@code 2026-10-12 09:30:00}; {@code null} stays {@code null}.
     */
    public static String format(Instant instant) {
        return instant == null ? null : DISPLAY_FORMAT.format(instant.atZone(ZoneId.systemDefault()));
    }

    /**
     * Like {@link #format}, with milliseconds, e.g. {@code 2026-10-12 09:30:00.123}.
     */
    public static String formatPrecise(Instant instant) {
        return instant == null ? null : PRECISE_FORMAT.format(instant.atZone(ZoneId.systemDefault()));
    }

    /**
     * Parses an ISO-8601 instant or offset date-time (e.g. {@code 2026-10-12T01:30:00Z}), or a local date-time in the
     * server's time zone (e.g. {@code 2026-10-12 09:30:00.123}).
     *
     * @return empty for blank or unparseable text
     */
    public static Optional<Instant> parse(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String value = text.trim();
        try {
            return Optional.of(OffsetDateTime.parse(value).toInstant());
        } catch (DateTimeParseException ignored) {
            // Not an offset date-time; try a local one.
        }
        try {
            return Optional.of(LocalDateTime.parse(value, LOCAL_INPUT_FORMAT).atZone(ZoneId.systemDefault()).toInstant());
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }
    }
}
