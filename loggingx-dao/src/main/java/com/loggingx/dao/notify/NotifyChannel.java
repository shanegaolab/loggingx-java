package com.loggingx.dao.notify;

import com.loggingx.dao.support.Timestamps;

import java.time.Instant;

/**
 * A notification channel's settings.
 *
 * @param channel {@code email} or {@code sms}
 * @param config  the settings as JSON, without the secret
 * @param secret  the SMTP password or SMS access key secret, encrypted; {@code null} when none
 */
public record NotifyChannel(String channel, boolean enabled, String config, String secret, Instant updatedAt) {

    public NotifyChannel {
        updatedAt = Timestamps.storable(updatedAt);
    }
}
