package com.loggingx.dao.ai;

import com.loggingx.dao.support.Timestamps;

import java.time.Instant;

/**
 * Settings of the AI diagnosis.
 *
 * @param provider   the AI provider (e.g. {@code deepseek}), or {@code null} for the first configured one
 * @param model      the model, or {@code null} for the provider's default
 * @param dailyLimit analyses started automatically (new errors, alerts) per day; manual ones are not limited
 */
public record AiSettings(String provider, String model, int dailyLimit, Instant updatedAt) {

    public AiSettings {
        updatedAt = Timestamps.storable(updatedAt);
    }
}
