package com.loggingx.common.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class DateTimes {

    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private DateTimes() {
    }

    /**
     * Formats an instant in the server's time zone, e.g. {@code 2026-10-12 09:30:00}; {@code null} stays {@code null}.
     */
    public static String format(Instant instant) {
        return instant == null ? null : DISPLAY_FORMAT.format(instant.atZone(ZoneId.systemDefault()));
    }
}
