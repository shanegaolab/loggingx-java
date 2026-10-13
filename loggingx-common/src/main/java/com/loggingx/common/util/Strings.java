package com.loggingx.common.util;

public final class Strings {

    private Strings() {
    }

    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * @return the trimmed value, or {@code null} when it is blank
     */
    public static String trimToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    /**
     * @return the trimmed value, or {@code fallback} when it is blank
     */
    public static String trimOr(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }
}
