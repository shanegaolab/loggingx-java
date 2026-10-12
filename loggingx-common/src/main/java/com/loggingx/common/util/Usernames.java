package com.loggingx.common.util;

import java.util.Locale;

public final class Usernames {

    private Usernames() {
    }

    /**
     * Usernames are case-insensitive and stored trimmed in lower case.
     */
    public static String normalize(String username) {
        return username == null ? null : username.trim().toLowerCase(Locale.ROOT);
    }
}
