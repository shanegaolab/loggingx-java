package com.loggingx.dao.support;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Instants are stored as UTC {@code DATETIME(6)} values, see {@link UtcInstantTypeHandler}.
 */
public final class Timestamps {

    private Timestamps() {
    }

    /**
     * Truncates to the microseconds a {@code DATETIME(6)} column keeps, so an entity equals itself once stored.
     * Entities apply it on construction.
     */
    public static Instant storable(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }
}
