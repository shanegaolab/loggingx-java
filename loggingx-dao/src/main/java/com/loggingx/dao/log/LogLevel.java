package com.loggingx.dao.log;

import java.util.Locale;

/**
 * Log severities, from least to most severe.
 */
public enum LogLevel {
    TRACE, DEBUG, INFO, WARN, ERROR, FATAL;

    /**
     * Maps a producer's level name, ignoring case and accepting common aliases such as {@code warning}, {@code err}
     * or {@code critical}. Missing or unknown names become {@link #INFO}.
     */
    public static LogLevel parse(String name) {
        if (name == null || name.isBlank()) {
            return INFO;
        }
        return switch (name.trim().toUpperCase(Locale.ROOT)) {
            case "TRACE", "FINEST", "FINER" -> TRACE;
            case "DEBUG", "FINE", "VERBOSE" -> DEBUG;
            case "WARN", "WARNING" -> WARN;
            case "ERROR", "ERR", "SEVERE" -> ERROR;
            case "FATAL", "CRITICAL", "CRIT", "PANIC", "EMERG", "ALERT" -> FATAL;
            default -> INFO;
        };
    }
}
