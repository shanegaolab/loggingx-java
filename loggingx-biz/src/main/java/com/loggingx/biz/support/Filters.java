package com.loggingx.biz.support;

import java.util.Locale;

/**
 * Helpers for optional list filters, where a blank filter matches everything.
 */
public final class Filters {

    private Filters() {
    }

    public static boolean containsIgnoreCase(String value, String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        return value != null && value.toLowerCase(Locale.ROOT).contains(filter.trim().toLowerCase(Locale.ROOT));
    }

    public static boolean equalsIfPresent(Object value, Object filter) {
        if (filter == null || filter instanceof String text && text.isBlank()) {
            return true;
        }
        return filter.equals(value);
    }
}
